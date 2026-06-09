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

package com.finsent.directory;


/**
 * Any directory access error will cause this exception.
 * @author Konstantine Matokhin
 */
public class DirectoryException extends Exception
{
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	/**
    *  Reserved for any errors on the remote site.
    */
    public static final int REMOTE_ACCESS_ERROR    = 0;
    /**
    *  Reserved for any errors with file system.
    */
    public static final int FILE_ACCESS_ERROR      = 1;
    /**
    *  Reserved for error when writing to read-only file.
    */
    public static final int FILE_READ_ONLY_ERROR   = 2;
    /**
    *  Reserved for any errors with naming system.
    */
    public static final int NAMING_ERROR           = 3;
    /**
    *  Reserved for any error
    */
    public static final int ANY_ERROR              = 4;


    /**
     * Determinates the code of the exception.
     */
    private int code_;

    public DirectoryException()
    {
        this("");
    }

    public DirectoryException(String message)
    {
        this(ANY_ERROR, message);
    }

    /**
     * Returns the code of the exception.
     */
    public int getCode()
    {
        return code_;
    }

    public DirectoryException(int code, Throwable cause)
    {
        super(cause);
        code_ = code;
    }

    /**
     * @param code the code of the error
     * @param message the message to be shown.
     */
    public DirectoryException(int code, String message)
    {
        super(message);
        code_ = code;
    }
}
