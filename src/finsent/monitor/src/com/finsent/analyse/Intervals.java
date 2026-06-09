package com.finsent.analyse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Interval-key and day arithmetic shared by the analysis pipeline and the macro-alert path (ports
 * the Python {@code _prev_interval_key} / {@code _prev_trading_day} helpers). Interval keys are
 * {@code HH:MM} bucket labels; days are {@code YYYYMMDD}. All arithmetic is calendar-based and pure.
 */
public final class Intervals
{
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd
    private static final int MINUTES_PER_DAY = 24 * 60;

    private Intervals()
    {
    }

    /** An interval key shifted backwards, with how many calendar days it crossed. */
    public record Shift(int dayOffset, String key)
    {
    }

    /** A {@code (day, key)} window coordinate. */
    public record DayKey(String day, String key)
    {
    }

    /** The window {@code windowMinutes} after {@code (day, key)}, rolling into the next day at midnight. */
    public static DayKey advance(String day, String key, int windowMinutes)
    {
        int total = parseMinutes(key) + windowMinutes;
        int dayOffset = 0;
        while (total >= MINUTES_PER_DAY)
        {
            total -= MINUTES_PER_DAY;
            dayOffset++;
        }
        return new DayKey(dayOffset == 0 ? day : plusDays(day, dayOffset), formatKey(total));
    }

    /** Whether {@code (day, key)} is at or before {@code (endDay, endKey)} chronologically. */
    public static boolean notAfter(String day, String key, String endDay, String endKey)
    {
        int dayCompare = day.compareTo(endDay);
        return dayCompare < 0 || (dayCompare == 0 && key.compareTo(endKey) <= 0);
    }

    /** {@code key} floored to the {@code windowMinutes} grid (e.g. 08:03 &rarr; 08:00 at 10-min windows). */
    public static String floorToWindow(String key, int windowMinutes)
    {
        return formatKey((parseMinutes(key) / windowMinutes) * windowMinutes);
    }

    /** {@code yyyymmdd} plus {@code days}, or the input unchanged if it cannot be parsed. */
    public static String plusDays(String yyyymmdd, int days)
    {
        return minusDays(yyyymmdd, -days);
    }

    /** The interval key {@code windowsBack} windows before {@code key}, wrapping across midnight. */
    public static Shift back(String key, int windowsBack, int windowMinutes)
    {
        int total = parseMinutes(key) - windowsBack * windowMinutes;
        int dayOffset = 0;
        while (total < 0)
        {
            total += MINUTES_PER_DAY;
            dayOffset++;
        }
        return new Shift(dayOffset, formatKey(total));
    }

    /** {@code yyyymmdd} minus {@code days}, or the input unchanged if it cannot be parsed. */
    public static String minusDays(String yyyymmdd, int days)
    {
        String result = yyyymmdd;
        try
        {
            result = LocalDate.parse(yyyymmdd, DAY).minusDays(days).format(DAY);
        }
        catch (DateTimeParseException notADay)
        {
            result = yyyymmdd;
        }
        return result;
    }

    /**
     * The previous calendar day only when it is a consecutive trading day (input is Tue&ndash;Fri);
     * {@code null} for Monday (previous is Sunday) or a weekend input, matching Python
     * {@code _prev_trading_day}.
     */
    public static String prevTradingDay(String yyyymmdd)
    {
        String result = null;
        LocalDate date = parseOrNull(yyyymmdd);
        if (date != null)
        {
            int dow = date.getDayOfWeek().getValue(); // Mon=1 .. Sun=7
            if (dow >= 2 && dow <= 5) // Tue..Fri
            {
                result = date.minusDays(1).format(DAY);
            }
        }
        return result;
    }

    /** Whether {@code yyyymmdd} falls on a Saturday or Sunday. */
    public static boolean isWeekend(String yyyymmdd)
    {
        LocalDate date = parseOrNull(yyyymmdd);
        return date != null && date.getDayOfWeek().getValue() >= 6;
    }

    public static int parseMinutes(String key)
    {
        int colon = key.indexOf(':');
        int hours = Integer.parseInt(key.substring(0, colon));
        int minutes = Integer.parseInt(key.substring(colon + 1));
        return hours * 60 + minutes;
    }

    public static String formatKey(int totalMinutes)
    {
        return String.format("%02d:%02d", totalMinutes / 60, totalMinutes % 60);
    }

    private static LocalDate parseOrNull(String yyyymmdd)
    {
        LocalDate date;
        try
        {
            date = LocalDate.parse(yyyymmdd, DAY);
        }
        catch (DateTimeParseException notADay)
        {
            date = null;
        }
        return date;
    }
}
