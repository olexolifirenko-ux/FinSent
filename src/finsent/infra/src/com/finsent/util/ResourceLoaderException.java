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
 *
 */

package com.finsent.util;

/**
 * Indicates a wrong situation while loading/saving a resource via
 * {@link ResourceLoader}.
 *
 * <p>This is a minimal variant of the original exception (which extended the
 * InfoReach {@code ExceptionBase} message/resource-provider framework), kept so
 * the XML subsystem can signal resource failures without that infrastructure.
 *
 * @author Alexey Getmanchuk
 */
public class ResourceLoaderException extends Exception
{
    private static final long serialVersionUID = -5572719004949365825L;

    public ResourceLoaderException()
    {
        super();
    }

    public ResourceLoaderException(String message)
    {
        super(message);
    }

    public ResourceLoaderException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public ResourceLoaderException(Throwable cause)
    {
        super(cause);
    }
}
