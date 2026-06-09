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

import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import com.finsent.util.GlobalDefs;
import com.finsent.util.UtilityFunctions;
import com.finsent.util.xml.XMLData;

/**
 * A configuration-driven implementation of {@link ILogFacility}. A facility owns
 * four loggers (debug / info / warning / error); each is described by a
 * {@code DebugLogger} / {@code InfoLogger} / {@code WarningLogger} /
 * {@code ErrorLogger} child of the {@code OutputLogFacility} configuration
 * element, falling back to a configured {@code DefaultLogger} and finally to a
 * standard console logger. Error and warning loggers default to {@code System.err}.
 *
 * <p>This is a trimmed port of the InfoReach {@code LogFacility}: it reproduces the
 * logger fall-back chain, the per-logger configuration and the
 * {@code outputDestination} reification ({@code %VAR%} substitution and the
 * {@code ::}&rarr;{@code _} replacement), but omits the JMS / event-service
 * publishing, back-test hooks and the start-up diagnostic prints of the
 * original, so it depends only on {@code com.finsent.*}. (Daily rolling of the
 * output file is handled by {@link Logger}.)
 *
 * @author Oleg Minukhin
 */
public class LogFacility implements ILogFacility
{
    public static final String DEFAULT_NAME = "";
    protected static final String DEFAULT_IMPLEMENTATION_CLASS = Logger.class.getName();

    private String name_;
    private final boolean suppressLoggingAtShutdown_;

    protected IConfigurableLogger debugLogger_;
    protected IConfigurableLogger infoLogger_;
    protected IConfigurableLogger warningLogger_;
    protected IConfigurableLogger errorLogger_;

    /**
     * Creates a facility from its {@code OutputLogFacility} configuration element
     * (or with all loggers going to the console when {@code configInfo} is null).
     */
    public LogFacility(XMLData configInfo, boolean suppressLoggingAtShutdown)
    {
        IConfigurableLogger standardLogger = createLogger(null);
        standardLogger.setOutputLevel(ILogger.MAX_OUTPUT_LEVEL);

        if (configInfo == null)
        {
            name_ = DEFAULT_NAME;
            suppressLoggingAtShutdown_ = suppressLoggingAtShutdown;

            debugLogger_ = standardLogger;
            infoLogger_ = standardLogger;
            warningLogger_ = standardLogger;
            errorLogger_ = createLogger(null, System.err);
            if (errorLogger_ == null)
            {
                errorLogger_ = standardLogger;
            }
        }
        else
        {
            name_ = configInfo.getAttributeStringValue(GlobalDefs.CFG_NAME, DEFAULT_NAME);
            suppressLoggingAtShutdown_ =
                configInfo.getAttributeBooleanValue(GlobalDefs.SUPPRESS_LOGGING_AT_SHUTDOWN, suppressLoggingAtShutdown);

            IConfigurableLogger defaultLogger = configureLogger(configInfo, CFG_DEFAULT_LOGGER, standardLogger, System.out);
            if (defaultLogger == null)
            {
                defaultLogger = standardLogger;
            }

            debugLogger_ = resolveLogger(configInfo, CFG_DEBUG_LOGGER, defaultLogger, standardLogger, System.out);
            infoLogger_ = resolveLogger(configInfo, CFG_INFO_LOGGER, defaultLogger, standardLogger, System.out);
            warningLogger_ = resolveLogger(configInfo, CFG_WARNING_LOGGER, defaultLogger, standardLogger, System.err);
            errorLogger_ = resolveLogger(configInfo, CFG_ERROR_LOGGER, defaultLogger, standardLogger, System.err);
        }
    }

    /**
     * Resolves a logger of the given type: uses its configuration element if
     * present (falling back to the standard logger when it can not be created),
     * otherwise the facility's default logger.
     */
    private IConfigurableLogger resolveLogger(XMLData facilityConfig, String loggerType,
        IConfigurableLogger defaultLogger, IConfigurableLogger standardLogger, PrintStream outStream)
    {
        IConfigurableLogger logger = configureLogger(facilityConfig, loggerType, standardLogger, outStream);
        if (logger == null)
        {
            logger = defaultLogger;
        }
        return logger;
    }

    /**
     * Creates and configures the logger described by {@code loggerType} under the
     * facility configuration, or returns null when no such element is declared.
     * When the element is present but the logger can not be instantiated the
     * standard logger is returned.
     */
    private IConfigurableLogger configureLogger(XMLData facilityConfig, String loggerType,
        IConfigurableLogger standardLogger, PrintStream outStream)
    {
        IConfigurableLogger logger = null;
        XMLData loggerConfigInfo = getDocumentPartWithDefaultData(facilityConfig, loggerType);
        if (loggerConfigInfo != null)
        {
            logger = createLogger(loggerConfigInfo, outStream);
            if (logger == null)
            {
                logger = standardLogger;
            }
            else
            {
                configLoggerFromXmlData(facilityConfig, logger, loggerConfigInfo, loggerType);
            }
        }
        return logger;
    }

    protected XMLData getDocumentPartWithDefaultData(XMLData configInfo, String tag)
    {
        XMLData result = configInfo.getDocumentPart(tag, true);
        if (result != null)
        {
            result.setDefaultData(configInfo);
        }
        return result;
    }

    protected IConfigurableLogger createLogger(XMLData configInfo)
    {
        return createLogger(configInfo, System.out);
    }

    /**
     * Creates a logger of the configured implementation class (defaulting to
     * {@link Logger}), pointed either at the configured {@code outputDestination}
     * file or at {@code outStream}.
     */
    protected IConfigurableLogger createLogger(XMLData configInfo, PrintStream outStream)
    {
        String implementationClass = DEFAULT_IMPLEMENTATION_CLASS;
        String outputDestination = null;
        if (configInfo != null)
        {
            implementationClass = configInfo.getAttributeStringValue(CFG_IMPLEMENTATION_CLASS, DEFAULT_IMPLEMENTATION_CLASS);
            outputDestination = configInfo.getAttributeStringValue(CFG_OUTPUT_DESTINATION, null);
        }

        IConfigurableLogger logger;
        try
        {
            logger = (IConfigurableLogger) Class.forName(implementationClass).getConstructor().newInstance();
            if (outputDestination == null)
            {
                logger.initialize(outStream);
            }
            else
            {
                logger.initialize(reifyOutputDestination(outputDestination));
            }
        }
        catch (Exception ex)
        {
            logger = null;
            System.err.println("Cannot instantiate a logger class: " + implementationClass +
                " for output: " + outputDestination);
            ex.printStackTrace(System.err);
        }
        return logger;
    }

    /**
     * Resolves an {@code outputDestination} template: {@code %VAR%} placeholders
     * (e.g. {@code %LOGS_DIR%}, {@code %fullProcessName%}) are substituted from
     * system properties / environment, then {@code ::} is replaced with {@code _}.
     */
    public static String reifyOutputDestination(String outputDestination) throws java.text.ParseException
    {
        outputDestination = UtilityFunctions.substituteEnvironmentVariables(outputDestination);
        outputDestination = outputDestination.replace("::", "_");
        return outputDestination;
    }

    protected void configLoggerFromXmlData(XMLData facilityConfigInfo,
        IConfigurableLogger logger, XMLData configInfo, String loggerType)
    {
        LogFacilityManager.propagateCommonConfig(facilityConfigInfo, configInfo);
        logger.configure(configInfo, loggerType);
        logger.setTimeLapseMode(getTimeLapseMode(configInfo.getAttributeStringValue(LogFacilityManager.TIME_LAPSE_ATTR, null)));
    }

    private ILogger.TimeLapseMode getTimeLapseMode(String mode)
    {
        ILogger.TimeLapseMode result = ILogger.TimeLapseMode.none;
        if (mode != null)
        {
            try
            {
                result = ILogger.TimeLapseMode.valueOf(mode);
            }
            catch (IllegalArgumentException ex)
            {
                result = ILogger.TimeLapseMode.none;
            }
        }
        return result;
    }

    /**
     * Builds a date format for the given timestamp pattern and time zone. Runs of
     * more than three {@code 'S'} (sub-millisecond digits) are rendered as zeros,
     * since the system clock is millisecond precision - this reproduces the
     * InfoReach default {@code .SSSSSS} field as {@code <millis>000}.
     */
    public static DateFormat newDateFormat(String timeStamp, String timeZone)
    {
        SimpleDateFormat result = null;
        if (timeStamp != null)
        {
            result = new SimpleDateFormat(toMillisecondPattern(timeStamp));
            TimeZone tz = (timeZone != null) ? TimeZone.getTimeZone(timeZone) : TimeZone.getDefault();
            result.getCalendar().setTimeZone(tz);
        }
        return result;
    }

    private static String toMillisecondPattern(String pattern)
    {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pattern.length())
        {
            char ch = pattern.charAt(i);
            if (ch == 'S')
            {
                int j = i;
                while (j < pattern.length() && pattern.charAt(j) == 'S')
                {
                    j++;
                }
                appendSubSecondField(out, j - i);
                i = j;
            }
            else
            {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    private static void appendSubSecondField(StringBuilder out, int count)
    {
        if (count > 3)
        {
            out.append("SSS").append('\'');
            for (int k = 0; k < count - 3; k++)
            {
                out.append('0');
            }
            out.append('\'');
        }
        else
        {
            for (int k = 0; k < count; k++)
            {
                out.append('S');
            }
        }
    }

    // ---- ILogFacility --------------------------------------------------------

    @Override
    public String getName()
    {
        return name_;
    }

    @Override
    public ILogger debug()
    {
        return debugLogger_;
    }

    @Override
    public ILogger info()
    {
        return infoLogger_;
    }

    @Override
    public ILogger warning()
    {
        return warningLogger_;
    }

    @Override
    public ILogger error()
    {
        return errorLogger_;
    }

    @Override
    public void initializePublishers()
    {
        // no-op: JMS / event-service publishing is not supported in this variant
    }

    @Override
    public void startGlobalUninitialize()
    {
        if (suppressLoggingAtShutdown_)
        {
            debugLogger_.suppressLogging();
            infoLogger_.suppressLogging();
            warningLogger_.suppressLogging();
            errorLogger_.suppressLogging();
        }
    }

    @Override
    public void uninitialize()
    {
        // no-op: no JMS session to close in this variant
    }

    @Override
    public void flush()
    {
        for (ILogger logger : new ILogger[] {debugLogger_, infoLogger_, warningLogger_, errorLogger_})
        {
            logger.flush();
        }
    }
}
