package com.finsent.analyse.cmd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

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
}
