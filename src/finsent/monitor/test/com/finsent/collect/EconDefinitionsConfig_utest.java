package com.finsent.collect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Test;

/**
 * Verifies {@link EconDefinitionsConfig}: parses the static definitions keyed by name, skips a
 * nameless entry, and yields an empty map for an absent file.
 */
public class EconDefinitionsConfig_utest
{
    @Test
    public void parsesDefinitionsKeyedByName() throws Exception
    {
        File file = temp("["
                + "{\"name\":\"CPI MoM\",\"series\":\"CUUR0000SA0\",\"kind\":\"mom_pct\",\"unit\":\"%\","
                + "\"hot_direction\":\"bearish\",\"inline_band\":0.1,\"high_band\":0.2},"
                + "{\"series\":\"X\"}"   // no name -> skipped
                + "]");

        Map<String, EconEventDef> defs = EconDefinitionsConfig.load(file);

        assertEquals(1, defs.size());
        EconEventDef cpi = defs.get("CPI MoM");
        assertEquals("CUUR0000SA0", cpi.series());
        assertEquals("mom_pct", cpi.kind());
        assertEquals("bearish", cpi.hotDirection());
        assertEquals(0.1, cpi.inlineBand(), 1e-9);
        assertEquals(0.2, cpi.highBand(), 1e-9);
    }

    @Test
    public void absentFileYieldsEmptyMap()
    {
        assertTrue(EconDefinitionsConfig.load(new File("does-not-exist-econ-defs.json")).isEmpty());
    }

    private static File temp(String json) throws Exception
    {
        File file = Files.createTempFile("econ-defs", ".json").toFile();
        file.deleteOnExit();
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        return file;
    }
}
