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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;

import com.finsent.util.GlobalSystem;
import com.finsent.util.xml.XMLData;

/**
 * Generic class for logging output. Logging output can be filtered through an
 * instance of this class instead of using stdout directly; it supports a per
 * output prefix, on/off switching, output-level filtering, an optional timestamp
 * and thread info, output to a file (append) and/or the console, and queued
 * (asynchronous) output on a single shared background thread.
 *
 * <p>This is a trimmed port of the InfoReach {@code Logger}: it reproduces the
 * configuration semantics, output rendering and the queued-output behaviour
 * (including the low/high watermark overflow handling) of the original, but
 * omits the subsystems the rest of FinSent does not depend on - JMS / event
 * service publishing, console colors, back-test hooks and the sub-microsecond
 * time service. The system clock is millisecond precision, so the default
 * {@code .SSSSSS} microsecond field always renders as {@code <millis>000} -
 * matching the original's output for a millisecond clock.
 *
 * <p>A file-backed logger rolls its output daily: it writes to
 * {@code <base>.<yyyy-MM-dd>.<ext>} and switches to a new dated file when the
 * local calendar day changes (judged by each entry's own timestamp), so a
 * non-stop process yields one file per day. Console loggers do not roll.
 *
 * @author VS
 * @author Oleg Minukhin
 */
public class Logger implements IConfigurableLogger
{
    public static final String DEFAULT_TIME_STAMP_FORMAT = "MMM d HH:mm:ss.SSSSSS Z";
    private static final String DAY_STAMP_FORMAT = "yyyy-MM-dd";

    private static final boolean DisableConsoleOutput_ = Boolean.getBoolean("DisableConsoleOutput");
    private static final String THREAD_ID_DELIMITER = "#";

    // ---- shared asynchronous output queue ------------------------------------

    private static OutputQueue Queue_;
    private static int QueueUsageCounter_;
    private static int LowWatermark_;
    private static int HiWatermark_;

    // ---- instance state ------------------------------------------------------

    private String prefix_;
    private String processAlias_;
    private boolean enabled_ = true;
    private int outputLevel_ = DEFAULT_OUTPUT_LEVEL;
    private int levelForExceptionStackTrace_ = 0;

    private boolean timeStampEnabled_ = true;
    private DateFormat dateFormatter_ = LogFacility.newDateFormat(DEFAULT_TIME_STAMP_FORMAT, null);
    private boolean threadInfoEnabled_ = true;
    private boolean queuedOutputEnabled_;
    private boolean alsoToStandardOutput_;
    private boolean alsoToStandardError_;
    private TimeLapseMode timeLapseMode_ = TimeLapseMode.none;

    private PrintWriter writer_;
    private boolean usingSystemOut_;
    private boolean usingSystemErr_;

    // Daily rolling state for a file-backed logger (null base => console logger, no rolling).
    private String baseFilePath_;
    private String currentLogDate_;
    private final DateFormat dayFormatter_ = LogFacility.newDateFormat(DAY_STAMP_FORMAT, null);

    public Logger()
    {
    }

    @Override
    public void initialize(String fileName) throws IOException
    {
        baseFilePath_ = fileName;
        currentLogDate_ = dayFormatter_.format(new Date());
        openDatedWriter(currentLogDate_);
    }

    /** (Re)opens {@link #writer_} on the dated file for {@code date} (append mode), creating dirs. */
    private void openDatedWriter(String date) throws IOException
    {
        String dated = datedFileName(baseFilePath_, date);
        File parent = new File(dated).getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists())
        {
            parent.mkdirs();
        }
        writer_ = new PrintWriter(new BufferedWriter(new FileWriter(dated, true)), false);
    }

    /**
     * Inserts {@code date} before the file extension: {@code .../FSSatellite.log} with
     * {@code 2026-06-07} becomes {@code .../FSSatellite.2026-06-07.log}. A path with no extension
     * gets the date appended.
     */
    static String datedFileName(String basePath, String date)
    {
        int slash = Math.max(basePath.lastIndexOf('/'), basePath.lastIndexOf('\\'));
        int dot = basePath.lastIndexOf('.');
        String result = basePath + "." + date;
        if (dot > slash)
        {
            result = basePath.substring(0, dot) + "." + date + basePath.substring(dot);
        }
        return result;
    }

    /** Switches to a new dated file when {@code timeMillis} falls on a different day than the open one. */
    synchronized void rollIfDayChanged(long timeMillis)
    {
        if (baseFilePath_ != null)
        {
            String day = dayFormatter_.format(new Date(timeMillis));
            if (!day.equals(currentLogDate_))
            {
                rollTo(day);
            }
        }
    }

    private void rollTo(String day)
    {
        PrintWriter previous = writer_;
        try
        {
            openDatedWriter(day);
            currentLogDate_ = day;
            previous.flush();
            previous.close();
        }
        catch (IOException cannotOpen)
        {
            // Keep the previous day's file rather than lose output; the next entry retries the roll.
            writer_ = previous;
            System.err.println("Cannot roll log file for " + day + ": " + cannotOpen);
        }
    }

    @Override
    public void initialize(PrintStream out)
    {
        usingSystemOut_ = (out == System.out);
        usingSystemErr_ = (out == System.err);
        writer_ = new PrintWriter(new BufferedWriter(new OutputStreamWriter(out)), true);
    }

    @Override
    public void configure(XMLData configInfo, String loggerType)
    {
        setQueuedOutputEnabled(true);
        setProcessAlias(configInfo.getAttributeStringValue(ILogFacility.CFG_PROCESS_ALIAS, null));
        setPrefix(configInfo.getAttributeStringValue(ILogFacility.CFG_PREFIX, null));
        setEnabled(configInfo.getAttributeBooleanValue(ILogFacility.CFG_ENABLED, true));
        setAlsoToStandardOutput(
            configInfo.getAttributeBooleanValue(ILogFacility.CFG_ALSO_TO_STANDARD_OUT, false) && !DisableConsoleOutput_);
        setAlsoToStandardError(
            configInfo.getAttributeBooleanValue(ILogFacility.CFG_ALSO_TO_STANDARD_ERR, false) && !DisableConsoleOutput_);
        setOutputLevel(
            configInfo.getAttributeIntValue(ILogFacility.CFG_OUTPUT_LEVEL, ILogger.DEFAULT_OUTPUT_LEVEL));
        setTimeStampEnabled(configInfo.getAttributeBooleanValue(ILogFacility.CFG_TIME_STAMP_ENABLED, false));
        setTimeStampFormat(
            LogFacility.newDateFormat(
                configInfo.getAttributeStringValue(ILogFacility.CFG_TIME_STAMP_FORMAT, DEFAULT_TIME_STAMP_FORMAT),
                configInfo.getAttributeStringValue(ILogFacility.CFG_TIME_STAMP_TIMEZONE, null)));
        setThreadInfoEnabled(configInfo.getAttributeBooleanValue(ILogFacility.CFG_THREAD_INFO_ENABLED, false));
    }

    // ---- mutators ------------------------------------------------------------

    @Override
    public synchronized void setPrefix(String prefix)
    {
        prefix_ = prefix;
    }

    private synchronized void setProcessAlias(String alias)
    {
        processAlias_ = alias;
    }

    @Override
    public synchronized void setOutputLevel(int level)
    {
        if (level < MIN_OUTPUT_LEVEL)
            level = MIN_OUTPUT_LEVEL;
        else if (level > MAX_OUTPUT_LEVEL)
            level = MAX_OUTPUT_LEVEL;
        outputLevel_ = level;
    }

    @Override
    public synchronized void setEnabled(boolean state)
    {
        enabled_ = state;
    }

    @Override
    public synchronized void setQueuedOutputEnabled(boolean value)
    {
        if (queuedOutputEnabled_ != value)
        {
            queuedOutputEnabled_ = value;
            useEventQueue(value);
        }
    }

    @Override
    public synchronized void setAlsoToStandardOutput(boolean value)
    {
        alsoToStandardOutput_ = value;
    }

    @Override
    public synchronized void setAlsoToStandardError(boolean value)
    {
        alsoToStandardError_ = value;
    }

    @Override
    public synchronized void setTimeStampEnabled(boolean value)
    {
        timeStampEnabled_ = value;
    }

    @Override
    public synchronized void setTimeStampFormat(DateFormat format)
    {
        if (format != null)
        {
            dateFormatter_ = format;
        }
    }

    @Override
    public synchronized void setThreadInfoEnabled(boolean value)
    {
        threadInfoEnabled_ = value;
    }

    @Override
    public void setTimeLapseMode(TimeLapseMode timeLapseMode)
    {
        timeLapseMode_ = timeLapseMode;
    }

    @Override
    public TimeLapseMode getTimeLapseMode()
    {
        return timeLapseMode_;
    }

    @Override
    public synchronized void suppressLogging()
    {
        setEnabled(false);
        levelForExceptionStackTrace_ = Integer.MAX_VALUE;
    }

    public static synchronized void setQueueWatermarks(int low, int hi)
    {
        LowWatermark_ = low;
        HiWatermark_ = hi;
        if (Queue_ != null)
        {
            Queue_.setWatermarks(low, hi);
        }
    }

    // ---- accessors -----------------------------------------------------------

    @Override
    public boolean isEnabled()
    {
        return enabled_;
    }

    @Override
    public final int getOutputLevel()
    {
        return isEnabled() ? outputLevel_ : -1;
    }

    @Override
    public boolean isLogRollingEnabled()
    {
        return baseFilePath_ != null;
    }

    @Override
    public String getLogFilePath()
    {
        return baseFilePath_ == null ? null : datedFileName(baseFilePath_, currentLogDate_);
    }

    // ---- core write path -----------------------------------------------------

    @Override
    public final synchronized void writeAdv(
        Object source, int outputLevel, Object data,
        boolean alsoToStandardOutput, boolean delayDataConversionToString)
    {
        if ((isEnabled() && (outputLevel_ >= outputLevel) && (data != null)) ||
            ((data instanceof Throwable) && (outputLevel_ >= levelForExceptionStackTrace_)))
        {
            writeForcedly(source, data, alsoToStandardOutput);
        }
    }

    @Override
    public final void writeAdv(
        Object source, int outputLevel, String formatStr, Object[] params,
        boolean alsoToStandardOutput, boolean delayDataConversionToString)
    {
        writeAdv(source, outputLevel, MessageFormat.format(formatStr, params), alsoToStandardOutput, false);
    }

    private synchronized void writeForcedly(Object source, Object data, boolean alsoToStandardOutput)
    {
        StringBuilder body = new StringBuilder();
        String dataSource = (source == null) ? null : String.valueOf(source);
        if (dataSource != null)
        {
            body.append(dataSource).append(": ");
        }
        dataToString(body, data);

        Thread currentThread = Thread.currentThread();
        OutputRecord record = new OutputRecord(
            this,
            body.toString(),
            System.currentTimeMillis(),
            timeStampEnabled_,
            threadInfoEnabled_ ? getThreadName(currentThread) : null,
            threadInfoEnabled_ ? getThreadId(currentThread) : NO_THREAD_ID,
            alsoToStandardOutput,
            alsoToStandardError_);

        if (queuedOutputEnabled_ && (Queue_ != null) && (currentThread != Queue_.getThread()))
        {
            Queue_.post(record);
        }
        else
        {
            printRecord(record);
        }
    }

    // ---- ILogger write overloads (delegate to the core path) -----------------

    @Override public final synchronized void write(Object source, int outputLevel, Object data, boolean alsoToStandardOutput)
    { writeAdv(source, outputLevel, data, alsoToStandardOutput, false); }

    @Override public final void write(Object source, Object data, boolean alsoToStandardOutput)
    { write(source, DEFAULT_OUTPUT_LEVEL, data, alsoToStandardOutput); }

    @Override public final void write(Object source, int outputLevel, Object data)
    { write(source, outputLevel, data, alsoToStandardOutput_); }

    @Override public final void write(Object source, Object data)
    { write(source, DEFAULT_OUTPUT_LEVEL, data, alsoToStandardOutput_); }

    @Override public final void write(int outputLevel, Object data, boolean alsoToStandardOutput)
    { write(null, outputLevel, data, alsoToStandardOutput); }

    @Override public final void write(Object data, boolean alsoToStandardOutput)
    { write(null, DEFAULT_OUTPUT_LEVEL, data, alsoToStandardOutput); }

    @Override public final void write(int outputLevel, Object data)
    { write(null, outputLevel, data, alsoToStandardOutput_); }

    @Override public final void write(Object data)
    { write(null, DEFAULT_OUTPUT_LEVEL, data, alsoToStandardOutput_); }

    @Override public final void write(Object data, double dateWithMicros)
    { write(null, DEFAULT_OUTPUT_LEVEL, data, alsoToStandardOutput_); }

    @Override public final void writef(Object source, int outputLevel, String formatStr, Object[] params, boolean alsoToStandardOutput)
    { write(source, outputLevel, MessageFormat.format(formatStr, params), alsoToStandardOutput); }

    @Override public final void writef(Object source, String formatStr, Object[] params, boolean alsoToStandardOutput)
    { writef(source, DEFAULT_OUTPUT_LEVEL, formatStr, params, alsoToStandardOutput); }

    @Override public final void writef(Object source, int outputLevel, String formatStr, Object[] params)
    { writef(source, outputLevel, formatStr, params, alsoToStandardOutput_); }

    @Override public final void writef(Object source, String formatStr, Object[] params)
    { writef(source, DEFAULT_OUTPUT_LEVEL, formatStr, params, alsoToStandardOutput_); }

    @Override public final void writef(int outputLevel, String formatStr, Object[] params, boolean alsoToStandardOutput)
    { writef(null, outputLevel, formatStr, params, alsoToStandardOutput); }

    @Override public final void writef(String formatStr, Object[] params, boolean alsoToStandardOutput)
    { writef(null, DEFAULT_OUTPUT_LEVEL, formatStr, params, alsoToStandardOutput); }

    @Override public final void writef(String formatStr, Object[] params)
    { writef(null, DEFAULT_OUTPUT_LEVEL, formatStr, params, alsoToStandardOutput_); }

    @Override public final void writef(int outputLevel, String formatStr, Object[] params)
    { writef(null, outputLevel, formatStr, params, alsoToStandardOutput_); }

    @Override
    public void flush()
    {
        if (Queue_ != null)
        {
            Queue_.flush();
        }
        if (writer_ != null)
        {
            writer_.flush();
        }
    }

    // ---- rendering -----------------------------------------------------------

    void printRecord(OutputRecord record)
    {
        if (record.skippedMessageCount_ != 0)
        {
            printLine(record, record.skippedMessageCount_ + " messages were skipped due to logger queue overflow");
        }
        printLine(record, record.body_);
    }

    private synchronized void printLine(OutputRecord record, String message)
    {
        rollIfDayChanged(record.timeMillis_);

        StringBuilder buffer = new StringBuilder();
        if (prefix_ != null)
            buffer.append(prefix_);

        if (record.timeStampEnabled_)
        {
            buffer.append('[').append(getTimestamp(record.timeMillis_)).append("] ");
        }

        if (record.threadName_ != null)
        {
            String process = (processAlias_ != null) ? processAlias_ : GlobalSystem.getProcessHandle();
            buffer.append('[').append(process).append("::").append(record.threadName_);
            buffer.append(THREAD_ID_DELIMITER).append(record.threadId_).append("] ");
        }

        buffer.append(message);
        String outputString = buffer.toString();

        writer_.println(outputString);
        writer_.flush();

        if (record.alsoToStandardOutput_ && !usingSystemOut_)
        {
            System.out.println(outputString);
            System.out.flush();
        }
        if (record.alsoToStandardError_ && !usingSystemErr_)
        {
            System.err.println(outputString);
            System.err.flush();
        }
    }

    private String getTimestamp(long timeMillis)
    {
        synchronized (dateFormatter_)
        {
            return dateFormatter_.format(new Date(timeMillis));
        }
    }

    private void dataToString(StringBuilder buffer, Object data)
    {
        if (data == null)
        {
            buffer.append((String) null);
        }
        else if (data.getClass().isArray())
        {
            if (data instanceof Object[])
            {
                for (Object object : (Object[]) data)
                {
                    dataToString(buffer, object);
                }
            }
            else
            {
                String s = Arrays.deepToString(new Object[]{data});
                buffer.append(s, 1, s.length() - 1); // remove the extra surrounding []
            }
        }
        else if (data instanceof Throwable)
        {
            buffer.append(exceptionToString((Throwable) data));
        }
        else
        {
            buffer.append(data);
        }
    }

    private String exceptionToString(Throwable t)
    {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (outputLevel_ >= levelForExceptionStackTrace_)
        {
            t.printStackTrace(pw);
        }
        else
        {
            pw.println(t);
        }
        pw.flush();
        return sw.toString();
    }

    // ---- shared queue lifecycle ----------------------------------------------

    private static synchronized void useEventQueue(boolean flag)
    {
        if (flag)
        {
            if (Queue_ == null)
            {
                Queue_ = new OutputQueue();
                Queue_.setWatermarks(LowWatermark_, HiWatermark_);
                Queue_.start();
            }
            QueueUsageCounter_++;
        }
        else if (QueueUsageCounter_ > 0)
        {
            QueueUsageCounter_--;
            if (QueueUsageCounter_ == 0 && Queue_ != null)
            {
                Queue_.stopDispatching();
                Queue_ = null;
            }
        }
    }

    public static Thread getEventQueueThread()
    {
        return Queue_ == null ? null : Queue_.getThread();
    }

    // ---- inner types ---------------------------------------------------------

    /**
     * A captured log entry. {@code source}/{@code data} are converted to a string
     * on the calling thread (so the rendered text reflects the moment of the call),
     * while the timestamp, prefix and thread info are applied when the entry is
     * printed.
     */
    static final class OutputRecord
    {
        final Logger logger_;
        final String body_;
        final long timeMillis_;
        final boolean timeStampEnabled_;
        final String threadName_;
        final long threadId_;
        final boolean alsoToStandardOutput_;
        final boolean alsoToStandardError_;
        int skippedMessageCount_;

        OutputRecord(Logger logger, String body, long timeMillis, boolean timeStampEnabled,
                     String threadName, long threadId, boolean alsoToStandardOutput, boolean alsoToStandardError)
        {
            logger_ = logger;
            body_ = body;
            timeMillis_ = timeMillis;
            timeStampEnabled_ = timeStampEnabled;
            threadName_ = threadName;
            threadId_ = threadId;
            alsoToStandardOutput_ = alsoToStandardOutput;
            alsoToStandardError_ = alsoToStandardError;
        }
    }

    /**
     * A single shared daemon-thread queue that renders and writes log entries off
     * the calling thread. When the backlog reaches the high watermark it enters an
     * overflow-reducing mode that drops the oldest entries, accumulating their
     * count onto the next entry (reported as "N messages were skipped ..."); it
     * leaves that mode once the backlog drains back to the low watermark.
     */
    private static final class OutputQueue implements Runnable
    {
        private final ArrayDeque<OutputRecord> queue_ = new ArrayDeque<>();
        private final Thread thread_ = new Thread(this, "Logger Output");
        private boolean running_ = true;
        private boolean overflowReducingMode_;
        private int lowWatermark_;
        private int hiWatermark_;

        OutputQueue()
        {
            thread_.setDaemon(true);
        }

        void start()
        {
            thread_.start();
        }

        Thread getThread()
        {
            return thread_;
        }

        synchronized void setWatermarks(int low, int hi)
        {
            lowWatermark_ = low;
            hiWatermark_ = hi;
            if (overflowReducingMode_ && queue_.size() <= lowWatermark_)
            {
                overflowReducingMode_ = false;
            }
        }

        synchronized void post(OutputRecord record)
        {
            handlePossibleOverflow();
            queue_.addLast(record);
            notifyAll();
        }

        private void handlePossibleOverflow()
        {
            if (overflowReducingMode_)
            {
                OutputRecord removed = queue_.pollFirst();
                OutputRecord oldest = queue_.peekFirst();
                if (oldest != null)
                {
                    oldest.skippedMessageCount_ += removed.skippedMessageCount_ + 1;
                }
            }
            else if (lowWatermark_ > 0 && hiWatermark_ > 0 && queue_.size() + 1 >= hiWatermark_)
            {
                overflowReducingMode_ = true;
            }
        }

        private synchronized OutputRecord take() throws InterruptedException
        {
            while (running_ && queue_.isEmpty())
            {
                wait();
            }
            OutputRecord record = null;
            if (!queue_.isEmpty())
            {
                if (overflowReducingMode_ && queue_.size() - 1 <= lowWatermark_)
                {
                    overflowReducingMode_ = false;
                }
                record = queue_.pollFirst();
                if (queue_.isEmpty())
                {
                    notifyAll();
                }
            }
            return record;
        }

        void flush()
        {
            synchronized (this)
            {
                while (!queue_.isEmpty())
                {
                    try
                    {
                        wait();
                    }
                    catch (InterruptedException ex)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        synchronized void stopDispatching()
        {
            running_ = false;
            notifyAll();
        }

        @Override
        public void run()
        {
            while (running_)
            {
                OutputRecord record = null;
                try
                {
                    record = take();
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    running_ = false;
                }
                if (record != null)
                {
                    record.logger_.printRecord(record);
                }
            }
        }
    }
}
