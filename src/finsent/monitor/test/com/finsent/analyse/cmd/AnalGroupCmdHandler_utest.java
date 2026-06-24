package com.finsent.analyse.cmd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.finsent.core.Times;

/**
 * Verifies {@link AnalGroupCmdHandler#parseDayKey}: the full {@code YYYYMMDD_HHMM} form, the short
 * time-only {@code HHMM} form (today's UTC day implied), and the malformed/absent cases.
 */
public class AnalGroupCmdHandler_utest
{
    @Test
    public void parsesFullDayKey()
    {
        assertArrayEquals(new String[] {"20260517", "22:10"}, AnalGroupCmdHandler.parseDayKey("20260517_2210"));
    }

    @Test
    public void shortTimeOnlyFormImpliesTodayUtc()
    {
        assertArrayEquals(new String[] {Times.todayUtc(), "05:30"}, AnalGroupCmdHandler.parseDayKey("0530"));
    }

    @Test
    public void rejectsMalformedOrAbsentTokens()
    {
        assertNull(AnalGroupCmdHandler.parseDayKey(null));
        assertNull(AnalGroupCmdHandler.parseDayKey(""));
        assertNull(AnalGroupCmdHandler.parseDayKey("530"));        // not 4 digits
        assertNull(AnalGroupCmdHandler.parseDayKey("0530pm"));     // trailing junk
        assertNull(AnalGroupCmdHandler.parseDayKey("20260517"));   // day only, no time
    }

    @Test
    public void hasFlagDetectsAnExactToken()
    {
        assertTrue(AnalGroupCmdHandler.hasFlag(new String[] {"0530", "-notify"}, "-notify"));
        assertFalse(AnalGroupCmdHandler.hasFlag(new String[] {"0530"}, "-notify"));
        assertFalse(AnalGroupCmdHandler.hasFlag(new String[] {}, "-notify"));
    }

    @Test
    public void firstNonFlagFindsThePositionalRegardlessOfFlagOrder()
    {
        assertEquals("0530", AnalGroupCmdHandler.firstNonFlag(new String[] {"0530", "-notify"}));
        assertEquals("0530", AnalGroupCmdHandler.firstNonFlag(new String[] {"-notify", "0530"}));
        assertNull(AnalGroupCmdHandler.firstNonFlag(new String[] {"-notify"}));
        assertNull(AnalGroupCmdHandler.firstNonFlag(new String[] {}));
    }
}
