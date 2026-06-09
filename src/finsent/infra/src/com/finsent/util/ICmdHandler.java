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

import java.io.Writer;

/**
 * @author  VS
 * @version 2.0, 09/29/97
 */
public interface ICmdHandler
{
    /**
    Performs command specific actions
    @param writer - writer for the command output
    @param command - command name
    @param args - command arguments
    @return int - Returns negative number to stop processing of commands
    */
    int commandEntered(Writer writer, String command, String[] args) throws IllegalArgumentException;

    default boolean useProxyForced(String[] args)
    {
        if (UtilityFunctions.isEmpty(args))
            return false;
        for (String arg: args)
        {
            if ("-p".equalsIgnoreCase(arg) || "-proxy".equalsIgnoreCase(arg))
                return true;
        }
        return false;
    }
}
