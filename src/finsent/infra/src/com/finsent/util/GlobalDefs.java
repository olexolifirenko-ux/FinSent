package com.finsent.util;

public class GlobalDefs
{
    public static final String EOL_UNIX = "\n";
    public static final String EOL_DOS = "\r\n";
    public static final String EOL_MAC = "\r";
    public static final String EOL = System.getProperty("line.separator", EOL_UNIX);

    public static final String CFG_OUTPUT_LOG_FACILITY_LIST = "OutputLogFacilityList";
    public static final String CFG_OUTPUT_LOG_FACILITY      = "OutputLogFacility";
    public static final String CFG_LOGS_DIR                 = "LOGS_DIR"; //-DLOGS_DIR system property

    public static final String CFG_GLOBAL_SYSTEM            = "GlobalSystem";
    public static final String CFG_PROCESS_NAMES_DELIMITER  = ",";

    public static final String CFG_CONFIG_DATA_RESOURCE     = "configDataResource";
    public static final String CFG_LOG_FACILITY_NAME        = "logFacilityName";
    public static final String CFG_FULL_PROCESS_NAME        = "fullProcessName";

    public static final String CFG_CUSTOM_INITIALIZER_CLASS_NAME = "customInitializerClassName";
    public static final String CFG_CONFIGURABLE_INITIALIZER_LIST = "InitializerList";
    public static final String CFG_CONFIGURABLE_INITIALIZER      = "Initializer";
    public static final String CFG_CUSTOM_INITIALIZER_CONFIG_RES = "configResource";
    public static final String CFG_CUSTOM_INITIALIZER_IMMEDIATE  = "immediate";
    public static final String CFG_CUSTOM_INITIALIZER_ENABLED    = "enabled";

    public static final String CFG_NAME                          = "name";
    public static final String CFG_BOOTSTRAP_DATA_FILE           = "bootstrapDataFile";
    public static final String CFG_KEEP_JVM_RUNNING_AFTER_SHUTDOWN = "keepJVMRunningAfterShutdown";
    public static final String CFG_IMPLEMENTATION_CLASS          = "implementationClass";
    public static final String CFG_VALUE                         = "value";
    public static final String CFG_TYPE                          = "type";
    public static final String CFG_KIND                          = "kind";
    public static final String CFG_MODE                          = "mode";

    public static final String SUPPRESS_LOGGING_AT_SHUTDOWN = "suppressLoggingAtShutdown";

}
