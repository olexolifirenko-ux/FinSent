package com.finsent.collect;

import java.time.Instant;

/**
 * One scheduled release of an economic event (#21): the volatile {@code release} time + {@code consensus}
 * forecast that change each month, entered by hand into the per-day schedule ({@code data/<date>/
 * econ_schedule_<date>.json}). Joined with its static {@link EconEventDef} (by {@code name}) into a full
 * {@link EconEvent} when the scheduler arms it.
 */
public record EconRelease(String name, Instant release, double consensus)
{
}
