/*
 * Copyright (c) 1997-2002 InfoReach, Inc. All Rights Reserved.
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

/**
 * An inteface which produces an logging outpur to selected output destination.
 *
 * @author Oleg Minukhin
 */
public interface ILogger
{
    long NO_THREAD_ID = -1L;
    /** Minimum output level - printed with slol min, default and max */
    public static final int MIN_OUTPUT_LEVEL = 0;
    /** Default output level - printed with slol default and max */
    public static final int DEFAULT_OUTPUT_LEVEL = 1;
    /** Maximum output level - printed with slol max */
    public static final int MAX_OUTPUT_LEVEL = 2;

    public static final String PROP_NAME_MAX_LOG_RECORD_LENGTH = ILogger.class.getPackage().getName()
            + ".maxLogRecordLength";
    public static final int MAX_LOG_RECORD_LENGTH = Integer.getInteger(PROP_NAME_MAX_LOG_RECORD_LENGTH,
                                                                       15 * 1024 * 1024).intValue();

    /**
     * Sets a default output level for this logger.
     *
     * @param level an output level.
     */
    public void setOutputLevel(int level);

    /**
     * Returns a default output level for this logger.
     *
     * @return an output level.
     */
    public int  getOutputLevel();

    /**
     * Switches logger on or off.
     *
     * @param b <code>true</code> - to switch this logger on,
     *          <code>false</code> - to switch this logger off.
     */
    public void setEnabled(boolean b);

    /**
     * Checks whether this logger will produce an output.
     *
     * @return <code>true</code> - it will produce an output,
     *         <code>false</code> - it won't produce an output.
     */
    public boolean isEnabled();

    /**
     * Writes data.
     *
     * @param source an object which initiates an output.
     * @param outputLevel an output level on which it will produce an output.
     * @param data data to write.
     * @param alsoOutputToStandardOutput copy a produced output to <code>System.out</code>.
     */
    public void write(Object source, int outputLevel, Object data,
                      boolean alsoOutputToStandardOutput);

    /**
     * Writes data.
     *
     * @param source an object which initiates an output.
     * @param data data to write.
     * @param alsoOutputToStandardOutput copy a produced output to <code>System.out</code>.
     */
    public void write(Object source, Object data,
                      boolean alsoOutputToStandardOutput);

    /**
     * Writes data.
     *
     * @param source an object which initiates an output.
     * @param outputLevel an output level on which it will produce an output.
     * @param data data to write.
     */
    public void write(Object source, int outputLevel, Object data);

    /**
     * Writes data.
     *
     * @param source an object which initiates an output.
     * @param data data to write.
     */
    public void write(Object source, Object data);

    /**
     * Writes data with source and data as varargs (in order to avoid verbose array creation).
     * This is more convenient alternative to
     * <pre>{@code
     * GlobalSystem.getLogFacility().info().write(getName(), new Object[] { "Some Message ", exc });
     * }</pre>
     * which can be rewritten as
     * <pre>{@code
     * GlobalSystem.getLogFacility().info().writes(getName(), "Some Message ", exc);
     * }</pre>
     *
     * @param source an object which initiates an output.
     * @param data data to write.
     */
    default void writes(Object source, Object... data)
    {
        write(source, data);
    }

    /**
     * Writes data.
     *
     * @param outputLevel an output level on which it will produce an output.
     * @param data data to write.
     * @param alsoOutputToStandardOutput copy a produced output to <code>System.out</code>.
     */
    public void write(int outputLevel, Object data,
                      boolean alsoOutputToStandardOutput);

    /**
     * Writes data.
     *
     * @param data data to write.
     * @param alsoOutputToStandardOutput copy a produced output to <code>System.out</code>.
     */
    public void write(Object data, boolean alsoOutputToStandardOutput);

    /**
     * Writes data.
     *
     * @param outputLevel an output level on which it will produce an output.
     * @param data data to write.
     */
    public void write(int outputLevel, Object data);
    /**
     * Writes data.
     *
     * @param data data to write.
     */
    public void write(Object data);
    /**
     * Writes data, with timestamp
     *
     * @param data data to write.
     * @param dateWithMicros to use for log entry.
     */
    void write(Object data, double dateWithMicros);

    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param source an object which initiates an output.
     * @param outputLevel an output level on which it will produce an output.
     * @param formatStr a formating string.
     * @param params arguments for formating.
     * @param alsoOutputToStandardOutput copy a produced output to <code>System.out</code>.
     *
     * @see java.text.MessageFormat
     */
    public void writef(Object source, int outputLevel, String formatStr,
                       Object[] params, boolean alsoOutputToStandardOutput);

    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param source an object which initiates an output.
     * @param formatStr a formating string.
     * @param params arguments for formating.
     * @param alsoOutputToStandardOutput copy a produced output to <code>System.out</code>.
     *
     * @see java.text.MessageFormat
     */
    public void writef(Object source, String formatStr,
                       Object[] params, boolean alsoOutputToStandardOutput);
    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param source an object which initiates an output.
     * @param outputLevel an output level on which it will produce an output.
     * @param formatStr a formating string.
     * @param params arguments for formating.
     *
     * @see java.text.MessageFormat
     */
    public void writef(Object source, int outputLevel, String formatStr,
                       Object[] params);

    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param source an object which initiates an output.
     * @param formatStr a formating string.
     * @param params arguments for formating.
     *
     * @see java.text.MessageFormat
     */
    public void writef(Object source, String formatStr, Object[] params);

    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param outputLevel an output level on which it will produce an output.
     * @param formatStr a formating string.
     * @param params arguments for formating.
     * @param alsoOutputToStandardOutput copy a produced output to <code>System.out</code>.
     *
     * @see java.text.MessageFormat
     */
    public void writef(int outputLevel, String formatStr,
                       Object params[], boolean alsoOutputToStandardOutput);
    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param formatStr a formating string.
     * @param params arguments for formating.
     * @param alsoOutputToStandardOutput copy a produced output to <code>System.out</code>.
     *
     * @see java.text.MessageFormat
     */
    public void writef(String formatStr, Object params[],
                       boolean alsoOutputToStandardOutput);

    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param formatStr a formating string.
     * @param params arguments for formating.
     *
     * @see java.text.MessageFormat
     */
    public void writef(String formatStr, Object params[]);

    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param outputLevel an output level on which it will produce an output.
     * @param formatStr a formating string.
     * @param params arguments for formating.
     *
     * @see java.text.MessageFormat
     */
    public void writef(int outputLevel, String formatStr, Object params[]);

    /**
     * Writes data.
     *
     * @param source an object which initiates an output.
     * @param outputLevel an output level on which it will produce an output.
     * @param data data to write.
     * @param alsoToStandardOutput copy a produced output to <code>System.out</code>.
     * @param delayDataConversionToString if set to true, conversion of data to
     * string can take place on a separate thread.
     */
    public void writeAdv(
        Object source,
        int outputLevel,
        Object data,
        boolean alsoToStandardOutput,
        boolean delayDataConversionToString);

    /**
     * Writes data formatted through <code>java.text.MessageFormat</code>.
     *
     * @param source an object which initiates an output.
     * @param outputLevel an output level on which it will produce an output.
     * @param formatStr a formating string.
     * @param params arguments for formating.
     * @param alsoToStandardOutput copy a produced output to <code>System.out</code>.
     * @param delayDataConversionToString if set to true, conversion of data to
     * string can take place on a separate thread.
     */
    public void writeAdv(
        Object source,
        int outputLevel,
        String formatStr,
        Object[] params,
        boolean alsoToStandardOutput,
        boolean delayDataConversionToString);

    /**
     * Sends all queued messages to output
     * Method returns only after there are no messages in queue
     */
    public void flush();

    public default boolean isLogRollingEnabled()
    {
        return false;
    }

    public default String getLogFilePath()
    {
        return null;
    }

    TimeLapseMode getTimeLapseMode();

    enum TimeLapseMode
    {
        none  (0),
        micros(12),
        nanos (15);

        private String format_;

        TimeLapseMode(int digits)
        {
            if (digits > 0)
            {
                format_ = "%" + digits + "d";
            }
        }

        public String format(long nanosTimeLapse)
        {
            if (format_ != null)
            {
                if (this == micros)
                {
                    nanosTimeLapse /= 1000L;
                }
                return String.format(format_, nanosTimeLapse);
            }
            return null;
        }
    }
    
    default String getThreadName(final Thread thread)
    {
        return thread.getName();
    }

    default long getThreadId(final Thread thread)
    {
        return thread.getId();
    }
}

