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

import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import static com.finsent.util.test.TestUtils.expectException;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mockStatic;

public class PropertyUtils_utest
{
    private static final double DELTA = 1e-10;

    @Test
    public void testStringList() throws NoPropertyFoundException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getStringList("var1", () -> null));

        Assert.assertArrayEquals(new String[]{"abc", "def"}, PropertyUtils.getStringList("var1", () -> "abc,def"));
        Assert.assertArrayEquals(new String[]{"abc", "def"}, PropertyUtils.getStringList("var1", () -> "abc, def"));
        Assert.assertArrayEquals(new String[0], PropertyUtils.getStringList("var1", () -> ""));
        Assert.assertArrayEquals(new String[0], PropertyUtils.getStringList("var1", () -> " "));
        Assert.assertArrayEquals(new String[] {"", ""}, PropertyUtils.getStringList("var1", () -> " , "));
    }

    @Test
    public void testInt() throws BadPropertyValueException, NoPropertyFoundException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getInt("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getInt("var1", () -> "abc"));

        assertEquals(4321, PropertyUtils.getInt("var1", () -> "4321"));
        assertEquals(-12345678, PropertyUtils.getInt("var1", () -> "-12345678"));
    }

    @Test
    public void testIntList() throws NoPropertyFoundException, BadPropertyValueException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getIntList("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getIntList("var1", () -> "3,ab"));

        Assert.assertArrayEquals(new int[]{2, 4}, PropertyUtils.getIntList("var1", () -> "2,4"));
        Assert.assertArrayEquals(new int[]{2, 4}, PropertyUtils.getIntList("var1", () -> "2, 4"));
        Assert.assertArrayEquals(new int[0], PropertyUtils.getIntList("var1", () -> ""));
    }

    @Test
    public void testTimeInterval() throws NoPropertyFoundException, BadPropertyValueException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getTimeInterval("var1", MICROSECONDS, () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getTimeInterval("var1", MICROSECONDS, () -> "100"));

        assertEquals(100L, PropertyUtils.getTimeInterval("var1", MICROSECONDS, () -> "100mks"));
        assertEquals(100L, PropertyUtils.getTimeInterval("var1", MILLISECONDS, () -> "100ms"));
        assertEquals(100L*1000, PropertyUtils.getTimeInterval("var1", MICROSECONDS, () -> "100 ms"));
        assertEquals(100L*1000*1000, PropertyUtils.getTimeInterval("var1", MICROSECONDS, () -> "100s"));
        assertEquals(100L*1000*1000*60, PropertyUtils.getTimeInterval("var1", MICROSECONDS, () -> "100m"));
        assertEquals(100L*1000*1000*60*60, PropertyUtils.getTimeInterval("var1", MICROSECONDS, () -> "100h"));
    }

    @Test
    public void testLong() throws BadPropertyValueException, NoPropertyFoundException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getLong("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getLong("var1", () -> "abc"));

        assertEquals(4366107128454488064L, PropertyUtils.getLong("var1", () -> "4366107128454488064"));
        assertEquals(-123456789012345L, PropertyUtils.getLong("var1", () -> "-123456789012345"));
    }

    @Test
    public void testLongList() throws NoPropertyFoundException, BadPropertyValueException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getLongList("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getLongList("var1", () -> "3,ab"));

        Assert.assertArrayEquals(new long[]{2, 4}, PropertyUtils.getLongList("var1", () -> "2,4"));
        Assert.assertArrayEquals(new long[]{2, 4}, PropertyUtils.getLongList("var1", () -> "2, 4"));
        Assert.assertArrayEquals(new long[0], PropertyUtils.getLongList("var1", () -> ""));
    }

    @Test
    public void testDouble() throws BadPropertyValueException, NoPropertyFoundException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getDouble("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getDouble("var1", () -> "abc"));

        assertEquals(43.21, PropertyUtils.getDouble("var1", () -> "43.21"), DELTA);
        assertEquals(-2.1234567e6, PropertyUtils.getDouble("var1", () -> "-2123456.7"), DELTA);
        assertEquals(-2.1234567e6, PropertyUtils.getDouble("var1", () -> "-2.1234567e6"), DELTA);
    }

    @Test
    public void testDoubleList() throws NoPropertyFoundException, BadPropertyValueException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getDoubleList("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getDoubleList("var1", () -> "3,ab"));

        Assert.assertTrue(Arrays.equals(new double[]{2.55, 4}, PropertyUtils.getDoubleList("var1", () -> "2.55,4")));
        Assert.assertTrue(Arrays.equals(new double[]{2, 4.23}, PropertyUtils.getDoubleList("var1", () -> "2, 4.23")));
        Assert.assertTrue(Arrays.equals(new double[0], PropertyUtils.getDoubleList("var1", () -> "")));
    }

    @Test
    public void testBool() throws NoPropertyFoundException, BadPropertyValueException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getBool("var1", () -> null));
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getBool("var1", () -> ""));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getBool("var1", () -> " "));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getBool("var1", () -> "tru"));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getBool("var1", () -> "aha"));

        assertEquals(true, PropertyUtils.getBool("var1", () -> "T"));
        assertEquals(true, PropertyUtils.getBool("var1", () -> "y"));
        assertEquals(true, PropertyUtils.getBool("var1", () -> "Yes"));
        assertEquals(true, PropertyUtils.getBool("var1", () -> "yes"));
        assertEquals(true, PropertyUtils.getBool("var1", () -> "True"));
        assertEquals(true, PropertyUtils.getBool("var1", () -> "on"));
        assertEquals(false, PropertyUtils.getBool("var1", () -> "f"));
        assertEquals(false, PropertyUtils.getBool("var1", () -> "N"));
        assertEquals(false, PropertyUtils.getBool("var1", () -> "nO"));
        assertEquals(false, PropertyUtils.getBool("var1", () -> "faLSe"));
        assertEquals(false, PropertyUtils.getBool("var1", () -> "off"));
    }

    @Test
    public void testBoolList() throws NoPropertyFoundException, BadPropertyValueException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getBoolList("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getBoolList("var1", () -> "true,ab"));

        Assert.assertTrue(Arrays.equals(new boolean[]{true, false}, PropertyUtils.getBoolList("var1", () -> "y, n")));
        Assert.assertTrue(Arrays.equals(new boolean[]{true, false}, PropertyUtils.getBoolList("var1", () -> "t, f")));
        Assert.assertTrue(Arrays.equals(new boolean[0], PropertyUtils.getBoolList("var1", () -> "")));
    }

    @Test
    public void testInetAddress() throws NoPropertyFoundException, BadPropertyValueException, UnknownHostException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getInetAddress("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getInetAddress("var1", () -> ""));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getInetAddress("var1", () -> " "));

        // mock access to underlying DNS subsystem to speed up process (was take more than 10 seconds)
        // wrapped with exception to limit scope of static mocking
        try(MockedStatic<InetAddress> inetAddressMockedStatic = mockStatic(InetAddress.class))
        {
            inetAddressMockedStatic.when(() ->InetAddress.getByName("tru")).thenThrow(UnknownHostException.class);
            expectException(BadPropertyValueException.class, () -> PropertyUtils.getInetAddress("var1", () -> "tru"));
        }

        assertEquals(InetAddress.getByAddress(new byte[] {123, 45, 67, 8}), PropertyUtils.getInetAddress("var1", () -> "123.45.67.8"));
        assertEquals(InetAddress.getByAddress(new byte[] {12, 45, 67, 8}), PropertyUtils.getInetAddress("var1", () -> "012.045.067.008"));
        assertEquals(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), PropertyUtils.getInetAddress("var1", () -> "localhost"));
    }

    @Test
    public void testInetAddressList() throws NoPropertyFoundException, BadPropertyValueException, UnknownHostException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getInetAddressList("var1", () -> null));

        // mock access to underlying DNS subsystem to speed up process (was take more than 10 seconds), mock first part of value separated by , (coma)
        // wrapped with exception to limit scope of static mocking
        try(MockedStatic<InetAddress> inetAddressMockedStatic = mockStatic(InetAddress.class))
        {
            inetAddressMockedStatic.when(() ->InetAddress.getByName("true")).thenThrow(UnknownHostException.class);
            // uncomment if order of parsing will not be guaranteed
            //inetAddressMockedStatic.when(() ->InetAddress.getByName("ab")).thenThrow(UnknownHostException.class);
            expectException(BadPropertyValueException.class, () -> PropertyUtils.getInetAddressList("var1", () -> "true,ab"));
        }

        Assert.assertTrue(Arrays.equals(new InetAddress[]{
            InetAddress.getByAddress(new byte[] {123, 45, 67, 8}),
            InetAddress.getByAddress(new byte[] {123, 45, 67, 9})
        }, PropertyUtils.getInetAddressList("var1", () -> "123.45.67.8, 123.45.67.9")));

        Assert.assertTrue(Arrays.equals(new InetAddress[0], PropertyUtils.getInetAddressList("var1", () -> "")));
    }

    @Test
    public void testSocketAddress() throws NoPropertyFoundException, BadPropertyValueException, UnknownHostException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getSocketAddress("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getSocketAddress("var1", () -> ""));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getSocketAddress("var1", () -> " "));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getSocketAddress("var1", () -> "tru"));

        assertEquals(
            new InetSocketAddress("123.45.67.8", 8080),
            PropertyUtils.getSocketAddress("var1", () -> "123.45.67.8:8080")
        );

        assertEquals(
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 12345),
            PropertyUtils.getSocketAddress("var1", () -> "localhost:12345")
        );
    }

    @Test
    public void testSocketAddressList() throws NoPropertyFoundException, BadPropertyValueException, UnknownHostException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getSocketAddressList("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getSocketAddressList("var1", () -> "true,ab"));

        Assert.assertTrue(Arrays.equals(new SocketAddress[]{
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {123, 45, 67, 8}), 8080),
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {123, 45, 67, 9}), 8080)
        }, PropertyUtils.getSocketAddressList("var1", () -> "123.45.67.8:8080, 123.45.67.9:8080")));

        Assert.assertTrue(Arrays.equals(new InetAddress[0], PropertyUtils.getSocketAddressList("var1", () -> "")));
    }

    @Test
    public void testClass() throws NoPropertyFoundException, BadPropertyValueException
    {
        expectException(NoPropertyFoundException.class, () -> PropertyUtils.getClazz("var1", () -> null));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getClazz("var1", () -> ""));
        expectException(BadPropertyValueException.class, () -> PropertyUtils.getClazz("var1", () -> "aha"));

        assertEquals(java.util.List.class, PropertyUtils.getClazz("var1", () -> "java.util.List"));
    }
}
