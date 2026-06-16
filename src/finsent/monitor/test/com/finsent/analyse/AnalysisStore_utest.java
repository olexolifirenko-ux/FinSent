package com.finsent.analyse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.finsent.core.Json;

/**
 * Round-trip verification of {@link AnalysisStore}: a recorded window survives commit + recover with
 * exact field parity, an interval is overwritten on re-analysis, {@code resonant_article_ids} drives
 * {@link AnalysisStore#hasResonant}, and a standalone macro-alert merges into a skeleton (or an
 * existing) interval.
 */
public class AnalysisStore_utest
{
    private static final String DAY = "20260604";
    private static final String KEY = "08:00";
    private static final String AT = "2026-06-04T08:00:00Z";

    private Path dir_;

    @Before
    public void setUp() throws IOException
    {
        dir_ = Files.createTempDirectory("fs-analysis-utest");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> paths = Files.walk(dir_))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(AnalysisStore_utest::deleteQuietly);
        }
    }

    @Test
    public void recordPersistsAndRecovers() throws Exception
    {
        AnalysisStore store = new AnalysisStore(dir_);
        try
        {
            store.record(DAY, KEY, record("bullish", "high", 7, 11));
            store.flush();
            assertTrue(Files.exists(dir_.resolve(DAY).resolve("analysis_" + DAY + ".json")));
        }
        finally
        {
            store.shutdown();
        }

        AnalysisStore recovered = new AnalysisStore(dir_);
        try
        {
            recovered.recover(3);
            ObjectNode interval = recovered.get(DAY, KEY);
            assertEquals("bullish", interval.path("prediction_record").path("direction").asText());
            assertEquals("high", interval.path("prediction_record").path("impact_tier").asText());
            assertEquals(2, interval.path("resonant_article_ids").size());
            assertEquals(AT, interval.path("analyzed_at").asText());
            assertTrue("resonant flag survives recovery", recovered.hasResonant(DAY, KEY));
        }
        finally
        {
            recovered.shutdown();
        }
    }

    @Test
    public void recoverDayLazilyLoadsAnUnrecoveredDay() throws Exception
    {
        AnalysisStore store = new AnalysisStore(dir_);
        try
        {
            store.record(DAY, KEY, record("bullish", "high", 7, 11));
            store.flush();
        }
        finally
        {
            store.shutdown();
        }

        // A fresh store that did NOT recover the day sees nothing until lazily loaded.
        AnalysisStore fresh = new AnalysisStore(dir_);
        try
        {
            assertTrue("not recovered yet", fresh.get(DAY, KEY).isEmpty());
            fresh.recoverDay(DAY);
            assertEquals("bullish", fresh.get(DAY, KEY).path("prediction_record").path("direction").asText());
        }
        finally
        {
            fresh.shutdown();
        }
    }

    @Test
    public void recordingOneWindowKeepsOtherWindowsOfAnUnrecoveredDay() throws Exception
    {
        // The data-loss regression: a day written earlier, then a fresh store (simulating a restart where
        // the day is older than the recovery lookback) re-analyses ONE window. Because record() rewrites
        // the whole analysis_<day>.json, the day must be hydrated first or the other windows are lost.
        AnalysisStore first = new AnalysisStore(dir_);
        try
        {
            first.record(DAY, "12:00", record("bullish", "high", 7));
            first.record(DAY, "12:10", record("bearish", "low", 3));
            first.flush();
        }
        finally
        {
            first.shutdown();
        }

        AnalysisStore reanalysis = new AnalysisStore(dir_);
        try
        {
            reanalysis.recoverDay(DAY); // the fix: hydrate before re-recording a single window
            reanalysis.record(DAY, "12:20", record("neutral", "noise"));
            reanalysis.flush();
        }
        finally
        {
            reanalysis.shutdown();
        }

        AnalysisStore verify = new AnalysisStore(dir_);
        try
        {
            verify.recover(30);
            assertEquals("re-analysed window stored", "neutral",
                    verify.get(DAY, "12:20").path("prediction_record").path("direction").asText());
            assertEquals("earlier window preserved", "bullish",
                    verify.get(DAY, "12:00").path("prediction_record").path("direction").asText());
            assertEquals("earlier window preserved", "bearish",
                    verify.get(DAY, "12:10").path("prediction_record").path("direction").asText());
        }
        finally
        {
            verify.shutdown();
        }
    }

    @Test
    public void reAnalysisOverwritesInterval()
    {
        AnalysisStore store = new AnalysisStore(dir_);
        try
        {
            store.record(DAY, KEY, record("bullish", "high", 7, 11));
            store.record(DAY, KEY, record("bearish", "low", 3));
            ObjectNode interval = store.get(DAY, KEY);
            assertEquals("bearish", interval.path("prediction_record").path("direction").asText());
            assertEquals(1, interval.path("resonant_article_ids").size());
        }
        finally
        {
            store.shutdown();
        }
    }

    @Test
    public void hasResonantReflectsArticleIds()
    {
        AnalysisStore store = new AnalysisStore(dir_);
        try
        {
            assertFalse("absent interval has no resonant", store.hasResonant(DAY, KEY));
            store.record(DAY, "07:50", record("neutral", "noise"));
            assertFalse("empty resonant list reads false", store.hasResonant(DAY, "07:50"));
            store.record(DAY, KEY, record("bullish", "high", 7));
            assertTrue(store.hasResonant(DAY, KEY));
        }
        finally
        {
            store.shutdown();
        }
    }

    @Test
    public void macroAlertCreatesSkeletonThenSurvivesRecovery() throws Exception
    {
        AnalysisStore store = new AnalysisStore(dir_);
        try
        {
            store.recordMacroAlert(DAY, KEY, macroAlert("bearish"));
            store.flush();
            ObjectNode interval = store.get(DAY, KEY);
            assertEquals(0, interval.path("article_ids").size());
            assertTrue("skeleton prediction_record is null", interval.path("prediction_record").isNull());
            assertEquals("bearish", interval.path("macro_alert").path("direction").asText());
            assertEquals(AT, interval.path("analyzed_at").asText());
        }
        finally
        {
            store.shutdown();
        }

        AnalysisStore recovered = new AnalysisStore(dir_);
        try
        {
            recovered.recover(3);
            assertEquals("bearish", recovered.get(DAY, KEY).path("macro_alert").path("direction").asText());
        }
        finally
        {
            recovered.shutdown();
        }
    }

    @Test
    public void macroAlertMergesIntoExistingInterval()
    {
        AnalysisStore store = new AnalysisStore(dir_);
        try
        {
            store.record(DAY, KEY, record("bullish", "high", 7));
            store.recordMacroAlert(DAY, KEY, macroAlert("bearish"));
            ObjectNode interval = store.get(DAY, KEY);
            assertEquals("prediction preserved", "bullish",
                    interval.path("prediction_record").path("direction").asText());
            assertEquals("macro alert attached", "bearish",
                    interval.path("macro_alert").path("direction").asText());
        }
        finally
        {
            store.shutdown();
        }
    }

    private static ObjectNode record(String direction, String tier, int... resonantIds)
    {
        ObjectNode prediction = Json.newObject();
        prediction.put("direction", direction);
        prediction.put("impact_tier", tier);

        ArrayNode resonant = Json.newArray();
        ArrayNode articleIds = Json.newArray();
        for (int id : resonantIds)
        {
            resonant.add(id);
            articleIds.add(id);
        }

        ObjectNode interval = Json.newObject();
        interval.put("analyzed_at", AT);
        interval.set("article_ids", articleIds);
        interval.set("screener", Json.newArray());
        interval.set("prediction_record", prediction);
        interval.set("resonant_article_ids", resonant);
        return interval;
    }

    private static ObjectNode macroAlert(String direction)
    {
        ObjectNode alert = Json.newObject();
        alert.put("analyzed_at", AT);
        alert.put("source", "macro_mechanical");
        alert.put("direction", direction);
        alert.put("impact_tier", "high");
        return alert;
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
