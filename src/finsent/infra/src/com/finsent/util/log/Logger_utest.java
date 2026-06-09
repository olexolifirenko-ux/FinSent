package com.finsent.util.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.junit.Test;

/**
 * Verifies {@link Logger}'s daily rolling: the dated-filename derivation ({@code datedFileName}) and
 * the day-change roll ({@code rollIfDayChanged}) -- a new dated file opens when an entry's timestamp
 * crosses into a new local calendar day, which is what gives a non-stop process one file per day.
 */
public class Logger_utest
{
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    @Test
    public void datedFileNameInsertsDateBeforeExtension()
    {
        assertEquals("/logs/FSSatellite.2026-06-07.log",
                Logger.datedFileName("/logs/FSSatellite.log", "2026-06-07"));
        assertEquals("a Windows path keeps its directory dots intact",
                "C:\\a.b\\FSSatellite.2026-06-07.log",
                Logger.datedFileName("C:\\a.b\\FSSatellite.log", "2026-06-07"));
        assertEquals("no extension -> date appended",
                "/logs/FSSatellite.2026-06-07",
                Logger.datedFileName("/logs/FSSatellite", "2026-06-07"));
    }

    @Test
    public void initializeOpensTodaysDatedFile() throws Exception
    {
        Path dir = Files.createTempDirectory("fslog");
        String base = dir.resolve("FSSatellite.log").toString();
        Logger logger = new Logger();
        logger.initialize(base);

        String today = dayStamp(System.currentTimeMillis());
        assertTrue(logger.isLogRollingEnabled());
        assertEquals(Logger.datedFileName(base, today), logger.getLogFilePath());
        assertTrue("today's dated file exists", new File(Logger.datedFileName(base, today)).exists());
    }

    @Test
    public void rollIfDayChangedOpensNewFileOnNewDayOnly() throws Exception
    {
        Path dir = Files.createTempDirectory("fslog");
        String base = dir.resolve("FSSatellite.log").toString();
        Logger logger = new Logger();
        logger.initialize(base);

        // A fixed past instant and the following day, formatted with the same local-zone day stamp.
        long day1 = 1_577_000_000_000L; // 2019-12-22-ish local
        long day2 = day1 + DAY_MS;
        String d1 = dayStamp(day1);
        String d2 = dayStamp(day2);

        logger.rollIfDayChanged(day1);
        assertEquals(Logger.datedFileName(base, d1), logger.getLogFilePath());
        assertTrue(new File(Logger.datedFileName(base, d1)).exists());

        // Same day again -> no change.
        logger.rollIfDayChanged(day1 + 60_000L);
        assertEquals(Logger.datedFileName(base, d1), logger.getLogFilePath());

        // Next day -> rolls to a new dated file.
        logger.rollIfDayChanged(day2);
        assertEquals(Logger.datedFileName(base, d2), logger.getLogFilePath());
        assertTrue(new File(Logger.datedFileName(base, d2)).exists());
    }

    @Test
    public void consoleLoggerDoesNotRoll()
    {
        Logger logger = new Logger();
        logger.initialize(System.out);
        assertFalse(logger.isLogRollingEnabled());
        // No base path -> the roll check is a no-op and never throws.
        logger.rollIfDayChanged(System.currentTimeMillis());
    }

    private static String dayStamp(long millis)
    {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date(millis));
    }
}
