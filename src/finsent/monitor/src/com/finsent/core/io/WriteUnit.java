package com.finsent.core.io;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One file's worth of data to persist: the full current state of a {@code stream}'s {@code day}
 * file as an immutable payload (an {@code ObjectNode} for {@link DataStream.Format#JSON} streams,
 * an {@code ArrayNode} for {@link DataStream.Format#JSONL}). Registries render their changed days
 * into write-units; the collector collects a cycle's units and the {@link PersistenceService}
 * commits them as one atomic batch. The payload must be a private copy &mdash; it is serialized
 * later on the persistence thread.
 */
public record WriteUnit(DataStream stream, String day, JsonNode payload)
{
}
