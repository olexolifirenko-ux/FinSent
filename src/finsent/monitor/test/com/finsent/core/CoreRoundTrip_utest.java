package com.finsent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.finsent.core.event.EventBus;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.PersistenceService;
import com.finsent.core.io.WriteUnit;
import com.finsent.core.registry.ArticleRegistry;
import com.finsent.core.registry.IntervalSnapshotRegistry;
import com.finsent.core.registry.OhlcRegistry;
import com.finsent.util.xml.XMLData;

/**
 * Round-trip verification of the core layer under the unit-of-work design: pure in-memory
 * registries + the persistence disk boundary + event bus + config. Proves recover (load &rarr;
 * hydrate) &rarr; mutate &rarr; commit-as-one-atomic-batch &rarr; reload with exact
 * {@code ObjectNode} shapes, hash dedup across a restart, snapshot/OHLC semantics, a multi-stream
 * batch committing atomically, event delivery, and config parsing.
 */
public class CoreRoundTrip_utest
{
    private static final String DAY = "20260531";
    private static final String KEY = "12:30";
    private static final int WINDOW = 10;

    private Path dir_;

    @Before
    public void setUp() throws IOException
    {
        dir_ = Files.createTempDirectory("fs-core-utest");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> paths = Files.walk(dir_))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(CoreRoundTrip_utest::deleteQuietly);
        }
    }

    @Test
    public void articlesPersistRecoverAndDedup() throws Exception
    {
        PersistenceService persistence = new PersistenceService(dir_);
        try
        {
            ArticleRegistry registry = new ArticleRegistry();
            registry.hydrate(persistence.load(DataStream.ARTICLES, 3));
            List<WriteUnit> writes = registry.store(List.of(
                    article("Fed cuts rates", "http://x/1", "2026-05-31T12:31:00Z"),
                    article("ETF inflows surge", "http://x/2", "2026-05-31T12:34:00Z")));
            assertFalse("fresh articles produce a write", writes.isEmpty());
            persistence.commit(writes);
            persistence.flush();

            List<String> lines = Files.readAllLines(dir_.resolve(DAY).resolve("articles_" + DAY + ".jsonl"));
            assertEquals(2, lines.size());
            assertTrue("id assigned", Json.parse(lines.get(0)).has("id"));
            assertTrue("hash assigned", Json.parse(lines.get(0)).has("hash"));
        }
        finally
        {
            persistence.shutdown();
        }

        // Restart: a fresh registry recovers id/hash/watermark state from the files.
        PersistenceService persistence2 = new PersistenceService(dir_);
        try
        {
            ArticleRegistry recovered = new ArticleRegistry();
            recovered.hydrate(persistence2.load(DataStream.ARTICLES, 3));

            assertTrue("duplicate dropped after recovery",
                    recovered.store(List.of(article("Fed cuts rates", "http://x/1", "2026-05-31T12:31:00Z"))).isEmpty());

            ObjectNode fresh = article("Sanctions imposed", "http://x/3", "2026-05-31T12:35:00Z");
            assertFalse("fresh article produces a write", recovered.store(List.of(fresh)).isEmpty());
            assertEquals("id continues from recovered counter", 3, fresh.path("id").asInt());

            List<ObjectNode> interval = recovered.forInterval(DAY, KEY, false, WINDOW);
            assertEquals(3, interval.size());
            assertEquals(1, interval.get(0).path("id").asInt());
            assertEquals(3, interval.get(2).path("id").asInt());
        }
        finally
        {
            persistence2.shutdown();
        }
    }

    @Test
    public void ensureDayResidentLoadsForReadWithoutRegressingIds() throws Exception
    {
        PersistenceService persistence = new PersistenceService(dir_);
        try
        {
            // Seed an older day on disk (ids 1 and 2 for DAY).
            ArticleRegistry seed = new ArticleRegistry();
            persistence.commit(seed.store(List.of(
                    article("Older A", "http://x/1", "2026-05-31T12:31:00Z"),
                    article("Older B", "http://x/2", "2026-05-31T12:34:00Z"))));
            persistence.flush();

            // A fresh registry with its own counter assigns id 1 to a new (different-day) article.
            ArticleRegistry registry = new ArticleRegistry();
            ObjectNode fresh = article("New C", "http://x/3", "2026-06-01T09:00:00Z");
            registry.store(List.of(fresh));
            assertEquals(1, fresh.path("id").asInt());
            assertFalse("older day not resident yet", registry.isResident(DAY));

            // Lazily load the older day for read-only access.
            registry.ensureDayResident(persistence.loadDay(DataStream.ARTICLES, DAY));
            assertTrue(registry.isResident(DAY));
            assertEquals(2, registry.forInterval(DAY, KEY, false, WINDOW).size());

            // The id counter was NOT regressed by loading the older day -- the next id continues at 2.
            ObjectNode next = article("New D", "http://x/4", "2026-06-01T09:01:00Z");
            registry.store(List.of(next));
            assertEquals(2, next.path("id").asInt());
        }
        finally
        {
            persistence.shutdown();
        }
    }

    @Test
    public void snapshotStoredOnceAndReloaded() throws Exception
    {
        PersistenceService persistence = new PersistenceService(dir_);
        try
        {
            IntervalSnapshotRegistry macro = new IntervalSnapshotRegistry(DataStream.MACRO);
            ObjectNode snap = Json.newObject();
            snap.put("fetched_at", "2026-05-31T12:30:02Z");
            assertFalse("first put stores", macro.putIfAbsent(DAY, KEY, snap).isEmpty());
            assertTrue("second put for same interval is skipped",
                    macro.putIfAbsent(DAY, KEY, Json.newObject()).isEmpty());

            persistence.commit(macro.putIfAbsent(DAY, "12:40", snap));
            persistence.flush();

            IntervalSnapshotRegistry reloaded = new IntervalSnapshotRegistry(DataStream.MACRO);
            reloaded.hydrate(persistence.load(DataStream.MACRO, 3));
            assertEquals("2026-05-31T12:30:02Z", reloaded.get(DAY, "12:40").path("fetched_at").asText());
        }
        finally
        {
            persistence.shutdown();
        }
    }

    @Test
    public void ohlcBarsMergeDedupRouteByDayAndRoundTrip() throws Exception
    {
        PersistenceService persistence = new PersistenceService(dir_);
        try
        {
            OhlcRegistry ohlc = new OhlcRegistry();
            List<WriteUnit> writes = new ArrayList<>();
            writes.addAll(ohlc.merge(bars("2026-05-31T11:55:00Z")));
            writes.addAll(ohlc.merge(bars("2026-05-31T11:56:00Z")));
            writes.addAll(ohlc.merge(bars("2026-05-31T11:56:00Z"))); // same ts -> deduped, no growth
            writes.addAll(ohlc.merge(bars("2026-06-01T00:02:00Z")));  // next day -> routed to its own file

            assertEquals("dedup by ts within the day", 2,
                    ohlc.barsInRange("2026-05-31T00:00:00Z", "2026-05-31T23:59:00Z").size());
            assertEquals("the next-day bar routed to its own day file", 1,
                    ohlc.barsInRange("2026-06-01T00:00:00Z", "2026-06-01T23:59:00Z").size());
            assertEquals("latest ts spans days", "2026-06-01T00:02:00Z", ohlc.latestTs());

            persistence.commit(writes);
            persistence.flush();

            OhlcRegistry reloaded = new OhlcRegistry();
            reloaded.hydrate(persistence.load(DataStream.OHLC, 3));
            assertEquals("a cross-day range read survives the round-trip", 3,
                    reloaded.barsInRange("2026-05-31T00:00:00Z", "2026-06-01T23:59:00Z").size());
        }
        finally
        {
            persistence.shutdown();
        }
    }

    @Test
    public void multiStreamBatchCommitsAtomicallyAndRecovers() throws Exception
    {
        PersistenceService persistence = new PersistenceService(dir_);
        try
        {
            ArticleRegistry articles = new ArticleRegistry();
            IntervalSnapshotRegistry macro = new IntervalSnapshotRegistry(DataStream.MACRO);

            // One batch spanning two streams (article JSONL + macro JSON) commits together.
            List<WriteUnit> batch = new ArrayList<>();
            batch.addAll(articles.store(List.of(article("Fed cuts rates", "http://x/1", "2026-05-31T12:31:00Z"))));
            ObjectNode snap = Json.newObject();
            snap.put("fetched_at", "2026-05-31T12:30:02Z");
            batch.addAll(macro.putIfAbsent(DAY, KEY, snap));
            persistence.commit(batch);
            persistence.flush();

            assertTrue(Files.exists(dir_.resolve(DAY).resolve("articles_" + DAY + ".jsonl")));
            assertTrue(Files.exists(dir_.resolve(DAY).resolve("macro_context_" + DAY + ".json")));

            // Both streams recover from the committed files.
            ArticleRegistry recoveredArticles = new ArticleRegistry();
            recoveredArticles.hydrate(persistence.load(DataStream.ARTICLES, 3));
            assertEquals(1, recoveredArticles.forInterval(DAY, KEY, false, WINDOW).size());

            IntervalSnapshotRegistry recoveredMacro = new IntervalSnapshotRegistry(DataStream.MACRO);
            recoveredMacro.hydrate(persistence.load(DataStream.MACRO, 3));
            assertEquals("2026-05-31T12:30:02Z", recoveredMacro.get(DAY, KEY).path("fetched_at").asText());
        }
        finally
        {
            persistence.shutdown();
        }
    }

    @Test
    public void eventBusDeliversToSubscriber() throws Exception
    {
        EventBus bus = new EventBus();
        try
        {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> received = new AtomicReference<>();
            bus.subscribe(String.class, event ->
            {
                received.set(event);
                latch.countDown();
            });
            bus.publish("hello");
            assertTrue("event delivered within timeout", latch.await(2, TimeUnit.SECONDS));
            assertEquals("hello", received.get());
        }
        finally
        {
            bus.shutdown();
        }
    }

    @Test
    public void configParsesCollectorAndAnalyserSections()
    {
        // The collector section owns the source/feed lists and the shared window param;
        // the analyser section owns screening and delivery.
        String xml = "<FSSatellite>"
                + "<FSCollector analysisNewsWindow=\"10m\" dataDir=\"custom_data\">"
                + "<Sources><Source name=\"newsapi\" apiKey=\"ENV:NOPE\"/><Source name=\"rss\"/></Sources>"
                + "<RssFeeds><Feed name=\"CoinDesk\" url=\"http://cd\"/></RssFeeds>"
                + "</FSCollector>"
                + "<FSAnalyser screenerThreshold=\"6\" telegramChatId=\"645133217\"/>"
                + "</FSSatellite>";
        Config config = new Config(XMLData.valueOf(xml));

        // Shared structural param (lives under <FSCollector>).
        assertEquals(10, config.windowMinutes());
        // Collector-owned data directory (default "data" when the attribute is absent).
        assertEquals("custom_data", config.dataDir());
        assertEquals("data", new Config(XMLData.valueOf("<FSSatellite><FSCollector/></FSSatellite>")).dataDir());
        // Collector-owned lists.
        assertEquals(2, config.sources().size());
        assertEquals("newsapi", config.sources().get(0).name());
        assertEquals(1, config.rssFeeds().size());
        // Analyser-owned scalars.
        assertEquals(6, config.screenerThreshold());
        assertEquals("645133217", config.telegramChatId());
    }

    private static ObjectNode article(String title, String url, String publishedAt)
    {
        ObjectNode article = Json.newObject();
        article.put("title", title);
        article.put("url", url);
        article.put("publishedAt", publishedAt);
        article.put("_source", "rss");
        return article;
    }

    private static ArrayNode bars(String ts)
    {
        ObjectNode bar = Json.newObject();
        bar.put("ts", ts);
        bar.put("c", 73925.33);
        ArrayNode bars = Json.newArray();
        bars.add(bar);
        return bars;
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // best-effort temp cleanup
        }
    }
}
