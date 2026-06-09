/*
 * Copyright (c) 1997-2013 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */
package com.finsent.util.diagnostics;

import com.finsent.util.GlobalDefs;
import com.finsent.util.UtilityFunctions;

public class ThreadUtils
{

    public static String getThreadStackTrace(Thread thread)
    {
        StringBuilder buffer = new StringBuilder(1000);
        buffer.append("Stack of thread \"");
        buffer.append(thread.getName());
        buffer.append("\", id: ");
        buffer.append(thread.getId());
        buffer.append(": {");
        buffer.append(GlobalDefs.EOL);
        final StackTraceElement[] coalescingQueueStack = thread.getStackTrace();
        for (StackTraceElement stackTraceElement : coalescingQueueStack)
        {
            buffer.append("  ").append(stackTraceElement).append(GlobalDefs.EOL);
        }
        buffer.append("}");
        return buffer.toString();
    }


    public static String getCurrentThreadStack()
    {
        return getCurrentThreadStack(1);
    }

    public static String getCurrentThreadStack(int skipNFrames)
    {
        return getCurrentThreadStack(skipNFrames, Integer.MAX_VALUE);
    }

    public static String getCurrentThreadStack(int skipNFrames, int limit)
    {
        Thread thread = Thread.currentThread();
        StringBuilder result = new StringBuilder();
        result.append(GlobalDefs.EOL);
        final StackTraceElement[] coalescingQueueStack = thread.getStackTrace();
        int reported = 0;
        for (int i = 2 + skipNFrames; i < coalescingQueueStack.length; ++i)
        {
            if (reported >= limit)
            {
                result.append("  ...").append(GlobalDefs.EOL);
                break;
            }
            result.append("  ").append(coalescingQueueStack[i]).append(GlobalDefs.EOL);
            ++reported;
        }
        return result.toString();
    }

    /**
     * Spins until time comes
     */
    public static void busySleepInMicros(long delay)
    {
        busySleepInNanos(delay * 1000L);
    }

    public static void busySleepInNanos(long delay)
    {
        long end = System.nanoTime() + delay;
        while (System.nanoTime() < end)
            Thread.yield();
    }

    /**
     * Don't use LockSupport#parkNanos(). It causes the
     * com.alex.util.throttler.LeakingBucketThrottler_utest#testManyEvents() on
     * Windows box to perform very bad.
     *
     * @param nanos
     */
    public static void sleepNanos(long nanos)
    {
        long end = System.nanoTime() + nanos;
        while ((end - System.nanoTime()) >= 20_000_000L)
        {
            try
            {
                Thread.sleep(1);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        busySleepInNanos(end - System.nanoTime());
    }

    public static void sleep(long millis)
    {
        sleep(millis, false);
    }

    public static void sleep(long millis, boolean printException)
    {
        UtilityFunctions.sleep(millis, printException);
    }

    public static Thread startDaemonThread(Runnable runnable)
    {
        return startDaemonThread("<no name>", runnable);
    }

    public static Thread startDaemonThread(String name, Runnable runnable)
    {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
