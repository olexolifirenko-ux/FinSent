/*
 * Copyright (c) 1997-98 InfoReach, Inc. All Rights Reserved.
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
 * Thrown when config file parsing fails.
 *
 * @author  SP
 * @author  OM
 * @version 3.0, 24/09/98
 */
public
class BadConfigFileException
    extends Exception
{
// Required methods

    private static final long serialVersionUID = 1L;

    /**
     * Constructs an <code>BadCofigFileException</code> with no detail message.
     */
    public
    BadConfigFileException()
    {
        super();
    } // BadConfigFileException()

    /**
     * Constructs an <code>BadCofigFileException</code> class with
     * the specified detail message.
     *
     * @param message the detail message
     */
    public
    BadConfigFileException(String message)
    {
        super(message);
    } // BadConfigFileException()

    public BadConfigFileException(Throwable t)
    {
        super(t);
    }

    public BadConfigFileException(String message, Throwable cause)
    {
        super(message, cause);
    }
} // class BadConfigFileException
