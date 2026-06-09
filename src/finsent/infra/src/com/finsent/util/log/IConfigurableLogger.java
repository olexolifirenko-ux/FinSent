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

import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;

import com.finsent.util.xml.XMLData;

/**
 * An interface for implementors of {@link ILogger} that can be created and
 * configured by {@link LogFacility} from XML configuration.
 *
 * <p>This is a trimmed variant of the InfoReach {@code IConfigurableLogger}: the
 * JMS / event-service publishing hooks ({@code setTopicArguments},
 * {@code initializePublishers}) and the pluggable {@code ITimeService} of the
 * original are intentionally omitted, so it depends only on {@code com.finsent.*}.
 *
 * @author Oleg Minukhin
 */
public interface IConfigurableLogger extends ILogger
{
    void initialize(String fileName) throws IOException;

    void initialize(PrintStream out);

    void configure(XMLData configInfo, String loggerType);

    /**
     * Sets a prefix to visually distinguish a produced output.
     *
     * @param prefix a prefix string for output, null means no prefix.
     */
    public void setPrefix(String prefix);

    /**
     * Sets queued output on or off.
     *
     * @param b <code>true</code> - to queue the output,
     *          <code>false</code> - not to queue the output.
     */
    public void setQueuedOutputEnabled(boolean b);

    /**
     * @param b <code>true</code> - to copy to standard output,
     *          <code>false</code> - not to copy to standard output.
     */
    public void setAlsoToStandardOutput(boolean b);

    /**
     * @param b <code>true</code> - to copy to standard error,
     *          <code>false</code> - not to copy to standard error.
     */
    public void setAlsoToStandardError(boolean b);

    /**
     * Enable or disable an output of time stamp.
     *
     * @param b <code>true</code> - to write time stamp,
     *          <code>false</code> - not to write time stamp.
     */
    public void setTimeStampEnabled(boolean b);

    /**
     * Set a time stamp format.
     *
     * @param format format object for timestamp.
     *
     * @see java.text.SimpleDateFormat
     */
    public void setTimeStampFormat(DateFormat format);

    /**
     * Enable or disable an output of thread information.
     *
     * @param b <code>true</code> - to write thread information,
     *          <code>false</code> - not to write thread information.
     */
    public void setThreadInfoEnabled(boolean b);

    /**
     * Disable any logger output. Even exception output must be avoided.
     * Is used for silent process shutdown.
     *
     * @see LogFacility#startGlobalUninitialize
     */
    public void suppressLogging();

    void setTimeLapseMode(TimeLapseMode mode);
}
