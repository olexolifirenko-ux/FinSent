package com.finsent.collect;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The outcome of one collection cycle and the payload the collector publishes to the analyser
 * (the Java replacement for Python's {@code collection_done} signal). It carries the interval the
 * cycle ran for, the number of new articles stored this cycle, and the FULL set of articles in
 * that news window &mdash; so the analyser can analyse the whole window (which may span the
 * boundary cycle plus several urgent polls) without re-querying the collector. {@code urgent} is
 * {@code true} when produced by the urgent poller.
 *
 * @param day            the {@code YYYYMMDD} day of the interval.
 * @param intervalKey    the {@code HH:MM} interval key.
 * @param stored         count of new articles stored this cycle (after filtering).
 * @param windowArticles all articles in the interval window, sorted by id.
 * @param urgent         whether this result came from the urgent poller.
 */
public record CollectionResult(String day, String intervalKey, int stored,
        List<ObjectNode> windowArticles, boolean urgent)
{
}
