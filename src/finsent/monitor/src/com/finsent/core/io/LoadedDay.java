package com.finsent.core.io;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Raw contents of one persisted day-file, as read back during recovery: the {@code day} string
 * and its {@code payload} (an {@code ObjectNode} for {@link DataStream.Format#JSON} streams, an
 * {@code ArrayNode} of the line objects for {@link DataStream.Format#JSONL}). The
 * {@link PersistenceService} produces these; a registry interprets them in {@code hydrate} to
 * rebuild its runtime state. This keeps the persistence layer domain-agnostic &mdash; it reads
 * bytes, the registry understands their meaning.
 */
public record LoadedDay(String day, JsonNode payload)
{
}
