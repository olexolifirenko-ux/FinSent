package com.finsent.analyse;

import java.time.Instant;

/**
 * Pre-move early-warning event: perpetual funding's MAGNITUDE is DRAINING into a still-flat tape -- the
 * leverage precursor that LEADS a FastMove break (on the 2026-06-24 unwind it bled ~80 min before the
 * price moved). {@code primedDirection} is the break it warns of: positive funding draining = long
 * conviction bleeding -> {@code bearish}-primed; negative funding draining = short conviction bleeding ->
 * {@code bullish}-primed.
 *
 * <p>Fragility, not a trigger: it can drain and NOT break, so it NEVER opens a position on its own. Its
 * job is lead time -- it arms the operator (and feeds lead-time telemetry) so a subsequent mechanical
 * FastMove fire in the primed direction can be acted on immediately rather than from a cold start.
 */
public record CompressionWarning(String day, String intervalKey, String primedDirection,
                                 double fundingDropPct, Double anchorPrice, Instant firedAt)
{
}
