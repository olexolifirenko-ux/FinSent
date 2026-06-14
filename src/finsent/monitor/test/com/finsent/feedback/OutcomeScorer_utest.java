package com.finsent.feedback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.feedback.OutcomeScorer.ArticlePrediction;
import com.finsent.feedback.OutcomeScorer.Prediction;
import com.finsent.feedback.OutcomeScorer.PriceSource;

/**
 * Verifies {@link OutcomeScorer}: realized 1h/24h move computation against an injected price source,
 * the direction-correctness rules, and that immature / price-missing predictions are skipped.
 */
public class OutcomeScorer_utest
{
    private static final Instant WINDOW = Instant.parse("2026-06-08T13:20:00Z");

    @Test
    public void scoresDirectionAndRealizedMove()
    {
        PriceSource prices = stub(Map.of(
                WINDOW.plusSeconds(3600), 102.0,    // +1h: +2%
                WINDOW.plusSeconds(86400), 95.0));  // +24h: -5%

        List<ObjectNode> out = OutcomeScorer.score(List.of(pred("bullish", "high", "medium", 100.0)), now48h(), prices);

        assertEquals(1, out.size());
        ObjectNode outcome = out.get(0);
        assertEquals(2.0, outcome.path("outcome_1h_pct").asDouble(), 1e-9);
        assertEquals(-5.0, outcome.path("outcome_24h_pct").asDouble(), 1e-9);
        assertTrue("bullish + up move = correct", outcome.path("direction_correct").asBoolean());
        assertEquals("medium", outcome.path("confidence").asText());
    }

    @Test
    public void directionRules()
    {
        assertTrue(OutcomeScorer.directionCorrect("bullish", 0.5));
        assertFalse(OutcomeScorer.directionCorrect("bullish", -0.5));
        assertTrue(OutcomeScorer.directionCorrect("bearish", -0.5));
        assertFalse(OutcomeScorer.directionCorrect("bearish", 0.5));
        assertTrue("small move counts as neutral-correct", OutcomeScorer.directionCorrect("neutral", 0.02));
        assertFalse("big move makes a neutral call wrong", OutcomeScorer.directionCorrect("neutral", 0.5));
    }

    @Test
    public void immaturePredictionIsSkipped()
    {
        Instant now = WINDOW.plusSeconds(1800); // only 30 min -> 1h not matured
        assertEquals(0, OutcomeScorer.score(List.of(pred("bullish", "high", "high", 100.0)), now, stub(Map.of())).size());
    }

    @Test
    public void missingPriceIsSkipped()
    {
        assertEquals(0, OutcomeScorer.score(List.of(pred("bearish", "low", "low", 100.0)), now48h(), stub(Map.of())).size());
    }

    @Test
    public void maturePredictionWith1hButNot24hOmits24h()
    {
        PriceSource prices = stub(Map.of(WINDOW.plusSeconds(3600), 101.0));
        Instant now = WINDOW.plusSeconds(7200); // 2h: 1h matured, 24h not

        ObjectNode outcome = OutcomeScorer.score(List.of(pred("bullish", "high", "high", 100.0)), now, prices).get(0);

        assertEquals(1.0, outcome.path("outcome_1h_pct").asDouble(), 1e-9);
        assertTrue("24h not yet matured -> null", outcome.path("outcome_24h_pct").isNull());
    }

    @Test
    public void scenarioValidationRules()
    {
        assertEquals("front-run validated when move stays small", Boolean.TRUE,
                OutcomeScorer.scenarioValidated("front_run", "", 0.3));
        assertEquals("front-run invalid when it moves a lot", Boolean.FALSE,
                OutcomeScorer.scenarioValidated("front_run", "", 1.0));
        assertEquals(Boolean.TRUE, OutcomeScorer.scenarioValidated("fresh_bullish", "", 0.4));
        assertEquals(Boolean.FALSE, OutcomeScorer.scenarioValidated("fresh_bullish", "", -0.4));
        assertEquals(Boolean.TRUE, OutcomeScorer.scenarioValidated("fresh_bearish", "", -0.4));
        assertEquals("noise is always validated", Boolean.TRUE, OutcomeScorer.scenarioValidated("noise", "", 5.0));
        assertEquals("reversal of a rising pre-trend = price down", Boolean.TRUE,
                OutcomeScorer.scenarioValidated("reversal", "rising", -0.6));
        assertEquals(Boolean.FALSE, OutcomeScorer.scenarioValidated("reversal", "rising", 0.6));
        assertEquals(Boolean.TRUE, OutcomeScorer.scenarioValidated("reversal", "falling", 0.6));
        assertNull("reversal with no directional pre-trend is unknown",
                OutcomeScorer.scenarioValidated("reversal", "flat", 0.6));
        assertNull("unknown scenario is unknown", OutcomeScorer.scenarioValidated("mystery", "", 1.0));
    }

    @Test
    public void scoresArticleScenario()
    {
        PriceSource prices = stub(Map.of(WINDOW.plusSeconds(3600), 99.0)); // -1% at +1h
        ArticlePrediction article = new ArticlePrediction(7, WINDOW, 100.0, "fresh_bearish", "flat", "20260608");

        List<ObjectNode> out = OutcomeScorer.scoreArticles(List.of(article), now48h(), prices);

        assertEquals(1, out.size());
        assertEquals(7, out.get(0).path("article_id").asInt());
        assertEquals(-1.0, out.get(0).path("actual_1h_pct").asDouble(), 1e-9);
        assertTrue("bearish call + down move validated", out.get(0).path("scenario_validated").asBoolean());
    }

    private static Instant now48h()
    {
        return WINDOW.plusSeconds(48 * 3600);
    }

    private static Prediction pred(String dir, String tier, String conf, double base)
    {
        return new Prediction(WINDOW, base, dir, tier, conf, "20260608", "13:20", "news");
    }

    private static PriceSource stub(Map<Instant, Double> prices)
    {
        Map<Instant, Double> copy = new HashMap<>(prices);
        return copy::get;
    }
}
