package com.finsent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON facility for the pipeline. Wraps a shared Jackson {@link ObjectMapper} and
 * ports the I/O conventions of the Python {@code shared.py}/{@code collect.py}:
 * data is held as {@link JsonNode}/{@link ObjectNode} (not POJOs) so the on-disk
 * shapes round-trip with exact field parity, and every file write is atomic
 * (write a sibling temp file, then rename) mirroring Python's {@code os.replace}.
 */
public final class Json
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json()
    {
    }

    /** A fresh empty JSON object node. */
    public static ObjectNode newObject()
    {
        return MAPPER.createObjectNode();
    }

    /** A fresh empty JSON array node. */
    public static ArrayNode newArray()
    {
        return MAPPER.createArrayNode();
    }

    /** Parse a JSON string into a node tree. */
    public static JsonNode parse(String text) throws JsonProcessingException
    {
        return MAPPER.readTree(text);
    }

    /** Compact single-line serialization. */
    public static String toCompactString(JsonNode node) throws JsonProcessingException
    {
        return MAPPER.writeValueAsString(node);
    }

    /**
     * Read a JSON file expected to contain an object. Returns an empty object when the
     * file is missing or unreadable/corrupt, matching Python {@code _read_json_dict}.
     */
    public static ObjectNode readObjectOrEmpty(Path path)
    {
        ObjectNode result = newObject();
        if (Files.isRegularFile(path))
        {
            try
            {
                JsonNode tree = MAPPER.readTree(path.toFile());
                if (tree instanceof ObjectNode)
                {
                    result = (ObjectNode) tree;
                }
            }
            catch (IOException corruptOrUnreadable)
            {
                result = newObject();
            }
        }
        return result;
    }

    /**
     * Read a JSONL file into a list of object nodes, tolerating blank and malformed
     * lines (they are skipped), matching Python {@code _read_day_file}.
     */
    public static List<ObjectNode> readJsonl(Path path) throws IOException
    {
        List<ObjectNode> records = new ArrayList<>();
        if (Files.isRegularFile(path))
        {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8))
            {
                String line = raw.trim();
                if (!line.isEmpty())
                {
                    addParsedLine(records, line);
                }
            }
        }
        return records;
    }

    private static void addParsedLine(List<ObjectNode> records, String line)
    {
        try
        {
            JsonNode node = MAPPER.readTree(line);
            if (node instanceof ObjectNode)
            {
                records.add((ObjectNode) node);
            }
        }
        catch (JsonProcessingException badLine)
        {
            // Tolerated: skip malformed lines, as the Python reader does.
        }
    }

    /**
     * Render a node as pretty-printed JSON bytes. Rendering is split from file writing so the
     * {@link com.finsent.core.io.PersistenceService} owns the atomic, batched commit to disk.
     */
    public static byte[] toPrettyBytes(JsonNode node) throws IOException
    {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
    }

    /** Render an array node as JSONL bytes (one compact element per line). */
    public static byte[] toJsonlBytes(JsonNode arrayNode) throws IOException
    {
        StringBuilder buffer = new StringBuilder();
        for (JsonNode element : arrayNode)
        {
            buffer.append(MAPPER.writeValueAsString(element)).append('\n');
        }
        return buffer.toString().getBytes(StandardCharsets.UTF_8);
    }
}
