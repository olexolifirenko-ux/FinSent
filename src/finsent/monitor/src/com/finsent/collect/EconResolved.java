package com.finsent.collect;

/**
 * Published on the collector's event bus when a scheduled economic release (#21) is <b>freshly</b>
 * resolved (its awaited print has landed): the analyser reads the window's market context for
 * {@code (day, intervalKey)} -- the release-time window -- and runs the article-less econ analysis for
 * {@code eventName}. A separate event type from {@link CollectionResult} so the analyser triggers econ
 * analysis off the release, not off a news window.
 *
 * @param day         the {@code YYYYMMDD} day of the release.
 * @param intervalKey the {@code HH:MM} window of the release time.
 * @param eventName   the resolved event's name (its key in the {@code econ_actuals} record).
 */
public record EconResolved(String day, String intervalKey, String eventName)
{
}
