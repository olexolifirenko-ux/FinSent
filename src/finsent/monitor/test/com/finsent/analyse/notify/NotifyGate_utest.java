package com.finsent.analyse.notify;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link NotifyGate} against Python {@code analyse._should_notify}: the non-neutral +
 * tier-threshold rule, the resonant-article freshness window (with injected {@code now}), and the
 * {@code skipAgeCheck} bypass.
 */
public class NotifyGate_utest
{
    private static final Instant NOW = Instant.parse("2026-06-04T08:00:00Z");
    private static final int AGE_MINUTES = 60;

    @Test
    public void nullPredRecordIsNotNotified()
    {
        assertFalse(NotifyGate.shouldNotify(null, List.of(), "high", "low", AGE_MINUTES, NOW, false));
    }

    @Test
    public void materialEventWithNeutralLeanStillNotifies()
    {
        // Monitor behaviour: a high-materiality event alerts even when the directional lean is unclear.
        ObjectNode pred = pred("neutral", "high");
        assertTrue(NotifyGate.shouldNotify(pred, List.of(fresh()), "high", "low", AGE_MINUTES, NOW, false));
    }

    @Test
    public void tierBelowMinimumIsNotNotified()
    {
        ObjectNode pred = pred("bullish", "low");
        assertFalse(NotifyGate.shouldNotify(pred, List.of(fresh()), "high", "low", AGE_MINUTES, NOW, false));
    }

    @Test
    public void nonNeutralTierMetWithFreshArticleNotifies()
    {
        ObjectNode pred = pred("bullish", "high");
        assertTrue(NotifyGate.shouldNotify(pred, List.of(stale(), fresh()), "high", "low", AGE_MINUTES, NOW, false));
    }

    @Test
    public void noFreshArticleFailsAgeGate()
    {
        ObjectNode pred = pred("bearish", "high");
        assertFalse(NotifyGate.shouldNotify(pred, List.of(stale()), "high", "low", AGE_MINUTES, NOW, false));
    }

    @Test
    public void skipAgeCheckBypassesFreshness()
    {
        ObjectNode pred = pred("bearish", "high");
        assertTrue(NotifyGate.shouldNotify(pred, List.of(stale()), "high", "low", AGE_MINUTES, NOW, true));
    }

    @Test
    public void confidenceBelowMinimumIsNotNotified()
    {
        ObjectNode pred = pred("bullish", "high");
        pred.put("confidence", "low");
        // Tier passes but conviction is below the configured floor -> suppressed.
        assertFalse(NotifyGate.shouldNotify(pred, List.of(fresh()), "high", "high", AGE_MINUTES, NOW, false));
    }

    @Test
    public void confidenceAtOrAboveMinimumNotifies()
    {
        ObjectNode pred = pred("bullish", "high");
        pred.put("confidence", "high");
        assertTrue(NotifyGate.shouldNotify(pred, List.of(fresh()), "high", "high", AGE_MINUTES, NOW, false));
    }

    private static ObjectNode pred(String direction, String tier)
    {
        ObjectNode pred = Json.newObject();
        pred.put("direction", direction);
        pred.put("impact_tier", tier);
        return pred;
    }

    /** Published 5 minutes before NOW -- inside the 60-minute window. */
    private static ObjectNode fresh()
    {
        return article("2026-06-04T07:55:00Z");
    }

    /** Published 3 hours before NOW -- outside the window. */
    private static ObjectNode stale()
    {
        return article("2026-06-04T05:00:00Z");
    }

    private static ObjectNode article(String publishedAt)
    {
        ObjectNode article = Json.newObject();
        article.put("publishedAt", publishedAt);
        return article;
    }
}
