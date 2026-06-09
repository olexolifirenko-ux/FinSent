/*
 * Copyright (c) 1997-2011 InfoReach, Inc. All Rights Reserved.
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
 * Interface for command handlers which knows everything about its registration
 *
 * @author Eugeny Schava
 */
public interface IAutonomicCmdHandler extends ICmdHandler
{
    String getCommand();

    String getDescription();

    String[] getCommandAliases();
}
