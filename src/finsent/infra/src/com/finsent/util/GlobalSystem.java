package com.finsent.util;

import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;

import com.finsent.util.log.ILogFacility;
import com.finsent.util.log.ILogger;
import com.finsent.util.log.LogFacilityManager;
import com.finsent.util.xml.XMLData;
import com.finsent.util.xml.XMLDataPathElement;

/**
 * GlobalSystem is a singleton holder for the common process-level resources and
 * facilities (log facility, configuration, command interpreter, mail sender)
 * and drives the process initialize/uninitialize lifecycle.
 *
 * <p>This is a trimmed variant of the full InfoReach {@code GlobalSystem},
 * keeping the command interpreter, shutdown hook, (un)initializer registries,
 * mail sender, config / bootstrap-config data and log facility. The JMS / RMI /
 * security / AppAdmin / directory / event / view subsystems of the original are
 * intentionally omitted so the class depends only on {@code com.alex.*}.
 *
 * @author Oleg Minukhin
 */
public class GlobalSystem
{
    private static ILogFacility LogFacility_;
    private static XMLData BootstrapConfigData_ = null;
    private static XMLData ConfigData_ = null;

    // command interpreter
    private static volatile CmdInterpreter Interpreter_;
    private static final AtomicReference<Thread> ShutdownHook_ = new AtomicReference<>();
    // list of registered uninitializers
    private static Vector<IUninitializer> Uninitializers_ = new Vector<>();
    // list of registered initializers
    private static final InitializerCollector Initializers_ = new InitializerCollector();

    private static IMailSender MailSender_;

    private static String ProcessType_ = "";
    private static String ProcessName_ = "";

    // timeout for uninitialization, seconds (process exit is forced when it elapses)
    private static boolean KeepJVMRunningAfterShutdown_ = false;

    volatile static boolean IsInitialized_;
    private static boolean IsUnitializing_ = false;

    private static Version Version_;

    // ---- log facility --------------------------------------------------------

    public static void setLogFacility(ILogFacility logFacility)
    {
        LogFacility_ = logFacility;
    }

    /**
     * Returns ILogFacility default implementation.
     *
     * @return an default ILogFacility.
     *
     * @see ILogFacility
     */
    public static final ILogFacility getLogFacility()
    {
        return (LogFacility_ == null) ? LogFacilityManager.getDefaultLogFacility() : LogFacility_;
    }

    /**
     * Returns ILogFacility implementation by  name
     *
     * @param logFacilityName name of log facility
     * @return an ILogFacility with given name.
     *
     * @see ILogFacility
     */
    public static final ILogFacility getLogFacility(String logFacilityName)
    { return LogFacilityManager.getLogFacility(logFacilityName); }

    public static ILogger debug()
    {
        return getLogFacility().debug();
    }

    public static ILogger info()
    {
        return getLogFacility().info();
    }

    public static ILogger warning()
    {
        return getLogFacility().warning();
    }

    public static ILogger error()
    {
        return getLogFacility().error();
    }

    // ---- configuration -------------------------------------------------------
    public static final XMLData getBootstrapConfigData()
    {
        return BootstrapConfigData_;
    }

    public/*nodoc*/ static void setBootstrapConfigData(XMLData bootstrapConfigData)
    {
        BootstrapConfigData_ = bootstrapConfigData;
    }

    /**
     * Returns the process configuration data loaded from the resource named by
     * the {@code configDataResource} attribute of this process's bootstrap
     * {@code <GlobalSystem>} section, or null when none is configured.
     */
    public static final XMLData getConfigData()
    {
        return ConfigData_;
    }

    public/*nodoc*/ static void setConfigData(XMLData configData)
    {
        ConfigData_ = configData;
    }

    public static String getProcessType()
    {
        return ProcessType_;
    }

    public static String getProcessName()
    {
        return ProcessName_;
    }

    /**
     * Returns a human-readable handle for this process ({@code type} or
     * {@code type::name}), used e.g. in thread info of log output.
     */
    public static String getProcessHandle()
    {
        return getProcessTypeAndName();
    }

    // ---- mail sender ---------------------------------------------------------

    public static IMailSender getMailSender()
    {
        return MailSender_;
    }

    public static void setMailSender(IMailSender mailSender)
    {
        MailSender_ = mailSender;
    }

    // ---- command interpreter -------------------------------------------------

    /**
     * Retrieves the process command interpreter, creating it lazily.
     */
    public/*nodoc*/ static CmdInterpreter getCmdInterpreter()
    {
        // command interpreter should be ALWAYS created with a separate
        // event thread to avoid deadlocks
        if (Interpreter_ == null)
        {
            synchronized (GlobalSystem.class)
            {
                if (Interpreter_ == null)
                    Interpreter_ = new CmdInterpreter(true);
            }
        }
        return Interpreter_;
    }

    // ---- version -------------------------------------------------------------

    /**
     * Returns the version of this system, taken from the jar manifest's
     * Implementation-Version (or {@link Version#NULL} when running from classes).
     */
    public static final Version getVersion()
    {
        if (Version_ == null)
        {
            String impl = GlobalSystem.class.getPackage().getImplementationVersion();
            Version_ = (impl != null) ? new Version(impl, impl, "none", "") : Version.NULL;
        }
        return Version_;
    }

    // ---- lifecycle -----------------------------------------------------------

    /**
     * Indicates if GlobalSystem is already initialized.
     */
    public/*nodoc*/ static boolean isInitialized()
    {
        return IsInitialized_;
    }

    public/*nodoc*/ static boolean isUninitializing()
    {
        return IsUnitializing_;
    }

    /**
     * Indicates if the JVM should continue running after the application is terminated.
     */
    public/*nodoc*/ static boolean keepJVMRunningAfterShutdown()
    {
        return KeepJVMRunningAfterShutdown_;
    }

    public static synchronized final
    void initialize(XMLData bootstrapConfigData, String processType)
        throws BadConfigFileException
    { initialize(bootstrapConfigData, processType, null, null); }

    public static synchronized final
    void initialize(XMLData bootstrapConfigData, String processType, String processName)
        throws BadConfigFileException
    { initialize(bootstrapConfigData, processType, processName, null); }

    /**
     * Initializes the process with the given bootstrap configuration, process
     * type and name. Installs the JVM shutdown hook and runs the registered
     * initializers.
     *
     * @param bootstrapConfigData configuration properties
     * @param processType type of process
     * @param processName name of process
     * @param callbackHandler optional handler for progress / version callbacks
     */
    public static synchronized final
    void initialize(XMLData bootstrapConfigData, final String processType,
                    final String processName, IGlobalSystemCallbackHandler callbackHandler)
        throws BadConfigFileException
    {
        KeepJVMRunningAfterShutdown_ =
            Boolean.parseBoolean(System.getProperty(GlobalDefs.CFG_KEEP_JVM_RUNNING_AFTER_SHUTDOWN, "false"));
        if (BootstrapConfigData_ != null)
            return;

        ProcessType_ = processType == null ? "" : processType;
        ProcessName_ = processName == null ? "" : processName;

        // Expose the process handle as the fullProcessName property (unless already
        // provided, e.g. via -DfullProcessName) so log outputDestination templates
        // such as "%LOGS_DIR%/%fullProcessName%.log" resolve.
        if (System.getProperty(GlobalDefs.CFG_FULL_PROCESS_NAME) == null)
            System.setProperty(GlobalDefs.CFG_FULL_PROCESS_NAME, getProcessTypeAndName());

        // BootstrapConfigData_ holds the section of the bootstrap document that is
        // specific to this process type/name (extracted via getProcessData), not
        // the whole document - mirroring the master_AO GlobalSystem behaviour.
        BootstrapConfigData_ = getProcessData(bootstrapConfigData, ProcessType_, ProcessName_);
        if (BootstrapConfigData_ == null)
        {
            String message = "No bootstrap information is found for " + ProcessType_;
            if (!ProcessName_.isEmpty())
                message += "::" + ProcessName_;
            throw new BadConfigFileException(message);
        }

        if (callbackHandler != null)
        {
            callbackHandler.setVersion(getVersion().toString());
        }

        XMLData thisBootstrapData = getThisBootstrapData();

        // Load the process configuration referenced by the bootstrap and let the
        // log facility manager configure the declared facilities from it, then
        // make the bootstrap-named facility this process's default - so the
        // initializers and the rest of startup log through the configured facility.
        setConfigData(loadConfigData(getConfigFileName(thisBootstrapData)));
        LogFacilityManager.initializeGlobalSettings();
        initializeProcessLogFacility(thisBootstrapData);

        registerOrRunInitializers(thisBootstrapData);

        installShutdownHook();
        IsInitialized_ = true;

        getLogFacility().info().write("GlobalSystem",
            "GlobalSystem initialized (type=" + ProcessType_ + ", name=" + ProcessName_ + ")");
    }

    public static boolean terminate(int code)
    {
        uninitialize(code);
        if (!keepJVMRunningAfterShutdown())
        {
            System.exit(code);
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Uninitializes the system.
     */
    public static void uninitialize()
    {
        uninitialize(0);
    }

    public static void uninitialize(int haltCode)
    {
        // To avoid multiple uninitialization attempts: the first call drops the
        // initialized state, so subsequent calls (e.g. the shutdown hook firing
        // after terminate() called System.exit()) become no-ops.
        boolean shouldProcessUninitialize;
        synchronized (GlobalSystem.class)
        {
            shouldProcessUninitialize = isInitialized();
            IsInitialized_ = false;
        }
        if (shouldProcessUninitialize)
        {
            IsUnitializing_ = true;
            uninstallShutdownHook();
            logForcedly("Process is shutting down ...");
            processUninitializers();
        }
    }

    public/*nodoc*/ static void installShutdownHook()
    {
        try
        {
            synchronized (ShutdownHook_)
            {
                uninstallShutdownHook();
                final Thread newHook = new Thread("JVM shutdown hook")
                {
                    {
                        setDaemon(true);
                    }

                    @Override
                    public void run()
                    {
                        uninitialize(0);
                    }
                };
                ShutdownHook_.set(newHook);
                Runtime.getRuntime().addShutdownHook(newHook);
            }
        }
        catch (IllegalStateException ex)
        {
            // Just ignore it - it means that JVM is already shutting down.
            // See javadoc for Runtime#getRuntime()#addShutdownHook() method.
        }
        catch (Throwable ex)
        {
            warning().write(ex);
        }
    }

    private static void uninstallShutdownHook()
    {
        try
        {
            synchronized (ShutdownHook_)
            {
                final Thread oldHook = ShutdownHook_.getAndSet(null);
                if (oldHook != null)
                    Runtime.getRuntime().removeShutdownHook(oldHook);
            }
        }
        catch (IllegalStateException ex)
        {
            // Just ignore it - it means that JVM is already shutting down.
        }
        catch (SecurityException ex)
        {
            // ignore - not permitted to remove the hook
        }
    }

    // ---- (un)initializers ----------------------------------------------------

    /**
     * Registers an implementation of IUninitializer; uninitialize() will be
     * called on process shutdown.
     */
    static public/*nodoc*/ void registerUninitializer(IUninitializer uninitializer)
    {
        Uninitializers_.add(0, uninitializer);
    }

    static public/*nodoc*/ void unregisterUninitializer(IUninitializer uninitializer)
    {
        Uninitializers_.remove(uninitializer);
    }

    /**
     * Registers an implementation of IInitializer to be called on the
     * initialization stage.
     */
    static public/*nodoc*/ void registerInitializer(IInitializer initializer)
    {
        Initializers_.registerInitializer(initializer, 0);
    }

    /**
     * Registers an implementation of IInitializer to be called on the
     * initialization stage as late as possible.
     */
    static public/*nodoc*/ void registerLastInitializer(IInitializer initializer)
    {
        Initializers_.registerInitializer(initializer, Integer.MAX_VALUE);
    }

    public static void invokeInitializersInThread()
    {
        Initializers_.startInThreadInitializers();
    }

    public/*nodoc*/ static void callInitializers()
    {
        getLogFacility().debug().write("Calling registered initializers...");
        Initializers_.makeReady();
    }

    /**
     * Registers (or, for immediate ones, runs) the custom initializers declared
     * in this process's bootstrap section: the single {@code customInitializerClassName}
     * attribute and the {@code InitializerList}/{@code Initializer} elements.
     */
    public static void registerOrRunInitializers(XMLData thisBootstrapData)
    {
        String customInitializerClassName =
            thisBootstrapData.getAttributeStringValue(GlobalDefs.CFG_CUSTOM_INITIALIZER_CLASS_NAME, null);

        if (customInitializerClassName != null)
        {
            Object initializer = UtilityFunctions.instantiateObject(customInitializerClassName);
            if (initializer != null)
            {
                if (initializer instanceof IInitializer)
                {
                    registerInitializer((IInitializer) initializer);
                }
                else
                {
                    getLogFacility().error().write("Class specified for custom initializer does not implement required interface: " + IInitializer.class.getName());
                }
            }
            else
            {
                getLogFacility().error().write("Could not instantiate custom initializer.");
            }
        }

        XMLData initializerList = thisBootstrapData.getDocumentPart(GlobalDefs.CFG_CONFIGURABLE_INITIALIZER_LIST, true);
        if (initializerList != null)
        {
            XMLData[] configurableInitializers = initializerList.getChildrenByTagName(GlobalDefs.CFG_CONFIGURABLE_INITIALIZER);
            for (XMLData configurableInitializerData : configurableInitializers)
            {
                configurableInitializerData.setDefaultData(initializerList);
                String className =
                    configurableInitializerData.getAttributeStringValue(GlobalDefs.CFG_CUSTOM_INITIALIZER_CLASS_NAME, null);
                boolean immediate = configurableInitializerData.getAttributeBooleanValue(GlobalDefs.CFG_CUSTOM_INITIALIZER_IMMEDIATE, false);
                boolean enabled = configurableInitializerData.getAttributeBooleanValue(GlobalDefs.CFG_CUSTOM_INITIALIZER_ENABLED, true);
                if (className != null)
                {
                    // An empty class name is allowed (the bootstrap template may be
                    // left unparameterized for some deployments) - just skip it.
                    if (enabled && className.length() > 0)
                    {
                        Object initializer = UtilityFunctions.instantiateObject(className);
                        if (initializer != null)
                        {
                            if (initializer instanceof IInitializable)
                            {
                                String configResource = configurableInitializerData.getAttributeStringValue(GlobalDefs.CFG_CUSTOM_INITIALIZER_CONFIG_RES, null);
                                XMLData initializerConfigData = configResource != null
                                                                ? new XMLData(configResource)
                                                                : configurableInitializerData;
                                registerSafeInitializer(initializer, initializerConfigData, immediate);
                            }
                            else if (initializer instanceof IInitializer)
                            {
                                registerSafeInitializer(initializer, null, immediate);
                            }
                            else
                            {
                                getLogFacility().error().write("Class specified for custom initializer " + className +
                                    " does not implement required interface: " + IInitializable.class.getName() + " or " + IInitializer.class.getName());
                            }
                        }
                        else
                        {
                            getLogFacility().error().write("Could not instantiate custom initializer " + className);
                        }
                    }
                }
                else
                {
                    getLogFacility().error().write("Class specified for custom initializer must have " +
                        GlobalDefs.CFG_CUSTOM_INITIALIZER_CLASS_NAME + " attribute specified.");
                }
            }
        }
    }

    private static void registerSafeInitializer(Object initializer, XMLData configData, boolean immediate)
    {
        IInitializer adapter = initializer instanceof IInitializable
                               ? new SafeInitializableAdapter((IInitializable) initializer, configData)
                               : new SafeInitializerAdapter((IInitializer) initializer);
        Initializers_.registerInitializer(adapter, immediate ? InitializerCollector.IMMEDIATE : 1);
    }

    /**
     * Extracts the section of {@code superData} that is specific to the given
     * process type and name. Returns null if no such section is found.
     *
     * <p>The "default" section is the {@code <processType>} element without a
     * {@code name} attribute (or the first such element when none is unnamed);
     * a name-specific section overrides it. An {@code extend} attribute pulls in
     * another document part as default data.
     *
     * @author Konstantin Matokhin, paul.gerber
     */
    public/*nodoc*/ static XMLData getProcessData(XMLData superData, String processType, String processName)
    {
        XMLData unnamedSection = null;
        XMLData[] processesWithGivenType = superData.getChildrenByTagName(processType);
        for (XMLData data : processesWithGivenType)
        {
            if (data.getAttributeStringValue(GlobalDefs.CFG_NAME, null) == null)
            {
                unnamedSection = data;
                break;
            }
        }

        // Find the "default" section: the <processType> element without a "name"
        // attribute. When there is none, fall back to the first <processType> element.
        XMLData defaultSection = (unnamedSection != null)
            ? unnamedSection
            : (processesWithGivenType.length > 0 ? processesWithGivenType[0] : null);

        XMLData processData = defaultSection;

        if (!processName.isEmpty())
        {
            // First look for the <processType> with name="<processName>";
            // if not found, fall back to the "default" section.
            processData = getProcessDataBaseOnTheName(superData, processType, processName);
            if (processData == null)
            {
                processData = defaultSection;
            }
        }

        if (processData != null)
        {
            String extend = processData.getAttributeStringValue("extend", null);
            if (extend != null)
            {
                // master_AO resolves the non-full-path form via new XMLDataPath(extend);
                // that constructor is package-private to com.alex.util.xml, so the public
                // getDocumentPart(String, recursive) is used here instead.
                XMLData defaultData = extend.contains(XMLData.FULL_PATH_DELIMITER)
                                      ? XMLData.fromFullPath(extend)
                                      : superData.getDocumentPart(extend, true);

                if (defaultData != null)
                    processData.setDefaultData(defaultData);
                else
                    getLogFacility().error().write("Document part '" + extend + "' not found");
            }

            if (!processName.isEmpty())
                processData.setAttributeValue(GlobalDefs.CFG_NAME, processName);
        }

        return processData;
    }

    private static XMLData getProcessDataBaseOnTheName(XMLData superData, String pType, String pName)
    {
        XMLData[] processesWithGivenType = superData.getChildrenByTagName(pType);
        for (XMLData xml : processesWithGivenType)
        {
            String name = xml.getAttributeStringValue(GlobalDefs.CFG_NAME, null);
            if (name != null)
            {
                String[] processNames = UtilityFunctions.getTokens(name, GlobalDefs.CFG_PROCESS_NAMES_DELIMITER, true, false);
                if (UtilityFunctions.indexOfInArray(pName, processNames) >= 0)
                {
                    return xml;
                }
            }
        }
        return null;
    }

    private static XMLData getThisBootstrapData() throws BadConfigFileException
    {
        XMLData thisBootstrapData = BootstrapConfigData_.getDocumentPart(
            new XMLDataPathElement(GlobalDefs.CFG_GLOBAL_SYSTEM), false);

        if (thisBootstrapData == null)
            throw new BadConfigFileException("No " + GlobalDefs.CFG_GLOBAL_SYSTEM +
                " bootstrap information is found for " + getProcessTypeAndName() +
                " in " + BootstrapConfigData_.getLoadedResourcePath());

        return thisBootstrapData;
    }

    private static String getProcessTypeAndName()
    {
        return ProcessName_.isEmpty() ? ProcessType_ : ProcessType_ + "::" + ProcessName_;
    }

    // ---- config data / log facility wiring -----------------------------------

    /**
     * Returns the configuration resource named by the {@code configDataResource}
     * attribute of this process's bootstrap section, or null when not specified.
     */
    private static String getConfigFileName(XMLData thisBootstrapData)
    {
        return thisBootstrapData.getAttributeStringValue(GlobalDefs.CFG_CONFIG_DATA_RESOURCE, null);
    }

    /**
     * Loads the process configuration from the given resource. Returns null when
     * no configuration resource is configured (the log facility manager then
     * leaves the default facilities in place).
     */
    private static XMLData loadConfigData(String configDataFile)
    {
        XMLData configData = null;
        if (configDataFile != null && !configDataFile.isEmpty())
        {
            configData = new XMLData(configDataFile);
        }
        return configData;
    }

    /**
     * Makes the facility named by the bootstrap {@code logFacilityName} attribute
     * this process's default log facility. When the attribute is absent the
     * previously installed (or built-in default) facility is kept.
     */
    private static void initializeProcessLogFacility(XMLData thisBootstrapData)
    {
        String logFacilityName =
            thisBootstrapData.getAttributeStringValue(GlobalDefs.CFG_LOG_FACILITY_NAME, null);
        if (logFacilityName != null && !logFacilityName.isEmpty())
        {
            setLogFacility(LogFacilityManager.getLogFacility(logFacilityName));
        }
    }

    // processes all registered uninitializers
    private static void processUninitializers()
    {
        logForcedly("Uninitialization...");
        Object[] uninitializers = Uninitializers_.toArray();
        for (Object o : uninitializers)
        {
            final IUninitializer uninitializer = (IUninitializer) o;
            try
            {
                uninitializer.uninitialize();
            }
            catch (Throwable ex)
            {
                getLogFacility().warning().write(ex);
            }
        }
        Uninitializers_.removeAllElements();
    }

    // Forced logging to stdout, used during shutdown when the log facility may
    // already be uninitialized.
    private static void logForcedly(String message)
    {
        System.out.println(message);
    }
}
