package com.finsent.analyse.notify;

import java.util.Map;

/**
 * Ordinal ranking of Claude's {@code confidence} labels (conviction in the directional call, distinct
 * from {@code impact_tier}'s magnitude). Used by the notification gate to compare a prediction's
 * conviction against the configured minimum. Mirrors {@link ImpactTier}.
 */
public final class Confidence
{
    private static final Map<String, Integer> ORDER = Map.of("low", 0, "medium", 1, "high", 2);

    private Confidence()
    {
    }

    /** Rank of {@code confidence}, or {@code defaultValue} for an unknown/null label. */
    public static int order(String confidence, int defaultValue)
    {
        Integer rank = confidence == null ? null : ORDER.get(confidence);
        return rank == null ? defaultValue : rank;
    }
}
