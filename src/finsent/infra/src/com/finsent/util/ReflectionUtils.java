/*
 * Copyright (c) 1997-2000 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util;

import java.lang.reflect.Field;

/**
 * Reflection helpers.
 *
 * <p>This is a minimal variant of the full InfoReach {@code ReflectionUtils},
 * carrying only the field accessors used by the XML subsystem.
 */
public class ReflectionUtils
{
    private ReflectionUtils() {}

    /**
     * Finds a (possibly inherited) declared field by name and makes it accessible.
     *
     * @throws NoSuchFieldException if no such field exists in the class hierarchy.
     */
    public static Field getField(Class<?> cl, String fieldName) throws NoSuchFieldException
    {
        Field field = null;
        do
        {
            try
            {
                field = cl.getDeclaredField(fieldName);
            }
            catch (NoSuchFieldException ex)
            {
                cl = cl.getSuperclass();
            }
        }
        while (field == null && cl != null);

        if (field == null)
        {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true);
        return field;
    }

    public static Object getFieldValue(Object obj, String fieldName) throws NoSuchFieldException, IllegalAccessException
    {
        return getField(obj.getClass(), fieldName).get(obj);
    }
}
