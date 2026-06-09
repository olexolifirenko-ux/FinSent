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

/**
 * Indicates that a resource (e.g. an XML resource referenced via
 * {@code <Include>}) could not be resolved or loaded.
 *
 * <p>This is a minimal variant of the original exception (which extended the
 * InfoReach {@code InfraException} message-provider framework).
 *
 * @author Andrey Aleshnikov
 */
public class BadResourceException extends Exception
{
    private static final long serialVersionUID = 1L;

    public BadResourceException(String message)
    {
        super(message);
    }

    public BadResourceException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public BadResourceException(Throwable cause)
    {
        super(cause);
    }
}
