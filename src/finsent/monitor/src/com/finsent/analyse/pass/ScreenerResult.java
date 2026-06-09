package com.finsent.analyse.pass;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Outcome of a completed screener pass: the {@code resonant} articles selected for deep analysis
 * (those whose {@code |screener_score|} meets the threshold) and the {@code screenerOut} array
 * recorded in the analysis file ({@code [{article_id, score, reason}]}). The pass also annotates each
 * input article in place with {@code screener_score} / {@code screener_reason}. A screen that cannot
 * be parsed never produces a result &mdash; it throws {@link IllegalStateException} to abort the
 * window.
 */
public record ScreenerResult(List<ObjectNode> resonant, ArrayNode screenerOut)
{
}
