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

public class BadPropertyValueException extends PropertyException
{
    private static final long serialVersionUID = 1L;

    public BadPropertyValueException(String property, String value)
    {
        this(property, value, "");
    }

    public BadPropertyValueException(String name, String value, Throwable cause)
    {
        this(name, value, "", cause);
    }

    public BadPropertyValueException(String name, String value, String expectedType)
    {
        this("Cannot read " + name + "=" + value + asType(expectedType));
    }

    private static String asType(String type)
    {
        if (type.isEmpty())
            return "";
        else
            return " as " + type;
    }

    public BadPropertyValueException(String name, String value, String expectedType, Throwable cause)
    {
        this("Cannot read " + name + "=" + value + " as " + expectedType, cause);
    }

    public BadPropertyValueException(String message)
    {
        super(message);
    }

    public BadPropertyValueException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
