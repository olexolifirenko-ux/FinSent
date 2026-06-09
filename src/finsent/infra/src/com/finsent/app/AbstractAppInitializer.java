/*
 * Copyright (c) 2000-2001 InfoReach, Inc. All Rights Reserved.
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

package com.finsent.app;

import java.util.HashMap;
import java.util.Map;

import com.finsent.directory.DirectorySystem;
import com.finsent.util.BadConfigFileException;
import com.finsent.util.CmdArgParser;
import com.finsent.util.CmdInterpreter;
import com.finsent.util.GlobalDefs;
import com.finsent.util.GlobalSystem;
import com.finsent.util.UtilityFunctions;
import com.finsent.util.xml.XMLData;

/**
 * Initialization entity base.
 * Organizes application initialization in appropriate manner.
 *
 * <p>This is a trimmed variant of the full InfoReach {@code AbstractAppInitializer}:
 * it drives {@link GlobalSystem} initialization, starts the command interpreter
 * and registers the default uninitializer. The splash screen, security-service
 * login and back-test subsystems of the original (along with the view / event /
 * record-data / metadata hooks) are intentionally omitted, so it depends only on
 * {@code com.alex.*}.
 *
 * @author Andrey Trubka.
 */
public abstract class AbstractAppInitializer
{
    private CmdArgParser argParser_;

    /**
     * The only constructor, which is also the only method
     * that should be called to initialize application.
     * Other methods are called from this constructor.
     */
    public AbstractAppInitializer(String[] args) throws Exception
    {
        beforeInitialize();
        initialize(args);
    }

    protected void beforeInitialize()
    {

    }

    protected CmdArgParser getArgParser()
    {
        return argParser_;
    }

    /**
     * Creates argument parser for command line process parameters.
     */
    protected CmdArgParser createArgParser(String[] args) throws BadConfigFileException
    {
        CmdArgParser argParser = new CmdArgParser(args);
        Map<String, int[]> validOptions = createValidOptionsForArgParser();
        argParser.setValidOptionMetaData(validOptions, !isExtraCmdArgsAllowed());
        if (!argParser.isValid())
        {
            throw new BadConfigFileException(argParser.getErrorsString()+"\nUsage: java " + getClass().getName() +
                CmdArgParser.generateValidSyntax(validOptions));
        }
        if (argParser.isOptionSet("h"))
        {
            System.out.println("Usage: java " + getClass().getName() + CmdArgParser.generateValidSyntax(validOptions));
        }
        return argParser;
    }

    protected Map<String, int[]> createValidOptionsForArgParser()
    {
        Map<String, int[]> validOptions = new HashMap<>(5, 1);
        validOptions.put(GlobalDefs.CFG_BOOTSTRAP_DATA_FILE, new int[] { 1, 1 } );
        validOptions.put(GlobalDefs.CFG_TYPE, new int[] { 1, 1 } );
        validOptions.put(GlobalDefs.CFG_NAME, new int[] { 1, 0 } );
        validOptions.put("h", new int[] { 0, 0 } );
        return validOptions;
    }

    protected boolean isExtraCmdArgsAllowed()
    {
        return false;
    }

    /**
     * Main initialization code. Performs all subsystem initializations.
     */
    protected void initialize(String[] args) throws Exception
    {
        try
        {
            initializeGlobalSystem(args);
            GlobalSystem.getCmdInterpreter().start();
            initializeCmdInterpreter(GlobalSystem.getCmdInterpreter());
            GlobalSystem.invokeInitializersInThread();
            initializeAppSystems();
            registerDefaultUninitializer();
            GlobalSystem.callInitializers();
            System.out.println(GlobalSystem.getCmdInterpreter().getHelp());
        }
        catch (Exception ex)
        {
            handleError(ex);
            new Thread(() -> GlobalSystem.terminate(1)).start();
        }
    }

    protected void handleError(Exception e)
    {
        GlobalSystem.getLogFacility().error().write(e);
    }

    /**
     * Global system initialization.
     */
    protected void initializeGlobalSystem(String[] args) throws Exception
    {
        argParser_ = createArgParser(args);

        if (argParser_ != null)
        {
            initializeDirectory();

            XMLData bootstrapData = new XMLData(
                    UtilityFunctions.substituteEnvironmentVariables(
                            argParser_.getOptionValue(GlobalDefs.CFG_BOOTSTRAP_DATA_FILE),
                            "${", "}"));

            String processType = argParser_.getOptionValue(GlobalDefs.CFG_TYPE, null);
            String processName = argParser_.getOptionValue(GlobalDefs.CFG_NAME, null);

            GlobalSystem.initialize(bootstrapData, processType, processName, null);
        }
    }

    /**
     * Roots resource resolution at the release home ({@code -Dfinsent.home}, set
     * by the launchers) so that relative resource paths - the bootstrap file, the
     * GlobalSystem {@code configDataResource} and log {@code outputDestination}s -
     * resolve there regardless of the JVM's working directory. Mirrors the
     * master_AO behaviour of resolving against the Directory root. When the
     * property is absent (e.g. run outside the launchers) resolution falls back to
     * the working directory.
     */
    private void initializeDirectory()
    {
        String home = System.getProperty("finsent.home");
        if (home != null && !DirectorySystem.isInitialized())
        {
            DirectorySystem.initializeDefault(home);
        }
    }

    /**
     * Override this method to provide app specific
     * system initializations.
     */
    protected void initializeAppSystems()
        throws Exception
    {
    }

    /**
     * Initialize command interpreter with additional information.
     * Just override to add new commands.
     */
    protected void initializeCmdInterpreter(CmdInterpreter interpreter)
    {
        interpreter.setVersion(GlobalSystem.getVersion());
    }

    /**
     * Registers handler which will be called on shut down of process.
     */
    private void registerDefaultUninitializer()
    {
        GlobalSystem.registerUninitializer(AbstractAppInitializer.this::uninitialize);
    }

    /**
     * Called when exiting so it can be overridden to
     * uninitialize some specific things.
     */
    protected void uninitialize()
    {
    }
}
