/*
 * Copyright (c) 1997-2012 InfoReach, Inc. All Rights Reserved.
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

/**
 * Used to not create empty arrays each times when they are needed.
 * @author Alexander Dolgin
 */
public class EmptyArrays
{
    public static final byte[] BYTE_ARRAY = new byte[0];
    public static final short[] SHORT_ARRAY = new short[0];
    public static final char[] CHAR_ARRAY = new char[0];
    public static final int[] INT_ARRAY = new int[0];
    public static final long[] LONG_ARRAY = new long[0];
    public static final float[] FLOAT_ARRAY = new float[0];
    public static final double[] DOUBLE_ARRAY = new double[0];
    public static final boolean[] BOOLEAN_ARRAY = new boolean[0];

    public static final Object[] OBJECT_ARRAY = new Object[0];
    public static final String[] STRING_ARRAY = new String[0];
    public static final Long[] LONG_OBJECT_ARRAY = new Long[0];
    @SuppressWarnings("rawtypes")
    public static final Class[] CLASS_ARRAY = new Class[0];
}
