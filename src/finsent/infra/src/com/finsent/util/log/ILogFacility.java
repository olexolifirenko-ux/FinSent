/*
 * Copyright (c) 1997-2001 InfoReach, Inc. All Rights Reserved.
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

package com.finsent.util.log;

import com.finsent.util.GlobalDefs;

/**
 * Interface provides facility to get <code>ILogger</code> which logs debug,
 * information, warning or error output.
 *
 * @author Oleg Minukhin
 */
public interface ILogFacility
{
    public static final String TOPICTYPE_LOG_FACILITY       = "logFacility";
    public static final String CFG_DEFAULT_LOGGER           = "DefaultLogger";
    public static final String CFG_DEBUG_LOGGER             = "DebugLogger";
    public static final String CFG_INFO_LOGGER              = "InfoLogger";
    public static final String CFG_WARNING_LOGGER           = "WarningLogger";
    public static final String CFG_ERROR_LOGGER             = "ErrorLogger";
    public static final String CFG_PREFIX                   = "prefix";
    public static final String CFG_PROCESS_ALIAS            = "processAlias";
    public static final String CFG_IMPLEMENTATION_CLASS     = GlobalDefs.CFG_IMPLEMENTATION_CLASS;
    public static final String CFG_OUTPUT_DESTINATION       = "outputDestination";
    public static final String CFG_ALSO_TO_STANDARD_OUT     = "alsoToStandardOutput";
    public static final String CFG_ALSO_TO_STANDARD_ERR     = "alsoToStandardError";
    public static final String CFG_ENABLED                  = "enabled";
    public static final String CFG_OUTPUT_LEVEL             = "outputLevel";
    public static final String CFG_TIME_STAMP_ENABLED       = "timeStampEnabled";
    public static final String CFG_TIME_STAMP_FORMAT        = "timeStampFormat";
    public static final String CFG_TIME_STAMP_TIMEZONE      = "timeStampTimeZone";
    public static final String CFG_THREAD_INFO_ENABLED      = "threadInfoEnabled";
    public static final String CFG_PUBLISH_TO_EVENT_SERVICE = "publishToEventService";
    public static final String CFG_USE_COLORS               = "useColors";
    public static final String CFG_FOREGROUND_COLOR         = "foregroundColor";

    /**
     * Returns its name.
     *
     * @return a name.
     */
    public String getName();

    /**
     * Returns <code>ILogger</code> class which produces debug output.
     *
     * @return an instance of <code>ILogger</code>.
     */
    public ILogger debug();

    /**
     * Returns <code>ILogger</code> class which produces information output.
     *
     * @return an instance of <code>ILogger</code>.
     */
    public ILogger info();

    /**
     * Returns <code>ILogger</code> class which produces warning output.
     *
     * @return an instance of <code>ILogger</code>.
     */
    public ILogger warning();

    /**
     * Returns <code>ILogger</code> class which produces error output.
     *
     * @return an instance of <code>ILogger</code>.
     */
    public ILogger error();

    public/*nodoc*/ void initializePublishers();

    /**
     * Flushes all loggers
     */
    public void flush();

    /**
     * Notifies facility about start of GlobalSystem uninitialize process. 
     */
    public void startGlobalUninitialize();
    
    public void uninitialize();
}
