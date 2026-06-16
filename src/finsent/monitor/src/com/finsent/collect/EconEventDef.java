package com.finsent.collect;

/**
 * The static definition of a scheduled economic release (#21): the stable fields that do not change
 * release to release -- the BLS {@code series} + {@code kind}, the {@code unit}, the {@code hotDirection}
 * polarity (BTC direction when actual &gt; consensus) and the {@code inlineBand}/{@code highBand} surprise
 * thresholds. Lives in {@code cfg/econ_definitions.json}, keyed by {@code name}; joined with a per-day
 * {@link EconRelease} (release time + consensus) into a full {@link EconEvent} when the scheduler arms it.
 */
public record EconEventDef(String name, String series, String kind, String unit, String hotDirection,
                           double inlineBand, double highBand)
{
}
