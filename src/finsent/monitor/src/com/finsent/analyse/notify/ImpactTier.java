package com.finsent.analyse.notify;

import java.util.Map;

/**
 * Ordinal ranking of Claude's {@code impact_tier} labels (ports Python {@code _IMPACT_TIER_ORDER}).
 * Used by the notification gate and the macro-alert path to compare a prediction's tier against the
 * configured minimum.
 */
public final class ImpactTier
{
    private static final Map<String, Integer> ORDER = Map.of("noise", 0, "low", 1, "high", 2);

    private ImpactTier()
    {
    }

    /** Rank of {@code tier}, or {@code defaultValue} for an unknown/null label. */
    public static int order(String tier, int defaultValue)
    {
        Integer rank = tier == null ? null : ORDER.get(tier);
        return rank == null ? defaultValue : rank;
    }
}
