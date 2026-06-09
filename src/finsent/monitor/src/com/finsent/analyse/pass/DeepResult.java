package com.finsent.analyse.pass;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Outcome of the deep-analysis pass: the aggregate {@code prediction} object Claude returned
 * ({@code {direction, impact_tier, key_events, reasoning}}, with {@code articles} split out and any
 * Claude-supplied {@code macro_regime} stripped), and the per-article {@code articles} array
 * ({@code [{i, direction, reasoning}]}). {@code prediction} is {@code null} when the response could
 * not be parsed or lacked a {@code direction}, in which case {@code articles} is empty.
 */
public record DeepResult(ObjectNode prediction, ArrayNode articles)
{
}
