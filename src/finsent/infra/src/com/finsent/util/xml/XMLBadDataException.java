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
package com.finsent.util.xml;

/**
*  This exception is thrown when some error has been found in the XML file.
*/
public class XMLBadDataException extends Exception
{

    // constructors
    
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public XMLBadDataException(Throwable cause)
    {
        super(cause);
    }

    /**
    @param String message - error message that describes error conditions
    */
    public XMLBadDataException(String message)
    {
        super(message);
    }

    public XMLBadDataException(String message, Throwable cause)
    {
        super(message, cause);
    }
}



