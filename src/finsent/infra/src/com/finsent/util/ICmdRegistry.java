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

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public interface ICmdRegistry
{
    ICmdRegistry NOP = new ICmdRegistry()
    {
        @Override
        public void registerCmdHandler(String command, ICmdHandler handler, String description, String[] aliases)
        {
        }

        @Override
        public void unregisterCmdHandler(String command)
        {
        }

        @Override
        public ICmdHandler getCmdHandler(String commandName)
        {
            return null;
        }
    };

    void registerCmdHandler(String command, ICmdHandler handler, String description, String[] aliases);

    void unregisterCmdHandler(String command);

    @CheckForNull
    ICmdHandler getCmdHandler(String commandName);

    default void registerCmdHandler(final @Nonnull IAutonomicCmdHandler handler)
    {
        registerCmdHandler(handler.getCommand(), handler, handler.getDescription(), handler.getCommandAliases());
    }
}
