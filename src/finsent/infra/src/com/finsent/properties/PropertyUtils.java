/*
 * Copyright (c) 1997-2019 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.properties;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import com.finsent.util.EmptyArrays;
import com.finsent.util.TimeUnitUtil;
import com.finsent.util.UtilityFunctions;
import com.finsent.util.sockets.SocketUtils;

import static com.finsent.util.ArrayUtilityFunctions.asArray;

public class PropertyUtils
{
    public static final String DEFAULT_DELIMETERS = ",;\t|";

    // Inlined from the InfoReach Value class (Value.YES = 'Y', Value.NO = 'N')
    // to avoid pulling in that heavyweight type just for stringToBool().
    private static final char VALUE_YES = 'Y';
    private static final char VALUE_NO  = 'N';

    private static String getString(@Nonnull String name, Supplier<String> valueSupplier, String defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        return value;
    }

    public static String getString(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getString(name, valueSupplier, null, false);
    }

    public static String getString(@Nonnull String name, Supplier<String> valueSupplier, String defaultValue) throws BadPropertyValueException
    {
        return getString(name, valueSupplier, defaultValue, true);
    }

    private static String[] getStringList(@Nonnull String name, Supplier<String> valueSupplier, String[] defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        String[] vv = UtilityFunctions.getTokens(value, DEFAULT_DELIMETERS, true, false);
        ArrayList<String> values = new ArrayList<>(Arrays.asList(vv));

        if (values.size() == 1 && values.get(0).isEmpty())
            return EmptyArrays.STRING_ARRAY;
        else
            return asArray(values);
    }

    public static String[] getStringList(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getStringList(name, valueSupplier, null, false);
    }

    public static String[] getStringList(@Nonnull String name, Supplier<String> valueSupplier, String[] defaultValue) throws BadPropertyValueException
    {
        return getStringList(name, valueSupplier, defaultValue, true);
    }

    private static int getInt(@Nonnull String name, Supplier<String> valueSupplier, int defaultValue, boolean useDefault) throws BadPropertyValueException, NoPropertyFoundException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            throw new BadPropertyValueException(name, value, "int");
        }
    }

    private static String getValueFromSupplier(Supplier<String> valueSupplier)
    {
        try
        {
            return valueSupplier.get();
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    public static int getInt(@Nonnull String name, Supplier<String> valueSupplier) throws BadPropertyValueException, NoPropertyFoundException
    {
        return getInt(name, valueSupplier, 0, false);
    }

    public static int getInt(@Nonnull String name, Supplier<String> valueSupplier, int defaultValue) throws BadPropertyValueException
    {
        return getInt(name, valueSupplier, defaultValue, true);
    }

    private static int[] getIntList(@Nonnull String name, Supplier<String> valueSupplier, int[] defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        String[] values = getStringList(name, valueSupplier);
        int[] intList = new int[values.length];
        for (int i = 0; i < values.length; i++)
        {
            int index = i;
            intList[i] = getInt(name, () -> values[index]);
        }
        return intList;
    }


    public static int[] getIntList(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getIntList(name, valueSupplier, null, false);
    }

    public static int[] getIntList(@Nonnull String name, Supplier<String> valueSupplier, int[] defaultValue) throws BadPropertyValueException
    {
        return getIntList(name, valueSupplier, defaultValue, true);
    }

    private static long getLong(@Nonnull String name, Supplier<String> valueSupplier, long defaultValue, boolean useDefault) throws BadPropertyValueException, NoPropertyFoundException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        try
        {
            return Long.parseLong(value);
        }
        catch (NumberFormatException e)
        {
            throw new BadPropertyValueException(name, value, "long");
        }
    }

    public static long getLong(@Nonnull String name, Supplier<String> valueSupplier) throws BadPropertyValueException, NoPropertyFoundException
    {
        return getLong(name, valueSupplier, 0L, false);
    }

    public static long getLong(@Nonnull String name, Supplier<String> valueSupplier, long defaultValue) throws BadPropertyValueException
    {
        return getLong(name, valueSupplier, defaultValue, true);
    }

    private static long[] getLongList(@Nonnull String name, Supplier<String> valueSupplier, long[] defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        String[] values = getStringList(name, valueSupplier);
        long[] longList = new long[values.length];
        for (int i = 0; i < values.length; i++)
        {
            int index = i;
            longList[i] = getLong(name, () -> values[index]);
        }
        return longList;
    }

    public static long[] getLongList(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getLongList(name, valueSupplier, null, false);
    }

    public static long[] getLongList(@Nonnull String name, Supplier<String> valueSupplier, long[] defaultValue) throws BadPropertyValueException
    {
        return getLongList(name, valueSupplier, defaultValue, true);
    }

    private static double getDouble(@Nonnull String name, Supplier<String> valueSupplier, double defaultValue, boolean useDefault) throws BadPropertyValueException, NoPropertyFoundException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        try
        {
            return Double.parseDouble(value);
        }
        catch (NumberFormatException e)
        {
            throw new BadPropertyValueException(name, value, "double");
        }
    }

    public static double getDouble(@Nonnull String name, Supplier<String> valueSupplier) throws BadPropertyValueException, NoPropertyFoundException
    {
        return getDouble(name, valueSupplier, 0D, false);
    }

    public static double getDouble(@Nonnull String name, Supplier<String> valueSupplier, double defaultValue) throws BadPropertyValueException
    {
        return getDouble(name, valueSupplier, defaultValue, true);
    }

    private static double[] getDoubleList(@Nonnull String name, Supplier<String> valueSupplier, double[] defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        String[] values = getStringList(name, valueSupplier);
        double[] doubleList = new double[values.length];
        for (int i = 0; i < values.length; i++)
        {
            int index = i;
            doubleList[i] = getDouble(name, () -> values[index]);
        }
        return doubleList;
    }

    public static double[] getDoubleList(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getDoubleList(name, valueSupplier, null, false);
    }

    public static double[] getDoubleList(@Nonnull String name, Supplier<String> valueSupplier, double[] defaultValue) throws BadPropertyValueException
    {
        return getDoubleList(name, valueSupplier, defaultValue, true);
    }

    public static Boolean stringToBool(String s)
    {
        s = s.toLowerCase();

        switch (s)
        {
            case "true":
            case "t":
            case "on":
            case "yes":
            case "y":
            case "" + (long)VALUE_YES:
            case "" + (double)VALUE_YES:
                return Boolean.TRUE;
            case "false":
            case "off":
            case "no":
            case "n":
            case "f":
            case "" + (long)VALUE_NO:
            case "" + (double)VALUE_NO:
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    private static boolean getBool(@Nonnull String name, Supplier<String> valueSupplier, boolean defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null || value.isEmpty())
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        Boolean b = stringToBool(value);
        if (b != null)
        {
            return b.booleanValue();
        }
        else
        {
            throw new BadPropertyValueException(name, value, "bool");
        }
    }

    public static boolean getBool(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getBool(name, valueSupplier, false, false);
    }

    public static boolean getBool(@Nonnull String name, Supplier<String> valueSupplier, boolean defaultValue) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getBool(name, valueSupplier, defaultValue, true);
    }

    private static boolean[] getBoolList(@Nonnull String name, Supplier<String> valueSupplier, boolean[] defaultValue, boolean useDefault) throws BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        String[] values = getStringList(name, valueSupplier);
        boolean[] list = new boolean[values.length];
        for (int i = 0; i < values.length; i++)
        {
            int index = i;
            list[i] = getBool(name, () -> values[index]);
        }
        return list;
    }

    public static boolean[] getBoolList(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getBoolList(name, valueSupplier, null, false);
    }

    public static boolean[] getBoolList(@Nonnull String name, Supplier<String> valueSupplier, boolean[] defaultValue) throws BadPropertyValueException
    {
        return getBoolList(name, valueSupplier, defaultValue, true);
    }

    private static InetAddress getInetAddress(@Nonnull String name, Supplier<String> valueSupplier, InetAddress defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        value = value.trim();
        if (value.isEmpty())
            throw new BadPropertyValueException(name, value, "inet address");

        try
        {
            return InetAddress.getByName(value);
        }
        catch (UnknownHostException e)
        {
            throw new BadPropertyValueException(name, value, "inet address");
        }
    }

    public static InetAddress getInetAddress(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getInetAddress(name, valueSupplier, null, false);
    }

    public static InetAddress getInetAddress(@Nonnull String name, Supplier<String> valueSupplier, InetAddress defaultValue) throws BadPropertyValueException
    {
        return getInetAddress(name, valueSupplier, defaultValue, true);
    }

    private static InetAddress[] getInetAddressList(@Nonnull String name, Supplier<String> valueSupplier, InetAddress[] defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        String[] values = getStringList(name, valueSupplier);

        try
        {
            InetAddress[] result = new InetAddress[values.length];
            for(int i = 0; i < result.length; ++i)
            {
                int index = i;
                result[i] = getInetAddress(name, () -> values[index]);
            }
            return result;
        }
        catch (Throwable e)
        {
            throw new BadPropertyValueException(name, value, "socket addresses");
        }
    }

    public static InetAddress[] getInetAddressList(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getInetAddressList(name, valueSupplier,  null, false);
    }

    public static InetAddress[] getInetAddressList(@Nonnull String name, Supplier<String> valueSupplier, InetAddress[] defaultValue) throws BadPropertyValueException
    {
        return getInetAddressList(name, valueSupplier,  defaultValue, true);
    }

    private static SocketAddress getSocketAddress(@Nonnull String name, Supplier<String> valueSupplier, SocketAddress defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        try
        {
            return SocketUtils.socketAddressFromString(value);
        }
        catch (Throwable e)
        {
            throw new BadPropertyValueException(name, value, "socket address");
        }
    }

    public static SocketAddress getSocketAddress(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getSocketAddress(name, valueSupplier, null, false);
    }

    public static SocketAddress getSocketAddress(@Nonnull String name, Supplier<String> valueSupplier, SocketAddress defaultValue) throws BadPropertyValueException
    {
        return getSocketAddress(name, valueSupplier, defaultValue, true);
    }

    private static SocketAddress[] getSocketAddressList(@Nonnull String name, Supplier<String> valueSupplier, SocketAddress[] defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        String[] values = getStringList(name, valueSupplier);
        try
        {
            SocketAddress[] result = new SocketAddress[values.length];
            for(int i = 0; i < result.length; ++i)
            {
                int index = i;
                result[i] = getSocketAddress(name, () -> values[index]);
            }
            return result;
        }
        catch (Throwable e)
        {
            throw new BadPropertyValueException(name, value, "socket address list");
        }
    }

    public static SocketAddress[] getSocketAddressList(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getSocketAddressList(name, valueSupplier, null, false);
    }

    public static SocketAddress[] getSocketAddressList(@Nonnull String name, Supplier<String> valueSupplier, SocketAddress[] defaultValue) throws BadPropertyValueException
    {
        return getSocketAddressList(name, valueSupplier, defaultValue, true);
    }

    private static long getTimeInterval(@Nonnull String name, TimeUnit unit, Supplier<String> valueSupplier, long defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }
        try
        {
            return TimeUnitUtil.valueOf(value, unit);
        }
        catch(IllegalArgumentException e)
        {
            throw new BadPropertyValueException(name, value, "time interval", e);
        }
    }

    public static long getTimeInterval(@Nonnull String name, TimeUnit unit, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getTimeInterval(name, unit, valueSupplier, 0L, false);
    }

    public static long getTimeInterval(@Nonnull String name, TimeUnit unit, Supplier<String> valueSupplier, long defaultValue) throws BadPropertyValueException
    {
        return getTimeInterval(name, unit, valueSupplier, defaultValue, true);
    }

    private static <T> Class<T> getClazz(@Nonnull String name, Supplier<String> valueSupplier, Class<T> defaultValue, boolean useDefault) throws NoPropertyFoundException, BadPropertyValueException
    {
        String value = getValueFromSupplier(valueSupplier);
        if (value == null)
        {
            if (useDefault)
                return defaultValue;
            else
                throw new NoPropertyFoundException(name);
        }

        try
        {
            return (Class<T>) Class.forName(value);
        }
        catch (ClassNotFoundException e)
        {
            throw new BadPropertyValueException(name, value, "class");
        }
    }

    public static <T> Class<T> getClazz(@Nonnull String name, Supplier<String> valueSupplier) throws NoPropertyFoundException, BadPropertyValueException
    {
        return getClazz(name, valueSupplier, null, false);
    }

    public static <T> Class<T> getClazz(@Nonnull String name, Supplier<String> valueSupplier, Class<T> defaultValue) throws BadPropertyValueException
    {
        return getClazz(name, valueSupplier, defaultValue, true);
    }
}
