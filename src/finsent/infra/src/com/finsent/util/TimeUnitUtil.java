/*
 * Copyright (c) 1997-2009 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 *
 */
package com.finsent.util;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import com.finsent.util.iterator.ReverseListIterator;

/**
 * Utility functions for JDK {@link TimeUnit} class
 *
 * @author Eugeny Schava
 */
public class TimeUnitUtil
{
    private static final Map<String, TimeUnit> UNIT_NAMES = new HashMap<>();
    private static final Map<TimeUnit, String> MODERATE_UNIT_NAMES = new EnumMap<>(TimeUnit.class);
    private static final Map<TimeUnit, String> SHORT_UNIT_NAMES = new EnumMap<>(TimeUnit.class);
    static
    {
        UNIT_NAMES.put("days", TimeUnit.DAYS);
        UNIT_NAMES.put("day", TimeUnit.DAYS);
        UNIT_NAMES.put("d", TimeUnit.DAYS);

        UNIT_NAMES.put("hours", TimeUnit.HOURS);
        UNIT_NAMES.put("hour", TimeUnit.HOURS);
        UNIT_NAMES.put("h", TimeUnit.HOURS);

        UNIT_NAMES.put("minutes", TimeUnit.MINUTES);
        UNIT_NAMES.put("minute", TimeUnit.MINUTES);
        UNIT_NAMES.put("mins", TimeUnit.MINUTES);
        UNIT_NAMES.put("min", TimeUnit.MINUTES);
        UNIT_NAMES.put("m", TimeUnit.MINUTES);

        UNIT_NAMES.put("seconds", TimeUnit.SECONDS);
        UNIT_NAMES.put("second", TimeUnit.SECONDS);
        UNIT_NAMES.put("secs", TimeUnit.SECONDS);
        UNIT_NAMES.put("sec", TimeUnit.SECONDS);
        UNIT_NAMES.put("s", TimeUnit.SECONDS);

        UNIT_NAMES.put("milliseconds", TimeUnit.MILLISECONDS);
        UNIT_NAMES.put("millisecond", TimeUnit.MILLISECONDS);
        UNIT_NAMES.put("millis", TimeUnit.MILLISECONDS);
        UNIT_NAMES.put("msecs", TimeUnit.MILLISECONDS);
        UNIT_NAMES.put("msec", TimeUnit.MILLISECONDS);
        UNIT_NAMES.put("ms", TimeUnit.MILLISECONDS);

        UNIT_NAMES.put("microseconds", TimeUnit.MICROSECONDS);
        UNIT_NAMES.put("microsecond", TimeUnit.MICROSECONDS);
        UNIT_NAMES.put("micros", TimeUnit.MICROSECONDS);
        UNIT_NAMES.put("mks", TimeUnit.MICROSECONDS);
        UNIT_NAMES.put("us", TimeUnit.MICROSECONDS);

        UNIT_NAMES.put("nanoseconds", TimeUnit.NANOSECONDS);
        UNIT_NAMES.put("nanosecond", TimeUnit.NANOSECONDS);
        UNIT_NAMES.put("nanos", TimeUnit.NANOSECONDS);
        UNIT_NAMES.put("ns", TimeUnit.NANOSECONDS);

        MODERATE_UNIT_NAMES.put(TimeUnit.DAYS, "day");
        MODERATE_UNIT_NAMES.put(TimeUnit.HOURS, "hour");
        MODERATE_UNIT_NAMES.put(TimeUnit.MINUTES, "min");
        MODERATE_UNIT_NAMES.put(TimeUnit.SECONDS, "sec");
        MODERATE_UNIT_NAMES.put(TimeUnit.MILLISECONDS, "msec");
        MODERATE_UNIT_NAMES.put(TimeUnit.MICROSECONDS, "mks");
        MODERATE_UNIT_NAMES.put(TimeUnit.NANOSECONDS, "ns");

        SHORT_UNIT_NAMES.put(TimeUnit.DAYS, "d");
        SHORT_UNIT_NAMES.put(TimeUnit.HOURS, "h");
        SHORT_UNIT_NAMES.put(TimeUnit.MINUTES, "m");
        SHORT_UNIT_NAMES.put(TimeUnit.SECONDS, "s");
        SHORT_UNIT_NAMES.put(TimeUnit.MILLISECONDS, "ms");
        SHORT_UNIT_NAMES.put(TimeUnit.MICROSECONDS, "us");
        SHORT_UNIT_NAMES.put(TimeUnit.NANOSECONDS, "ns");
    }
    private static final Set<String> INFINITY_NAMES = new HashSet<String>();
    static
    {
        INFINITY_NAMES.add("inf");
        INFINITY_NAMES.add("infinity");
    }
    private static final String ZERO = "0";
    private static final String MINUS = "-";

    private static Pattern PATTERN = Pattern.compile("^(\\d+)\\s*([a-zA-Z]+)[\\s,]*(.*)$");

    public enum DurationFormat
    {
        FULL,
        MODERATE,
        SHORT
    }

    /**
     * Converts string representation of time period in time units
     * @param str string representation of time period
     * @param timeUnit time unit
     * @return time period in given units
     * @throws IllegalArgumentException if string representation doesn't have valid format
     */
    public static long valueOf(String str, TimeUnit timeUnit) throws IllegalArgumentException
    {
        long result = 0;

        if (ZERO.equals(str))
        {
            // '0' is always '0', and it does not need to be specified with unit
            return 0;
        }
        if (INFINITY_NAMES.contains(str))
        {
            return Long.MAX_VALUE;
        }
        boolean negative = false;
        if (str.startsWith(MINUS))
        {
            negative = true;
            str = str.substring(MINUS.length());
        }
        str = str.trim();
        while (str.length() > 0)
        {
            Matcher matcher = PATTERN.matcher(str);
            if (matcher.matches())
            {
                final long value = Long.parseLong(matcher.group(1));
                final TimeUnit unit = stringToTimeUnit(matcher.group(2));
                str = matcher.group(3); // the rest

                result += timeUnit.convert(value, unit);
            }
            else
                throw new IllegalArgumentException("String '" + str + "' doesn't have valid format");
        }
        if (negative)
        {
            result = -result;
        }
        return result;
    }

    public static long valueWithDefaultUnit(String str, TimeUnit timeUnit) throws IllegalArgumentException
    {
        return valueWithDefaultUnit(str, timeUnit, timeUnit);
    }

    public static long valueWithDefaultUnit(String str, TimeUnit timeUnit, TimeUnit defaultTimeUnit) throws IllegalArgumentException
    {
        try
        {
            return valueOf(str, timeUnit);
        }
        catch (IllegalArgumentException exc)
        {
            try
            {
                return timeUnit.convert(Long.parseLong(str), defaultTimeUnit);
            }
            catch (NumberFormatException num)
            {
                throw exc;
            }
        }
    }

    public static String millisToString(long millis)
    {
        return durationToString(millis, TimeUnit.MILLISECONDS);
    }

    public static String millisToString(long millis, DurationFormat format)
    {
        return durationToString(millis, TimeUnit.MILLISECONDS, format);
    }

    public static String durationToString(long duration, TimeUnit unit)
    {
        return durationToString(duration, unit, DurationFormat.FULL);
    }

    public static String durationToString(long duration, TimeUnit unit, DurationFormat format)
    {
        if (duration == 0)
            return "0";

        StringBuilder result = new StringBuilder();
        if (duration < 0)
        {
            result.append("-");
            duration = -duration;
        }

        for (TimeUnit timeUnit : new ReverseListIterator<>(Arrays.asList(TimeUnit.values()))) // from long to short
        {
            long timeUnitInDurationUnits = unit.convert(1, timeUnit);
            if (duration >= timeUnitInDurationUnits)
            {
                long c = duration / timeUnitInDurationUnits;
                if (c > 0)
                {
                    duration %= timeUnitInDurationUnits;

                    String unitName = formatUnitName(timeUnit, c, format);

                    if (result.length() > 1) result.append(" ");
                    result.append(c).append(" ").append(unitName);

                    if (duration == 0)
                        break;
                }
            }
        }

        return result.toString();
    }

    private static String formatUnitName(TimeUnit timeUnit, long count, DurationFormat format)
    {
        String unitName = null;
        if (format == DurationFormat.MODERATE)
        {
            unitName = MODERATE_UNIT_NAMES.get(timeUnit);
            if (count > 1 && (timeUnit == TimeUnit.DAYS || timeUnit == TimeUnit.HOURS))
            {
                unitName += "s";
            }
        }
        else if (format == DurationFormat.SHORT)
        {
            unitName = SHORT_UNIT_NAMES.get(timeUnit);
        }

        return unitName != null
            ? unitName
            : timeUnit.name().substring(0, timeUnit.name().length() - (count == 1 ? 1 : 0)).toLowerCase();
    }

    public static @Nonnull TimeUnit stringToTimeUnit(String str)
    {
        TimeUnit unit = UNIT_NAMES.get(str);
        if (unit == null)
        {
            throw new IllegalArgumentException("Unit '" + str + "' is not known");
        }
        return unit;
    }
}
