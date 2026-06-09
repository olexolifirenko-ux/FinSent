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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This class provides generic command parsing: it reads command lines from an
 * input stream, looks up the registered {@link ICmdHandler} and dispatches to
 * it.
 *
 * <p>This is a trimmed variant of the full InfoReach {@code CmdInterpreter},
 * carrying the command registry, the read/dispatch loop, help generation and
 * the built-in {@code exit}/{@code help}/{@code version} commands. The script
 * engines (BeanShell/Groovy), command history, external-tool and remote
 * (AppAdmin/RMI) command groups of the original are intentionally omitted.
 *
 * @author Alexander Lozitsky
 */
public class CmdInterpreter implements Runnable, ICmdRegistry
{
    public static final String DISABLE_CMD_INTERPERTER_VAR = "disableCmdInterpreter";

    static boolean disableCmdInterpreter_ =
        System.getProperty(DISABLE_CMD_INTERPERTER_VAR, "false").equalsIgnoreCase("true");

    /** Input stream of commands. */
    private final Reader reader_;
    /** Stream of output data. */
    private final Writer writer_;

    /** Registered commands, keyed by lower-cased command name (insertion-ordered for help). */
    private final Map<String, Command> commandsMap_ = new LinkedHashMap<>();
    /** Aliases mapped to their owning command, keyed by lower-cased alias. */
    private final Map<String, Command> commandAliasesMap_ = new HashMap<>();

    /** Thread that interprets command input from user; null when running inline. */
    private final Thread eventLoopThread_;

    /** Version for commands. */
    private Version version_;

    private volatile boolean running_;
    private volatile boolean runFlag_;

    /**
     * Sets input stream to standard input and output to standard output.
     * Starts a separate reader thread on {@link #start()}.
     */
    public CmdInterpreter()
    {
        this(true);
    }

    /**
     * Sets input stream to standard input and output to standard output.
     * @param useSeparateThread if true the interpreter uses a separate thread
     * for reading input and executing commands
     */
    public CmdInterpreter(boolean useSeparateThread)
    {
        this(new InputStreamReader(System.in), useSeparateThread);
    }

    /**
     * Sets output stream to standard output.
     * @param reader used to read commands
     * @param useSeparateThread if true the interpreter uses a separate thread
     */
    public CmdInterpreter(Reader reader, boolean useSeparateThread)
    {
        this(reader, new OutputStreamWriter(System.out), useSeparateThread);
    }

    /**
     * @param reader used to read commands
     * @param writer used to print output of commands
     * @param useSeparateThread if true the interpreter uses a separate thread
     */
    public CmdInterpreter(Reader reader, Writer writer, boolean useSeparateThread)
    {
        reader_ = reader;
        writer_ = writer;
        eventLoopThread_ = useSeparateThread ? newEventLoopThread() : null;

        registerCmdHandler("exit", new ExitCmdHandler(), "Exits the process.", new String[] { "quit" });
        registerCmdHandler("help", new HelpCmdHandler(), "Shows this help.", new String[] { "?" });
        registerCmdHandler("version", new VersionCmdHandler(), "Shows the product version.", new String[] { "ver" });
        registerDebugGroupCmdHandler();
    }

    private void registerDebugGroupCmdHandler()
    {
        DebugGroupCmdHandler cmdHandler = DebugGroupCmdHandler.getDefaultInstance();

        registerCmdHandler(DebugGroupCmdHandler.COMMAND,
                cmdHandler,
                DebugGroupCmdHandler.DESCRIPTION,
                DebugGroupCmdHandler.COMMAND_ALIASES);
    }

    private Thread newEventLoopThread()
    {
        Thread thread = new Thread(this, "Command interpreter thread");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Returns the stream of output data.
     */
    public synchronized Writer getWriter()
    {
        return writer_;
    }

    /**
     * Returns help for commands.
     */
    public synchronized String getHelp()
    {
        return generateHelp();
    }

    /**
     * Sets the version for commands.
     */
    public synchronized void setVersion(Version version)
    {
        version_ = version;
    }

    /**
     * Returns the version for commands.
     */
    public synchronized Version getVersion()
    {
        return (version_ == null) ? GlobalSystem.getVersion() : version_;
    }

    public synchronized boolean isUseSeparateThread()
    {
        return eventLoopThread_ != null;
    }

    @Override
    public final void registerCmdHandler(IAutonomicCmdHandler cmdHandler)
    {
        registerCmdHandler(cmdHandler.getCommand(), cmdHandler, cmdHandler.getDescription(), cmdHandler.getCommandAliases());
    }

    public synchronized void registerCmdHandler(String command, ICmdHandler handler)
    {
        registerCmdHandler(command, handler, "", null);
    }

    public synchronized void registerCmdHandler(String command, ICmdHandler handler, String[] aliases)
    {
        registerCmdHandler(command, handler, "", aliases);
    }

    public synchronized void registerCmdHandler(String command, ICmdHandler handler, String description)
    {
        registerCmdHandler(command, handler, description, null);
    }

    @Override
    public synchronized void registerCmdHandler(String command, ICmdHandler handler, String description, String[] aliases)
    {
        if (command == null || command.length() == 0)
        {
            throw new IllegalArgumentException("Command can not be null or empty string");
        }
        unregisterCmdHandler(command);
        Command newCommand = new Command(command, handler, description, aliases);
        commandsMap_.put(key(command), newCommand);
        if (aliases != null)
        {
            for (String alias : aliases)
            {
                commandAliasesMap_.put(key(alias), newCommand);
            }
        }
    }

    @Override
    public synchronized void unregisterCmdHandler(String command)
    {
        if (command != null)
        {
            Command oldCommand = commandsMap_.remove(key(command));
            if (oldCommand != null && oldCommand.getAliases() != null)
            {
                for (String alias : oldCommand.getAliases())
                {
                    commandAliasesMap_.remove(key(alias));
                }
            }
        }
    }

    @Override
    public ICmdHandler getCmdHandler(String commandName)
    {
        Command command = getCommand(commandName);
        return command != null ? command.getHandler() : null;
    }

    /**
     * Returns the command by name or alias.
     */
    public synchronized Command getCommand(String commandOrAlias)
    {
        Command command = commandsMap_.get(key(commandOrAlias));
        if (command == null)
        {
            command = commandAliasesMap_.get(key(commandOrAlias));
        }
        return command;
    }

    /**
     * Starts the interpreter.
     */
    public synchronized void start()
    {
        running_ = true;
        runFlag_ = true;
        if (eventLoopThread_ == null)
            run();
        else
            eventLoopThread_.start();
    }

    /**
     * Returns true if the interpreter is started.
     */
    public synchronized boolean isStarted()
    {
        return running_ && runFlag_;
    }

    /**
     * Reads command lines from the input and dispatches them until the input is
     * exhausted or the interpreter is stopped. Disabled (no-op) when stdin is
     * unavailable -- controlled by the {@value #DISABLE_CMD_INTERPERTER_VAR} JVM
     * property (e.g. when launched via javaw/jlaunch).
     */
    @Override
    public void run()
    {
        if (disableCmdInterpreter_)
            return;

        BufferedReader reader = new BufferedReader(reader_);
        if (isUseSeparateThread())
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);

        try
        {
            while (running_ && runFlag_)
            {
                String line = reader.readLine();
                if (line == null)
                {
                    running_ = false;
                }
                else if (line.length() > 0)
                {
                    interpretLine(line);
                }
            }
        }
        catch (IOException ex)
        {
            println("Error by interpreter\n");
            GlobalSystem.getLogFacility().error().write(ex);
        }
    }

    public void interpretLine(String line)
    {
        try
        {
            interpretLine(writer_, line);
        }
        catch (Exception ex)
        {
            println("Exception by interpreter\n");
            GlobalSystem.getLogFacility().error().write(ex);
        }
    }

    public void interpretLine(Writer writer, String line) throws IOException
    {
        interpretLine(writer, line, false);
    }

    /**
     * Interprets a single command line: tokenizes it (honouring single/double
     * quotes), looks up the registered handler and invokes it.
     *
     * @param runInCurrentThread kept for source compatibility; the trimmed
     *        interpreter always runs the handler in the calling thread
     */
    public void interpretLine(Writer writer, String line, boolean runInCurrentThread) throws IOException
    {
        if (writer == null)
            writer = writer_;

        String[] tokens = tokenize(line);
        if (tokens.length == 0)
            return;

        String cmd = tokens[0];
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);

        Command command = getCommand(cmd);
        if (command != null)
        {
            ICmdHandler handler = command.getHandler();
            if (handler != null)
                handler.commandEntered(writer, cmd, args);
        }
        else
        {
            RuntimeException exception = new RuntimeException("Illegal command: '" + cmd + "'.");
            UtilityFunctions.writeln(writer, exception.getMessage());
            UtilityFunctions.writeln(writer, getHelp());
            throw exception;
        }
    }

    public void println(String message)
    {
        UtilityFunctions.writeln(writer_, message);
    }

    private synchronized String generateHelp()
    {
        StringBuilder sb = new StringBuilder("Available commands:\n");
        for (Command command : commandsMap_.values())
        {
            sb.append('\t').append(command.getName());
            String[] aliases = command.getAliases();
            if (aliases != null)
            {
                for (String alias : aliases)
                {
                    sb.append(", ").append(alias);
                }
            }
            String description = command.getDescription();
            if (description != null && description.length() > 0)
            {
                sb.append("\n\t\t").append(description);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Splits a command line into tokens on whitespace, treating text inside
     * single or double quotes as a single token.
     */
    private static String[] tokenize(String line)
    {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (quote != 0)
            {
                if (c == quote)
                    quote = 0;
                else
                    current.append(c);
            }
            else if (c == '"' || c == '\'')
            {
                quote = c;
                inToken = true;
            }
            else if (Character.isWhitespace(c))
            {
                if (inToken)
                {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            }
            else
            {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken)
            tokens.add(current.toString());
        return tokens.toArray(new String[0]);
    }

    private static String key(String name)
    {
        return name.toLowerCase();
    }

    /**
     * A registered command: its name, handler, description and aliases.
     */
    public static class Command
    {
        private final String name_;
        private final ICmdHandler handler_;
        private final String description_;
        private final String[] aliases_;

        Command(String name, ICmdHandler handler, String description, String[] aliases)
        {
            name_ = name;
            handler_ = handler;
            description_ = description;
            aliases_ = aliases;
        }

        public String getName()
        {
            return name_;
        }

        public ICmdHandler getHandler()
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

    private class ExitCmdHandler implements ICmdHandler
    {
        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            UtilityFunctions.writeln(writer, "Exiting...");
            running_ = false;
            runFlag_ = false;
            GlobalSystem.terminate(0);
            return -1;
        }
    }

    private class HelpCmdHandler implements ICmdHandler
    {
        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            UtilityFunctions.writeln(writer, getHelp());
            return 0;
        }
    }

    private class VersionCmdHandler implements ICmdHandler
    {
        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            UtilityFunctions.writeln(writer, String.valueOf(getVersion()));
            return 0;
        }
    }
}
