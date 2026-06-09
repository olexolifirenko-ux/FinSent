package com.finsent.collect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.finsent.collect.source.IArticleSource;
import com.finsent.core.Config;
import com.finsent.core.Json;
import com.finsent.util.xml.XMLData;

/**
 * Verifies {@link FSCollector#collect} (Python {@code collect.run_collect}) on the article pipeline
 * with stub sources and an injected {@code now} (no network; fetchers null so context steps are
 * skipped, exercised end-to-end later): {@code _source} tagging, stale and watermark filtering,
 * cross-source dedup, the atomic commit to disk, recovery on restart, and that the whole-window
 * {@link CollectionResult} is published to subscribers.
 */
public class FSCollector_utest
{
    private static final String DAY = "20260529"; // a Friday (UTC)
    private static final int WINDOW = 10;
    private static final Instant NOW = Instant.parse("2026-05-29T12:03:20Z");
    private static final String KEY = "12:00";

    private Path dir_;
    private Config config_;
    private final List<FSCollector> created_ = new ArrayList<>();

    @Before
    public void setUp() throws IOException
    {
        dir_ = Files.createTempDirectory("fs-collector-utest");
        config_ = new Config(XMLData.valueOf(
                "<FSSatellite><FSCollector analysisNewsWindow=\"10m\" articleMaxAge=\"48h\"/></FSSatellite>"));
    }

    @After
    public void tearDown() throws IOException
    {
        created_.forEach(FSCollector::shutdown);
        try (Stream<Path> paths = Files.walk(dir_))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(FSCollector_utest::deleteQuietly);
        }
    }

    @Test
    public void collectsFreshArticlesTaggedBySourceAndCommits()
    {
        FSCollector collector = collector(
                source("srcA", article("A1", "http://a/1", "2026-05-29T12:01:00Z")),
                source("srcB", article("B1", "http://b/1", "2026-05-29T12:02:00Z")));

        CollectionResult result = collector.collect(NOW);
        collector.flush();

        assertEquals(2, result.stored());
        assertEquals(DAY, result.day());
        assertEquals(KEY, result.intervalKey());
        assertEquals(2, result.windowArticles().size());
        assertEquals("srcA", result.windowArticles().get(0).path("_source").asText());
        assertEquals("srcB", result.windowArticles().get(1).path("_source").asText());
        assertTrue("cycle committed the article file", Files.exists(dir_.resolve("articles_" + DAY + ".jsonl")));
    }

    @Test
    public void dropsStaleArticles()
    {
        FSCollector collector = collector(source("srcA",
                article("fresh", "http://a/1", "2026-05-29T12:01:00Z"),
                article("ancient", "http://a/2", "2026-05-01T00:00:00Z"))); // > 48h before NOW

        CollectionResult result = collector.collect(NOW);

        assertEquals(1, result.stored());
        assertEquals("fresh", result.windowArticles().get(0).path("title").asText());
    }

    @Test
    public void dropsArticlesAtOrBelowWatermark()
    {
        MutableSource src = new MutableSource("srcA");
        FSCollector collector = collector(src);

        src.set(article("first", "http://a/1", "2026-05-29T12:01:00Z"));
        assertEquals(1, collector.collect(NOW).stored()); // sets srcA watermark to 12:01

        src.set(article("stale-wm", "http://a/2", "2026-05-29T12:00:30Z"));
        assertEquals(0, collector.collect(NOW).stored()); // at/below watermark -> dropped
    }

    @Test
    public void crossSourceDuplicatesAreDedupedByTheRegistry()
    {
        FSCollector collector = collector(
                source("srcA", article("dup", "http://x/1", "2026-05-29T12:01:00Z")),
                source("srcB", article("dup", "http://x/1", "2026-05-29T12:02:00Z")));

        CollectionResult result = collector.collect(NOW);

        assertEquals(1, result.windowArticles().size());
    }

    @Test
    public void recoversCommittedStateOnRestart()
    {
        FSCollector collector = collector(
                source("srcA", article("A1", "http://a/1", "2026-05-29T12:01:00Z")));
        collector.collect(NOW);
        collector.flush();

        FSCollector restarted = collector(); // no sources needed for recovery
        restarted.recover(3);
        assertEquals(1, restarted.articles().forInterval(DAY, KEY, false, WINDOW).size());
    }

    @Test
    public void forIntervalReturnsCopiesSoAnalysisCannotPolluteStoredArticles()
    {
        FSCollector collector = collector(
                source("srcA", article("A1", "http://a/1", "2026-05-29T12:01:00Z")));
        collector.collect(NOW);

        // The screener annotates window articles in place; that must not reach the stored object.
        collector.articles().forInterval(DAY, KEY, false, WINDOW).get(0).put("screener_score", 8);

        List<ObjectNode> reread = collector.articles().forInterval(DAY, KEY, false, WINDOW);
        assertFalse("stored article stays collector-only", reread.get(0).has("screener_score"));
    }

    @Test
    public void publishesWholeWindowResultToSubscribers() throws Exception
    {
        FSCollector collector = collector(
                source("srcA", article("A1", "http://a/1", "2026-05-29T12:01:00Z")));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CollectionResult> received = new AtomicReference<>();
        collector.addListener(result ->
        {
            received.set(result);
            latch.countDown();
        });

        collector.collect(NOW);

        assertTrue("listener notified within timeout", latch.await(2, TimeUnit.SECONDS));
        assertEquals(KEY, received.get().intervalKey());
        assertEquals(1, received.get().windowArticles().size());
    }

    @Test
    public void publishesUrgentResultWhenUrgentArticleInWindow() throws Exception
    {
        FSCollector collector = urgentCollector(
                source("rss", article("SEC charges exchange", "http://a/1", "2026-05-29T12:01:00Z")));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CollectionResult> received = new AtomicReference<>();
        collector.addListener(result ->
        {
            received.set(result);
            latch.countDown();
        });

        CollectionResult result = collector.collectUrgent(NOW);

        assertTrue("urgent result published", latch.await(2, TimeUnit.SECONDS));
        assertTrue(result.urgent());
        assertTrue("matching article flagged urgent",
                received.get().windowArticles().get(0).path("urgent_worthy").asBoolean());
    }

    @Test
    public void urgentLanePublishesFreshArticleWindowEvenWhenNotUrgentWorthy() throws Exception
    {
        // Every fresh article re-publishes its window; the urgent keyword only sets the urgent_worthy flag.
        FSCollector collector = urgentCollector(
                source("rss", article("Bitcoin trades sideways", "http://a/2", "2026-05-29T12:02:00Z")));
        AtomicReference<CollectionResult> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        collector.addListener(result -> { received.set(result); latch.countDown(); });

        collector.collectUrgent(NOW);

        assertTrue("a fresh article publishes its window even if not urgent-worthy", latch.await(2, TimeUnit.SECONDS));
        assertFalse("the article is not flagged urgent", received.get().windowArticles().get(0).has("urgent_worthy"));
    }

    @Test
    public void freshArticleInAPastWindowRepublishesThatWindow() throws Exception
    {
        // Article published in the 11:50 window, but the cycle runs at 12:03 (current window 12:00).
        // The article's own past window must still be published for (re)analysis -- the live-analysis fix.
        FSCollector collector = collector(
                source("srcA", article("Late headline", "http://a/late", "2026-05-29T11:52:00Z")));
        AtomicReference<CollectionResult> withArticle = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        collector.addListener(result ->
        {
            if (!result.windowArticles().isEmpty()) { withArticle.set(result); latch.countDown(); }
        });

        collector.collect(NOW);

        assertTrue("the late article's past window is (re)published", latch.await(2, TimeUnit.SECONDS));
        assertEquals("11:50", withArticle.get().intervalKey());
        assertEquals("Late headline", withArticle.get().windowArticles().get(0).path("title").asText());
    }

    @Test
    public void reDeliveredDuplicateDoesNotRepublishItsWindow() throws Exception
    {
        // The same past-window story arrives from two sources. It is stored once (advancing only one
        // source's watermark); the other source re-delivers it as "fresh" each cycle, but the dedup
        // (publish only newly-stored windows) must keep it from re-publishing its 11:50 window.
        FSCollector collector = collector(
                source("srcA", article("Same story", "http://x/1", "2026-05-29T11:51:00Z")),
                source("srcB", article("Same story", "http://x/1", "2026-05-29T11:51:00Z")));
        AtomicInteger windowPublishes = new AtomicInteger();
        collector.addListener(result -> { if (!result.windowArticles().isEmpty()) { windowPublishes.incrementAndGet(); } });

        collector.collect(NOW); // stored once -> the 11:50 window publishes once
        collector.collect(NOW); // re-delivered duplicate -> deduped, must NOT re-publish 11:50

        Thread.sleep(300);
        assertEquals("the re-delivered duplicate must not re-publish its window", 1, windowPublishes.get());
    }

    private FSCollector collector(IArticleSource... sources)
    {
        return build(List.of(sources), List.of());
    }

    private FSCollector urgentCollector(IArticleSource... urgentSources)
    {
        return build(List.of(), List.of(urgentSources));
    }

    private FSCollector build(List<IArticleSource> sources, List<IArticleSource> urgentSources)
    {
        FSCollector collector = new FSCollector(config_, dir_, sources, urgentSources, null, null, null, null);
        created_.add(collector);
        return collector;
    }

    private static IArticleSource source(String name, ObjectNode... articles)
    {
        MutableSource source = new MutableSource(name);
        source.set(articles);
        return source;
    }

    /** A stub source whose returned articles can be changed between cycles. */
    private static final class MutableSource implements IArticleSource
    {
        private final String name_;
        private List<ObjectNode> articles_ = List.of();

        MutableSource(String name)
        {
            name_ = name;
        }

        void set(ObjectNode... articles)
        {
            articles_ = List.of(articles);
        }

        @Override
        public String name()
        {
            return name_;
        }

        @Override
        public List<ObjectNode> fetch(Map<String, String> fromTsMap)
        {
            // Return fresh instances each fetch (as real sources do) so a re-delivered article has no
            // id until it is stored -- the collector relies on that to publish only newly-stored windows.
            List<ObjectNode> copies = new ArrayList<>();
            for (ObjectNode article : articles_)
            {
                copies.add(article.deepCopy());
            }
            return copies;
        }
    }

    private static ObjectNode article(String title, String url, String publishedAt)
    {
        ObjectNode article = Json.newObject();
        article.put("title", title);
        article.put("url", url);
        article.put("publishedAt", publishedAt);
        return article;
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
