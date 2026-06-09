/*
 * Copyright (c) 2003 InfoReach, Inc. All Rights Reserved.
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
import java.io.PrintWriter;
import java.util.*;

/**
 * Command handler that contains a few subcommands inside.
 *
 * @author Andrey Trubka.
 */
public class CmdGroupHandler implements ICmdHandler, ICmdRegistry
{
    protected static class CommandContainer
    {
        private String command_;
        private ICmdHandler handler_;
        private String description_;
        private String[] aliases_;

        public CommandContainer(
            String command, ICmdHandler handler, String description, String[] aliases)
        {
            if (command == null)
                throw new IllegalArgumentException("Command name cannot be null");
            if (handler == null)
                throw new IllegalArgumentException("Command handler cannot be null");

            command_ = command;
            handler_ = handler;
            description_ = description;
            aliases_ = aliases;
        }

        public String getCommandName()
        {
            return command_;
        }

        public ICmdHandler getCommandHandler()
        {
            return handler_;
        }

        public String getDescription()
        {
            return description_;
        }

        public String[] getAliases()
        {
            return aliases_;
        }
    }

    private static final int MINIMUM_REQUIRED_PARAMETER_COUNT = 1;
    private static final int SUB_COMMAND_PARAMETER_INDEX = 0;

    protected Map commandNameToCommandContainer_ = new LinkedHashMap();
    protected HashMap aliasesToCommands_ = new HashMap();
    protected Map hiddendCommands_ = new LinkedHashMap();

    public CmdGroupHandler()
    {
        this.registerCmdHandler("?*?", new HelpPrinter(), "Shows help for hidden commands.", null);
    }

    public final void registerCmdHandler(IAutonomicCmdHandler cmdHandler)
    {
        registerCmdHandler(cmdHandler.getCommand(), cmdHandler, cmdHandler.getDescription(), cmdHandler.getCommandAliases());
    }

    public void registerCmdHandler(
        String command,
        ICmdHandler handler,
        String description,
        String[] aliases)
    {
        CommandContainer commandContainer =
            new CommandContainer(command, handler, description, aliases);
        associateCommand(command, commandContainer);
        if (aliases != null)
        {
            int count = aliases.length;
            for (int i = 0; i < count; i++)
            {
                associateCommand(aliases[i], commandContainer);
            }
        }
        if (isCommandHandlerHidden(handler))
        {
            hiddendCommands_.put(command, commandContainer);
        }
        else
        {
            commandNameToCommandContainer_.put(command, commandContainer);
        }
    }

/** @todo 2 */
    public void unregisterCmdHandler(String command)
    {
        CommandContainer cmdContainer = (CommandContainer)hiddendCommands_.get(command);
        if (null != cmdContainer)
        {
            hiddendCommands_.remove(command);
        }
        else
        {
            cmdContainer = (CommandContainer)commandNameToCommandContainer_.remove(command);
        }

        if (cmdContainer != null)
        {
            removeCommandAssociation(cmdContainer.getCommandName());
            String[] aliases = cmdContainer.getAliases();
            if (aliases != null)
            {
                int count = aliases.length;
                for (int i = 0; i < count; i++)
                {
                    removeCommandAssociation(aliases[i]);
                }
            }
        }
    }

    protected synchronized void associateCommand(String key, CommandContainer cmd)
    {
        String ignoreCaseKey = key.toLowerCase();
        if (!aliasesToCommands_.containsKey(ignoreCaseKey))
        {
            aliasesToCommands_.put(ignoreCaseKey, cmd);
        }
        else
        {
            throw new IllegalArgumentException(
                "Alias or command name " + key + " is already registered");
        }
    }

    protected synchronized void removeCommandAssociation(String key)
    {
        String ignoreCaseKey = key.toLowerCase();
        aliasesToCommands_.remove(ignoreCaseKey);
    }

    protected int getCommandCount()
    {
        return commandNameToCommandContainer_.size() + hiddendCommands_.size();
    }

    public ICmdHandler getCmdHandler(String commandName)
    {
        CommandContainer container = getCommand(commandName);
        return container != null ? container.getCommandHandler() : null;
    }

    protected CommandContainer getCommand(String commandName)
    {
        return (CommandContainer)aliasesToCommands_.get(commandName.toLowerCase());
    }

    public int commandEntered(Writer writer, String command, String[] args) throws IllegalArgumentException
    {
        try
        {
            int paramCount = args.length;

            if (paramCount >= MINIMUM_REQUIRED_PARAMETER_COUNT)
            {
                String commandName = args[SUB_COMMAND_PARAMETER_INDEX];
                CommandContainer cmd = getCommand(commandName);
                if (cmd != null)
                {
                    int subParamCount = paramCount-1;
                    String[] subArgs = new String[subParamCount];
                    if (subParamCount > 0)
                    {
                        System.arraycopy(args, 1, subArgs, 0, subParamCount);
                    }
                    cmd.getCommandHandler().commandEntered(writer, commandName, subArgs);
                }
                else
                {
                    UtilityFunctions.writeln(writer, "Unknown sub-command " + commandName);
                }
            }
            else
            {
                printUsage(writer);
            }
        }
        catch (Exception ex)
        {
            UtilityFunctions.writeln(writer, "Couldn't perform the command due to exception:\n");
            ex.printStackTrace(new PrintWriter(writer, true));
        }
        return 0;
    }

    protected void printUsage(Writer writer)
    {
        UtilityFunctions.writeln(writer, "Usage:");
        for (Iterator i = commandNameToCommandContainer_.values().iterator(); i.hasNext(); )
        {
            CommandContainer cmd = (CommandContainer)i.next();
            printCommandUsage(writer, cmd);
        }
    }

    protected void printCommandUsage(Writer writer, CommandContainer cmd)
    {
        char carriageReturn = '\n';
        String stringToReplace = "\n\t\t";
        String description = cmd.getDescription();
        StringBuffer buffer = new StringBuffer();
        if (null != description)
        {
            buffer.append(description);
            for (int index = description.lastIndexOf(carriageReturn, description.length());
                 index >= 0;
                 index = description.lastIndexOf(carriageReturn, index - 1))
            {
                buffer.replace(index, index + 1, stringToReplace);
            }
        }

        StringBuffer commandBuffer = new StringBuffer();
        commandBuffer.append(cmd.getCommandName());
        String[] aliases = cmd.getAliases();
        if (aliases != null)
        {
            int aliasCount = aliases.length;
            for (int j = 0; j < aliasCount; j++)
            {
                commandBuffer.append(", ");
                commandBuffer.append(aliases[j]);
            }
        }

        UtilityFunctions.writeln(writer,
            "\t" + commandBuffer.toString() + stringToReplace + buffer.toString());
    }
    protected boolean isCommandHidden(CommandContainer cmd)
    {
        ICmdHandler cmdHandler = cmd.getCommandHandler();
        return (isCommandHandlerHidden(cmdHandler));
    }

    protected boolean isCommandHandlerHidden(ICmdHandler cmd)
    {return (cmd instanceof IHiddenCmdHandler);}

    private class HelpPrinter implements IHiddenCmdHandler
    {
        public int commandEntered(Writer writer, String command, String[] args1)
        {
            Iterator hiddenCmds = hiddendCommands_.values().iterator();
            while(hiddenCmds.hasNext())
            {
                printCommandUsage(writer, (CommandContainer) hiddenCmds.next());
            }
            return 0;
        }
    }
}
