package com.finsent.collect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import org.junit.Test;

/**
 * Verifies {@link EconScheduleConfig}: parses the {@code {name, release, consensus}} rows, skips a row
 * with no name or an unparseable release time, and yields an empty list for an absent file.
 */
public class EconScheduleConfig_utest
{
    @Test
    public void parsesReleasesAndSkipsMalformedRows() throws Exception
    {
        File file = temp("["
                + "{\"name\":\"CPI MoM\",\"release\":\"2026-06-11T12:30:00Z\",\"consensus\":0.3},"
                + "{\"name\":\"\",\"release\":\"2026-06-11T12:30:00Z\",\"consensus\":0.1},"   // no name -> skipped
                + "{\"name\":\"Bad\",\"release\":\"not-a-date\",\"consensus\":1.0}"           // bad release -> skipped
                + "]");

        List<EconRelease> releases = EconScheduleConfig.load(file);

        assertEquals(1, releases.size());
        assertEquals("CPI MoM", releases.get(0).name());
        assertEquals(Instant.parse("2026-06-11T12:30:00Z"), releases.get(0).release());
        assertEquals(0.3, releases.get(0).consensus(), 1e-9);
    }

    @Test
    public void absentFileYieldsEmptyList()
    {
        assertTrue(EconScheduleConfig.load(new File("does-not-exist-econ-schedule.json")).isEmpty());
    }

    private static File temp(String json) throws Exception
    {
        File file = Files.createTempFile("econ-schedule", ".json").toFile();
        file.deleteOnExit();
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        return file;
    }
}
