package com.finsent.analyse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.finsent.analyse.claude.IClaudeClient;
import com.finsent.analyse.notify.EmailNotifier;
import com.finsent.analyse.notify.Notifier;
import com.finsent.analyse.notify.TelegramNotifier;
import com.finsent.collect.CollectionResult;
import com.finsent.collect.FSCollector;
import com.finsent.core.Config;
import com.finsent.core.Json;
import com.finsent.core.io.PersistenceService;
import com.finsent.core.registry.ArticleRegistry;
import com.finsent.util.xml.XMLData;

/**
 * Integration check of {@link FSAnalyser}'s synchronous {@code analyse(result, now)} entry point
 * with a stub Claude client and a temp-dir store (no network, empty collector context): a resonant
 * window screens, runs deep analysis, and persists the aggregate + per-article prediction with the
 * resonant ids; an all-below-threshold window stores a screener-only stub and never calls the deep
 * pass. Drives the analysis logic directly (bypassing the queue/worker), exactly as the worker does.
 */
public class FSAnalyser_utest
{
    private static final String DAY = "20260604";
    private static final String KEY = "08:00";
    private static final Instant NOW = Instant.parse("2026-06-04T08:05:00Z");

    private Path dir_;
    private FSCollector collector_;
    private AnalysisStore store_;
    private Notifier notifier_;
    private FSAnalyser analyser_;

    @Before
    public void setUp() throws IOException
    {
        dir_ = Files.createTempDirectory("fs-analyser-utest");
        Config config = config();
        collector_ = new FSCollector(config, dir_);
        store_ = new AnalysisStore(dir_);
        notifier_ = new Notifier(new TelegramNotifier("", "", "https://api.telegram.org"),
                new EmailNotifier(null, "", ""), "high", 60);
    }

    @After
    public void tearDown() throws IOException
    {
        if (analyser_ != null)
        {
            analyser_.uninitialize();
        }
        notifier_.shutdown();
        store_.shutdown();
        collector_.shutdown();
        try (Stream<Path> paths = Files.walk(dir_))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(FSAnalyser_utest::deleteQuietly);
        }
    }

    @Test
    public void resonantWindowRunsDeepAnalysisAndPersistsRecord() throws IOException
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "[{\"i\":1,\"score\":8,\"reason\":\"a\"},{\"i\":2,\"score\":8,\"reason\":\"b\"}]",
                "{\"direction\":\"bearish\",\"impact_tier\":\"high\",\"key_events\":[\"war\"],\"reasoning\":\"r\","
                        + "\"articles\":[{\"i\":1,\"direction\":\"bearish\",\"reasoning\":\"x\"},"
                        + "{\"i\":2,\"direction\":\"neutral\",\"reasoning\":\"y\"}]}");
        analyser(client).analyse(result(article(1, "War breaks out"), article(2, "Sanctions imposed")), NOW);

        ObjectNode interval = store_.get(DAY, KEY);
        ObjectNode prediction = (ObjectNode) interval.path("prediction_record");
        assertEquals("bearish", prediction.path("direction").asText());
        assertEquals("high", prediction.path("impact_tier").asText());
        assertTrue(prediction.path("claude_available").asBoolean());
        assertEquals(2, interval.path("resonant_article_ids").size());
        assertEquals(2, prediction.path("articles").size());
        assertEquals(1, prediction.path("articles").get(0).path("id").asInt());
        assertEquals("bearish", prediction.path("articles").get(0).path("direction").asText());
        assertEquals("fresh_bearish", prediction.path("articles").get(0).path("scenario").asText()); // no OHLC -> fresh
        assertTrue(store_.hasResonant(DAY, KEY));
        assertEquals("screener + deep = 2 Claude calls", 2, client.callCount());
    }

    @Test
    public void resonantWindowPublishesAnalysisReadyOnTheBus() throws Exception
    {
        AtomicReference<AnalysisReady> received = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        collector_.subscribe(AnalysisReady.class, signal ->
        {
            received.set(signal);
            delivered.countDown();
        });

        StubClaudeClient client = new StubClaudeClient().enqueue(
                "[{\"i\":1,\"score\":8,\"reason\":\"a\"}]",
                "{\"direction\":\"bearish\",\"impact_tier\":\"high\",\"key_events\":[\"war\"],\"reasoning\":\"r\","
                        + "\"articles\":[{\"i\":1,\"direction\":\"bearish\",\"reasoning\":\"x\"}]}");
        analyser(client).analyse(result(article(1, "War breaks out")), NOW);

        assertTrue("AnalysisReady delivered on the bus", delivered.await(2, TimeUnit.SECONDS));
        AnalysisReady signal = received.get();
        assertEquals("news", signal.source());
        assertEquals("bearish", signal.direction());
        assertEquals("high", signal.impactTier());
        assertEquals(DAY, signal.day());
        assertEquals(KEY, signal.intervalKey());
    }

    @Test
    public void belowThresholdWindowStoresStubWithoutDeepAnalysis() throws IOException
    {
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "[{\"i\":1,\"score\":2,\"reason\":\"a\"},{\"i\":2,\"score\":3,\"reason\":\"b\"}]");
        analyser(client).analyse(result(article(1, "Minor update"), article(2, "Opinion piece")), NOW);

        ObjectNode interval = store_.get(DAY, KEY);
        assertTrue("screener-only stub has null prediction", interval.path("prediction_record").isNull());
        assertEquals(2, interval.path("article_ids").size());
        assertFalse(store_.hasResonant(DAY, KEY));
        assertEquals("screener only, deep never called", 1, client.callCount());
    }

    @Test
    public void livePathSkipsDeepReRunUntilTheResonantSetChanges() throws IOException
    {
        String deepOne = "{\"direction\":\"bearish\",\"impact_tier\":\"high\",\"key_events\":[\"war\"],"
                + "\"reasoning\":\"r\",\"articles\":[{\"i\":1,\"direction\":\"bearish\",\"reasoning\":\"x\"}]}";
        StubClaudeClient client = new StubClaudeClient().enqueue("[{\"i\":1,\"score\":8,\"reason\":\"a\"}]", deepOne);
        FSAnalyser analyser = analyser(client);
        analyser.analyse(result(article(1, "War breaks out")), NOW);
        assertEquals("first pass: screener + deep", 2, client.callCount());

        // A non-resonant article trickles into the window; the resonant set {1} is unchanged, so the
        // live path skips the deep re-run and only the screener runs.
        client.enqueue("[{\"i\":1,\"score\":8,\"reason\":\"a\"},{\"i\":2,\"score\":0,\"reason\":\"noise\"}]");
        analyser.analyse(result(article(1, "War breaks out"), article(2, "Market recap")), NOW);
        assertEquals("unchanged resonant set: screener only, deep skipped", 3, client.callCount());

        // A genuinely new resonant article appears; the resonant set changes ({1} -> {1,3}) so deep re-runs.
        client.enqueue("[{\"i\":1,\"score\":8,\"reason\":\"a\"},{\"i\":2,\"score\":0,\"reason\":\"noise\"},"
                        + "{\"i\":3,\"score\":8,\"reason\":\"c\"}]",
                "{\"direction\":\"bearish\",\"impact_tier\":\"high\",\"key_events\":[\"war\"],\"reasoning\":\"r\","
                        + "\"articles\":[{\"i\":1,\"direction\":\"bearish\",\"reasoning\":\"x\"},"
                        + "{\"i\":2,\"direction\":\"bearish\",\"reasoning\":\"z\"}]}");
        analyser.analyse(result(article(1, "War breaks out"), article(2, "Market recap"),
                article(3, "Sanctions imposed")), NOW);
        assertEquals("changed resonant set: screener + deep re-ran", 5, client.callCount());
    }

    @Test
    public void screenerFailureAbortsWithoutDeepOrRecord() throws IOException
    {
        StubClaudeClient client = new StubClaudeClient(); // empty queue -> every screener call throws
        FSAnalyser analyser = analyser(client);

        // An unparseable screener response is a critical error: the cycle aborts (throws) deliberately,
        // rather than running the deep pass on fabricated scores or persisting a half-formed record.
        assertThrows(IllegalStateException.class,
                () -> analyser.analyse(result(article(1, "War breaks out")), NOW));

        assertTrue("no record is written when the screener aborts", store_.get(DAY, KEY).isEmpty());
        assertFalse(store_.hasResonant(DAY, KEY));
        assertEquals("two screener attempts, deep never called", 2, client.callCount());
    }

    @Test
    public void reanalyseLazilyRecoversTheDayFromDiskAndRecordsResult() throws IOException
    {
        // Seed the day's articles on disk only -- the fresh collector_ has NOT recovered them.
        PersistenceService seed = new PersistenceService(dir_);
        seed.commit(new ArticleRegistry().store(List.of(article(1, "War breaks out"), article(2, "Sanctions imposed"))));
        seed.flush();
        seed.shutdown();
        assertFalse("day not resident before re-analysis", collector_.articles().isResident(DAY));

        StubClaudeClient client = new StubClaudeClient().enqueue(
                "[{\"i\":1,\"score\":8,\"reason\":\"a\"},{\"i\":2,\"score\":8,\"reason\":\"b\"}]",
                "{\"direction\":\"bearish\",\"impact_tier\":\"high\",\"key_events\":[\"war\"],\"reasoning\":\"r\","
                        + "\"articles\":[{\"i\":1,\"direction\":\"bearish\",\"reasoning\":\"x\"},"
                        + "{\"i\":2,\"direction\":\"neutral\",\"reasoning\":\"y\"}]}");

        String summary = analyser(client).reanalyse(DAY, KEY);

        assertTrue("day lazily recovered from disk", collector_.articles().isResident(DAY));
        assertEquals("bearish / high, 2 resonant.", summary);
        ObjectNode interval = store_.get(DAY, KEY);
        assertEquals("bearish", interval.path("prediction_record").path("direction").asText());
        assertEquals(2, interval.path("resonant_article_ids").size());
    }

    @Test
    public void econSurpriseRunsDeepAndRecordsEconAlert() throws IOException
    {
        seedEcon("CPI MoM", 0.3, 0.6); // surprise +0.3 > high_band -> high bearish prior
        seedPrice(60500.0);
        StubClaudeClient client = new StubClaudeClient().enqueue(
                "{\"direction\":\"bearish\",\"impact_tier\":\"high\","
                        + "\"key_events\":[\"CPI hot\"],\"reasoning\":\"hot print, risk-off\"}");
        analyser(client).analyseEcon(DAY, KEY, "CPI MoM", NOW, true);

        ObjectNode econAlert = (ObjectNode) store_.get(DAY, KEY).path("econ_alert");
        assertEquals("bearish", econAlert.path("direction").asText());
        assertEquals("high", econAlert.path("impact_tier").asText());
        assertTrue(econAlert.path("claude_available").asBoolean());
        assertEquals("bearish", econAlert.path("mechanical_direction").asText());
        assertEquals("high", econAlert.path("mechanical_tier").asText());
        assertEquals(60500.0, econAlert.path("btc_at_prediction").asDouble(), 1e-9); // #6 scoring anchor
        assertEquals("econ runs only the deep pass (no screener)", 1, client.callCount());
    }

    @Test
    public void econInlinePrintRecordsMechanicalOnlyWithoutDeep() throws IOException
    {
        seedEcon("CPI MoM", 0.3, 0.35); // surprise +0.05 <= inline_band -> in line
        StubClaudeClient client = new StubClaudeClient(); // empty queue -> the deep pass must NOT be called
        analyser(client).analyseEcon(DAY, KEY, "CPI MoM", NOW, true);

        ObjectNode econAlert = (ObjectNode) store_.get(DAY, KEY).path("econ_alert");
        assertEquals("neutral", econAlert.path("direction").asText());
        assertEquals("noise", econAlert.path("impact_tier").asText());
        assertFalse(econAlert.path("claude_available").asBoolean());
        assertEquals("in-line print never calls the deep pass", 0, client.callCount());
    }

    /** Seed a resolved scheduled-release record (the collector's raw econ_actuals shape) in memory. */
    private void seedEcon(String name, double consensus, double actual)
    {
        ObjectNode resolved = Json.newObject();
        resolved.put("event", name);
        resolved.put("release", "2026-06-04T08:00:00Z");
        resolved.put("consensus", consensus);
        resolved.put("actual", actual);
        resolved.put("unit", "%");
        resolved.put("hot_direction", "bearish");
        resolved.put("inline_band", 0.1);
        resolved.put("high_band", 0.2);
        collector_.econ().store(DAY, name, resolved);
    }

    /** Seed the window's price-context snapshot (the btc_at_prediction anchor source for #6 scoring). */
    private void seedPrice(double btcPrice)
    {
        ObjectNode snap = Json.newObject();
        snap.put("btc_price", btcPrice);
        collector_.price().putIfAbsent(DAY, KEY, snap);
    }

    @Test
    public void planBackfillFindsWindowsMissingAnalysis() throws IOException
    {
        // Two windows with news on disk (08:00, 08:10); only 08:00 has a stored analysis record.
        PersistenceService seed = new PersistenceService(dir_);
        seed.commit(new ArticleRegistry().store(List.of(
                article(1, "War breaks out", "2026-06-04T08:01:00Z"),
                article(2, "Major quake", "2026-06-04T08:11:00Z"))));
        seed.flush();
        seed.shutdown();
        ObjectNode analysed = Json.newObject();
        analysed.put("analyzed_at", "2026-06-04T08:00:30Z");
        store_.record(DAY, "08:00", analysed);

        FSAnalyser fsAnalyser = analyser(new StubClaudeClient());

        List<Intervals.DayKey> missing = fsAnalyser.planBackfill(DAY, "08:00", DAY, "08:10", false).windows();
        assertEquals(1, missing.size());
        assertEquals("08:10", missing.get(0).key());

        List<Intervals.DayKey> all = fsAnalyser.planBackfill(DAY, "08:00", DAY, "08:10", true).windows();
        assertEquals("force re-includes the already-analysed window", 2, all.size());
    }

    @Test
    public void describeReturnsStoredRecordOrAbsence() throws IOException
    {
        ObjectNode prediction = Json.newObject();
        prediction.put("direction", "bearish");
        prediction.put("impact_tier", "high");
        prediction.put("macro_regime", "risk_off");
        prediction.put("reasoning", "Escalation.");
        ObjectNode interval = Json.newObject();
        interval.put("analyzed_at", "2026-06-04T08:00:30Z");
        interval.set("article_ids", Json.newArray());
        interval.set("resonant_article_ids", Json.newArray());
        interval.set("prediction_record", prediction);
        store_.record(DAY, KEY, interval);

        FSAnalyser fsAnalyser = analyser(new StubClaudeClient());
        String shown = fsAnalyser.describe(DAY, KEY);
        assertTrue(shown.contains("bearish / high"));
        assertTrue(shown.contains("Escalation."));
        assertTrue(fsAnalyser.describe(DAY, "09:00").startsWith("No analysis record"));
    }

    private FSAnalyser analyser(IClaudeClient client) throws IOException
    {
        analyser_ = new FSAnalyser(collector_, store_, client, notifier_, config(), promptsDir(), true);
        return analyser_;
    }

    private File promptsDir() throws IOException
    {
        Path prompts = dir_.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("screener.txt"), "{articles}", StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("deep_analysis.txt"), "STATIC DEEP INSTRUCTIONS", StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("deep_analysis_dynamic.txt"), "{article_count}|{market_signals}|{articles}",
                StandardCharsets.UTF_8);
        Files.writeString(prompts.resolve("econ_analysis.txt"), "{catalyst}|{market_signals}", StandardCharsets.UTF_8);
        return prompts.toFile();
    }

    private static Config config()
    {
        return new Config(XMLData.valueOf("<FSSatellite>"
                + "<FSCollector analysisNewsWindow=\"10m\" ohlcImpactWindow=\"30m\" optionsEnabled=\"false\"/>"
                + "<FSAnalyser screenerThreshold=\"6\" notifyMinImpactTier=\"high\" newsAgeToNotify=\"1h\"/>"
                + "</FSSatellite>"));
    }

    private static CollectionResult result(ObjectNode... articles)
    {
        return new CollectionResult(DAY, KEY, articles.length, List.of(articles), false);
    }

    private static ObjectNode article(int id, String title)
    {
        return article(id, title, "2026-06-04T08:01:00Z"); // floors to KEY 08:00
    }

    private static ObjectNode article(int id, String title, String publishedAt)
    {
        ObjectNode source = Json.newObject();
        source.put("name", "Reuters");
        ObjectNode article = Json.newObject();
        article.put("id", id);
        article.set("source", source);
        article.put("publishedAt", publishedAt);
        article.put("title", title);
        article.put("description", "");
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

    /** Returns queued responses in order (screener array, then deep object); records call count. */
    private static final class StubClaudeClient implements IClaudeClient
    {
        private final Deque<String> responses_ = new ArrayDeque<>();
        private int calls_ = 0;

        private StubClaudeClient enqueue(String... responses)
        {
            for (String response : responses)
            {
                responses_.addLast(response);
            }
            return this;
        }

        @Override
        public String complete(String model, String prompt, int maxTokens) throws IOException
        {
            calls_++;
            String response = responses_.pollFirst();
            if (response == null)
            {
                throw new IOException("stub: no response queued");
            }
            return response;
        }

        private int callCount()
        {
            return calls_;
        }
    }
}
