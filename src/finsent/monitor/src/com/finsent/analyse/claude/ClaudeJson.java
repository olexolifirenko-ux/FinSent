package com.finsent.analyse.claude;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;

/**
 * Tolerant extraction of the JSON payload from a Claude completion (ports the cleanup Python
 * {@code analyse._parse_screener_response} / {@code _parse_claude_response} apply before
 * {@code json.loads}). Claude often wraps its answer in a {@code ```json} fence or emits trailing
 * commas; this strips the fence, isolates the first {@code {...}} / {@code [...]} span, repairs
 * trailing commas, and parses &mdash; returning {@code null} when nothing parseable is found so the
 * caller can apply its fallback.
 */
public final class ClaudeJson
{
    private static final Pattern OBJECT = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
    private static final Pattern ARRAY = Pattern.compile("\\[.*\\]", Pattern.DOTALL);
    private static final Pattern FENCE_OPEN = Pattern.compile("^```[a-zA-Z]*\\s*\\n?");
    private static final Pattern FENCE_CLOSE = Pattern.compile("\\n?```\\s*$");

    private ClaudeJson()
    {
    }

    /** First {@code {...}} object span in {@code text}, fence-stripped and comma-repaired, or null. */
    public static ObjectNode extractObject(String text)
    {
        ObjectNode result = null;
        JsonNode node = extract(text, OBJECT);
        if (node instanceof ObjectNode)
        {
            result = (ObjectNode) node;
        }
        return result;
    }

    /** First {@code [...]} array span in {@code text}, fence-stripped and comma-repaired, or null. */
    public static ArrayNode extractArray(String text)
    {
        ArrayNode result = null;
        JsonNode node = extract(text, ARRAY);
        if (node instanceof ArrayNode)
        {
            result = (ArrayNode) node;
        }
        return result;
    }

    /** Strip a leading/trailing Markdown code fence, matching Python's regex cleanup. */
    static String stripFences(String text)
    {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("```"))
        {
            cleaned = FENCE_OPEN.matcher(cleaned).replaceFirst("");
            cleaned = FENCE_CLOSE.matcher(cleaned).replaceFirst("");
        }
        return cleaned;
    }

    /** Repair trailing commas before a closing brace/bracket (e.g. {@code ,}} &rarr; {@code }}). */
    static String fixTrailingCommas(String raw)
    {
        return raw.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]");
    }

    private static JsonNode extract(String text, Pattern span)
    {
        JsonNode result = null;
        Matcher matcher = span.matcher(stripFences(text));
        if (matcher.find())
        {
            try
            {
                result = Json.parse(fixTrailingCommas(matcher.group()));
            }
            catch (JsonProcessingException malformed)
            {
                // Tolerated: unparseable span yields null so the caller applies its fallback.
                result = null;
            }
        }
        return result;
    }
}
