package com.finsent.core;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import java.util.concurrent.TimeUnit;

import com.finsent.util.TimeUnitUtil;

/**
 * Time helpers shared across the pipeline. Ports the time-handling conventions of
 * the Python {@code shared.py} / {@code analyse.py}:
 * <ul>
 *   <li>the canonical UTC timestamp format {@code yyyy-MM-dd'T'HH:mm:ss'Z'};</li>
 *   <li>interval-window flooring into {@code HH:MM} keys;</li>
 *   <li>parsing of duration specs such as {@code "10m"} / {@code "12h"} into minutes
 *       (delegated to {@link TimeUnitUtil}, which already understands the same units).</li>
 * </ul>
 * All instants are handled in UTC, matching the data files which store UTC throughout.
 */
public final class Times
{
    /** Canonical timestamp format used in every data file, e.g. {@code 2026-05-31T00:54:19Z}. */
    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final String DEFAULT_INTERVAL_KEY = "00:00";

    private Times()
    {
    }

    /** Current UTC time as a canonical {@code yyyy-MM-dd'T'HH:mm:ss'Z'} string. */
    public static String nowUtcIso()
    {
        return ISO_UTC.format(Instant.now());
    }

    /** Format an instant as a canonical UTC timestamp string. */
    public static String formatUtcIso(Instant instant)
    {
        return ISO_UTC.format(instant);
    }

    /**
     * Parse a stored timestamp into an {@link Instant}. Accepts both the canonical
     * {@code ...Z} form and an explicit offset form (e.g. {@code ...+00:00}), mirroring
     * the Python {@code datetime.fromisoformat(ts.replace("Z","+00:00"))} tolerance.
     *
     * @throws java.time.format.DateTimeParseException if the value is not a valid timestamp.
     */
    public static Instant parseIso(String iso)
    {
        Instant result;
        try
        {
            result = Instant.parse(iso);
        }
        catch (java.time.format.DateTimeParseException notInstant)
        {
            result = OffsetDateTime.parse(iso).toInstant();
        }
        return result;
    }

    /**
     * Number of whole minutes in a duration spec such as {@code "10m"}, {@code "30m"}
     * or {@code "12h"}. Ports {@code shared._interval_to_minutes}; unparseable specs
     * fall back to {@code 1} (the Python default).
     */
    public static int intervalMinutes(String spec)
    {
        return intervalMinutes(spec, 1);
    }

    /** As {@link #intervalMinutes(String)} but with a caller-supplied fallback. */
    public static int intervalMinutes(String spec, int fallback)
    {
        int minutes = fallback;
        if (spec != null && !spec.isEmpty())
        {
            try
            {
                minutes = (int) TimeUnitUtil.valueOf(spec.trim().toLowerCase(), TimeUnit.MINUTES);
            }
            catch (IllegalArgumentException badSpec)
            {
                minutes = fallback;
            }
        }
        return minutes;
    }

    /**
     * Interval key {@code "HH:MM"} for a timestamp, floored to the window boundary.
     * Ports {@code analyse._interval_key}; an unparseable timestamp yields {@code "00:00"}.
     */
    public static String intervalKey(String iso, int windowMinutes)
    {
        String key;
        try
        {
            key = intervalKey(parseIso(iso), windowMinutes);
        }
        catch (java.time.format.DateTimeParseException badTimestamp)
        {
            key = DEFAULT_INTERVAL_KEY;
        }
        return key;
    }

    /** Interval key {@code "HH:MM"} for an instant, floored to the window boundary. */
    public static String intervalKey(Instant instant, int windowMinutes)
    {
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        int flooredMinute = (utc.getMinute() / windowMinutes) * windowMinutes;
        return String.format("%02d:%02d", utc.getHour(), flooredMinute);
    }

    /**
     * The {@code YYYYMMDD} day string for a stored timestamp. Ports
     * {@code analyse._interval_day}: it uses the date prefix of the timestamp itself
     * (which is already UTC), falling back to today when the value is too short.
     */
    public static String dayOf(String iso)
    {
        String day;
        if (iso != null && iso.length() >= 10)
        {
            day = iso.substring(0, 10).replace("-", "");
        }
        else
        {
            day = todayUtc();
        }
        return day;
    }

    /** Today's {@code YYYYMMDD} in UTC. */
    public static String todayUtc()
    {
        return DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.now());
    }
}
