package com.finsent.collect;

import java.time.Instant;

/**
 * One manual-consensus economic release from {@code econ_events.json} (BACKLOG #21). The
 * {@code consensus} is entered by hand (or a scheduled agent); the actual is fetched from BLS by
 * {@link EconActuals} per {@code series}/{@code kind} once {@code release} has passed. {@code unit},
 * {@code hotDirection} and the {@code inlineBand}/{@code highBand} thresholds feed the mechanical
 * {@code EconEventSignals}.
 */
public record EconEvent(String name, Instant release, double consensus, String unit, String hotDirection,
                        double inlineBand, double highBand, String series, String kind)
{
}
