package com.finsent.core.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.LoadedDay;
import com.finsent.core.io.WriteUnit;
import com.finsent.util.GlobalSystem;

/**
 * Registry of collected articles, stored as JSONL day-files ({@code articles_*.jsonl}).
 * This is the stateful registry: it owns the monotonic article-id counter, the
 * per-source publication-time watermarks and the URL+title hash set used for dedup &mdash;
 * the runtime state that the Python {@code shared.get_state()} reconstructs from the data
 * files. IDs are assigned atomically under the registry lock so concurrent collector and
 * urgent threads never produce duplicates. Mutations update memory and return
 * {@link WriteUnit}s for the collector to commit; the registry itself never touches disk.
 */
public final class ArticleRegistry implements IRegistry
{
    private static final String NAME = "ArticleRegistry";

    private static final String F_ID = "id";
    private static final String F_HASH = "hash";
    private static final String F_COLLECTED_AT = "collected_at";
    private static final String F_PUBLISHED_AT = "publishedAt";
    private static final String F_SOURCE_OBJECT = "source"; // the {id?, name} provenance object
    private static final String F_URL = "url";
    private static final String F_TITLE = "title";
    private static final String F_RSS_FEED = "_rss_feed";
    private static final String F_SOURCE = "_source";
    // Analysis-only annotations the screener writes onto articles; stripped on load so they never
    // round-trip into the collector-owned articles file (which must hold collector data only).
    private static final List<String> ANALYSIS_FIELDS = List.of("screener_score", "screener_reason");
    private static final String F_URGENT = "urgent_worthy";

    private final Object lock_ = new Object();
    private final Map<String, List<ObjectNode>> byDay_ = new HashMap<>();
    private final Map<String, String> watermarkBySource_ = new HashMap<>();
    private final Set<String> hashes_ = new HashSet<>();
    private int nextId_ = 1;

    @Override
    public DataStream stream()
    {
        return DataStream.ARTICLES;
    }

    @Override
    public void hydrate(List<LoadedDay> days)
    {
        synchronized (lock_)
        {
            int maxId = 0;
            for (LoadedDay loaded : days)
            {
                maxId = Math.max(maxId, hydrateDay(loaded));
            }
            nextId_ = maxId + 1;
        }
        GlobalSystem.info().writes(NAME, "Recovered: nextId=" + nextId_
                + ", watermarks=" + watermarkBySource_.size() + " source(s)");
    }

    @Override
    public boolean isResident(String day)
    {
        synchronized (lock_)
        {
            return byDay_.containsKey(day);
        }
    }

    @Override
    public void ensureDayResident(LoadedDay loaded)
    {
        synchronized (lock_)
        {
            if (!byDay_.containsKey(loaded.day()))
            {
                // Populates the day's articles + dedup hashes + watermarks for read access; the
                // returned max-id is deliberately ignored so loading an older day never regresses
                // the monotonic id counter (unlike hydrate, which seeds it).
                hydrateDay(loaded);
            }
        }
    }

    private int hydrateDay(LoadedDay loaded)
    {
        int maxId = 0;
        List<ObjectNode> articles = new ArrayList<>();
        for (JsonNode node : loaded.payload())
        {
            if (node instanceof ObjectNode)
            {
                ObjectNode article = (ObjectNode) node;
                article.remove(ANALYSIS_FIELDS); // clean any analysis fields that leaked into older files
                articles.add(article);
                maxId = Math.max(maxId, article.path(F_ID).asInt(0));
                String hash = article.path(F_HASH).asText("");
                if (!hash.isEmpty())
                {
                    hashes_.add(hash);
                }
                updateWatermark(article);
            }
        }
        byDay_.put(loaded.day(), articles);
        return maxId;
    }

    /**
     * Assign ids and store fresh articles, deduplicating against the URL+title hash set.
     * Returns the write-unit(s) for the day-files that changed (empty when every article was a
     * duplicate). Ports {@code collect.store_articles}.
     */
    public List<WriteUnit> store(List<ObjectNode> articles)
    {
        List<WriteUnit> writes = List.of();
        if (articles != null && !articles.isEmpty())
        {
            writes = storeLocked(articles);
        }
        return writes;
    }

    private List<WriteUnit> storeLocked(List<ObjectNode> articles)
    {
        Set<String> affectedDays = new HashSet<>();
        synchronized (lock_)
        {
            String collectedAt = Times.nowUtcIso();
            for (ObjectNode article : articles)
            {
                storeOne(article, collectedAt, affectedDays);
            }
        }
        List<WriteUnit> writes = new ArrayList<>();
        for (String day : affectedDays)
        {
            writes.add(writeUnitFor(day));
        }
        return writes;
    }

    private void storeOne(ObjectNode article, String collectedAt, Set<String> affectedDays)
    {
        String hash = articleHash(article);
        if (!hashes_.contains(hash))
        {
            int id = nextId_++;
            // Stamp the id on the input node too: the collector tells genuinely-new (non-duplicate)
            // articles apart by whether store() assigned them an id (FSCollector.newlyStored).
            article.put(F_ID, id);
            ObjectNode stored = canonicalize(article, id, hash, collectedAt);
            hashes_.add(hash);
            String day = Times.dayOf(stored.path(F_PUBLISHED_AT).asText(""));
            byDay_.computeIfAbsent(day, d -> new ArrayList<>()).add(stored);
            updateWatermark(stored);
            affectedDays.add(day);
        }
    }

    /**
     * Build the canonical persisted article: the identity/timing fields ({@code id}, {@code publishedAt},
     * {@code collected_at}) lead so a day-file scans easily, then the source-built fields in their original
     * order, then the dedup {@code hash}. Drops the always-null {@code source.id} (every source nulls it);
     * everything else &mdash; including {@code author}, which the X squawk feed populates &mdash; is kept.
     */
    private static ObjectNode canonicalize(ObjectNode article, int id, String hash, String collectedAt)
    {
        ObjectNode stored = Json.newObject();
        stored.put(F_ID, id);
        stored.put(F_PUBLISHED_AT, article.path(F_PUBLISHED_AT).asText(""));
        stored.put(F_COLLECTED_AT, collectedAt);
        Iterator<Map.Entry<String, JsonNode>> fields = article.fields();
        while (fields.hasNext())
        {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (!isLeadOrHashField(name)) // these are placed explicitly (front/back), not copied in place
            {
                stored.set(name, name.equals(F_SOURCE_OBJECT) ? sourceWithoutId(field.getValue()) : field.getValue());
            }
        }
        stored.put(F_HASH, hash);
        return stored;
    }

    /** The fields {@link #canonicalize} positions itself (lead trio + trailing hash), excluded from the copy loop. */
    private static boolean isLeadOrHashField(String name)
    {
        return name.equals(F_ID) || name.equals(F_PUBLISHED_AT) || name.equals(F_COLLECTED_AT) || name.equals(F_HASH);
    }

    /** Copy the {@code source} provenance object without its always-null {@code id} field. */
    private static ObjectNode sourceWithoutId(JsonNode source)
    {
        ObjectNode stored = Json.newObject();
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext())
        {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getKey().equals(F_ID))
            {
                stored.set(field.getKey(), field.getValue());
            }
        }
        return stored;
    }

    /** A copy of the per-source publication-time watermarks (source key &rarr; max publishedAt). */
    public Map<String, String> watermarksSnapshot()
    {
        synchronized (lock_)
        {
            return new HashMap<>(watermarkBySource_);
        }
    }

    /**
     * Articles for an interval, sorted by id. With {@code urgentOnly} returns only those
     * flagged {@code urgent_worthy}. Served from the in-memory working set; days outside it
     * (older than the recovered window) return empty.
     *
     * <p>Returns <b>deep copies</b>: the analyser/screener annotate window articles in place
     * ({@code screener_score} etc.), so handing out the registry's own nodes would both leak those
     * analysis fields into the collector-owned articles file and race the persistence thread that
     * serialises them. Copies keep the stored objects collector-only and immutable to consumers.
     */
    public List<ObjectNode> forInterval(String day, String intervalKey, boolean urgentOnly, int windowMinutes)
    {
        List<ObjectNode> source = snapshotDay(day);
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode article : source)
        {
            boolean keyMatch = Times.intervalKey(article.path(F_PUBLISHED_AT).asText(""), windowMinutes)
                    .equals(intervalKey);
            boolean urgentOk = !urgentOnly || article.path(F_URGENT).asBoolean(false);
            if (keyMatch && urgentOk)
            {
                result.add(article.deepCopy());
            }
        }
        result.sort(Comparator.comparingInt(a -> a.path(F_ID).asInt(0)));
        return result;
    }

    private List<ObjectNode> snapshotDay(String day)
    {
        synchronized (lock_)
        {
            List<ObjectNode> inMemory = byDay_.get(day);
            return inMemory == null ? new ArrayList<>() : new ArrayList<>(inMemory);
        }
    }

    private void updateWatermark(ObjectNode article)
    {
        String key = sourceKey(article);
        String published = article.path(F_PUBLISHED_AT).asText("");
        if (!key.isEmpty() && !published.isEmpty()
                && published.compareTo(watermarkBySource_.getOrDefault(key, "")) > 0)
        {
            watermarkBySource_.put(key, published);
        }
    }

    private static String sourceKey(ObjectNode article)
    {
        String key = article.path(F_RSS_FEED).asText("");
        return key.isEmpty() ? article.path(F_SOURCE).asText("") : key;
    }

    /** Render a day's articles, sorted by publishedAt, as the JSONL payload for its file. */
    private WriteUnit writeUnitFor(String day)
    {
        List<ObjectNode> snapshot;
        synchronized (lock_)
        {
            List<ObjectNode> list = byDay_.get(day);
            snapshot = list == null ? new ArrayList<>() : new ArrayList<>(list);
        }
        snapshot.sort(Comparator.comparing(a -> a.path(F_PUBLISHED_AT).asText("")));
        ArrayNode payload = Json.newArray();
        for (ObjectNode article : snapshot)
        {
            payload.add(article.deepCopy());
        }
        return new WriteUnit(DataStream.ARTICLES, day, payload);
    }

    private static String articleHash(ObjectNode article)
    {
        return md5Hex(article.path(F_URL).asText("") + article.path(F_TITLE).asText(""));
    }

    private static String md5Hex(String text)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException md5Missing)
        {
            throw new IllegalStateException("MD5 algorithm unavailable", md5Missing);
        }
    }
}
