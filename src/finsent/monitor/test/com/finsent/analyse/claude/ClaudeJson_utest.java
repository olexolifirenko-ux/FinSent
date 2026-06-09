package com.finsent.analyse.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

/**
 * Verifies {@link ClaudeJson} against the cleanup Python {@code analyse._parse_*_response} applies
 * before {@code json.loads}: code-fence stripping, trailing-comma repair, span isolation from
 * surrounding prose, and null on unparseable input.
 */
public class ClaudeJson_utest
{
    @Test
    public void extractsPlainObject()
    {
        ObjectNode node = ClaudeJson.extractObject("{\"direction\":\"bullish\",\"impact_tier\":\"high\"}");
        assertEquals("bullish", node.path("direction").asText());
        assertEquals("high", node.path("impact_tier").asText());
    }

    @Test
    public void stripsJsonCodeFenceAroundObject()
    {
        String fenced = "```json\n{\"direction\":\"bearish\"}\n```";
        assertEquals("bearish", ClaudeJson.extractObject(fenced).path("direction").asText());
    }

    @Test
    public void repairsTrailingCommasInObject()
    {
        ObjectNode node = ClaudeJson.extractObject("{\"a\":1,\"b\":2,}");
        assertEquals(1, node.path("a").asInt());
        assertEquals(2, node.path("b").asInt());
    }

    @Test
    public void isolatesObjectFromSurroundingProse()
    {
        ObjectNode node = ClaudeJson.extractObject("Here is the result: {\"impact_tier\":\"noise\"} -- done.");
        assertEquals("noise", node.path("impact_tier").asText());
    }

    @Test
    public void extractsScreenerArrayWithFenceAndTrailingComma()
    {
        String text = "```json\n[{\"i\":1,\"score\":7,\"reason\":\"x\"},{\"i\":2,\"score\":0,\"reason\":\"dup\"},]\n```";
        ArrayNode array = ClaudeJson.extractArray(text);
        assertEquals(2, array.size());
        assertEquals(7, array.get(0).path("score").asInt());
        assertEquals("dup", array.get(1).path("reason").asText());
    }

    @Test
    public void returnsNullWhenNoJsonPresent()
    {
        assertNull(ClaudeJson.extractObject("no json here at all"));
        assertNull(ClaudeJson.extractArray("still nothing"));
        assertNull(ClaudeJson.extractObject(null));
    }

    @Test
    public void objectExtractionIgnoresArrayPayload()
    {
        assertNull(ClaudeJson.extractObject("[1, 2, 3]"));
    }
}
