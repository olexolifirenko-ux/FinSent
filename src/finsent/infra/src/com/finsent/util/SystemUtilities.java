/*
 * Copyright (c) 1997-2009 InfoReach, Inc. All Rights Reserved.
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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.Map;

public class SystemUtilities
{
    public static String getCurrentThreadStack()
    {
        StringWriter writer = new StringWriter();
        try
        {
            dumpAllStacks(writer, Thread.currentThread().getId(), 1);
        }
        catch (IOException ex)
        {
            ex.printStackTrace(new PrintWriter(writer, true));
        }
        return writer.toString();
    }

    protected static void dumpAllStacks(Writer writer, long threadId, int skippedDepth) throws IOException
    {
        if (skippedDepth > 0)
        {
            skippedDepth++;
        }
        try
        {
            if (skippedDepth > 0)
            {
                skippedDepth++; // ManagementFactory.getThreadMXBean().dumpAllThreads() adds 1 stack trace
            }
            ThreadInfo[] threadInfos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
            for (ThreadInfo threadInfo : threadInfos)
            {
                if (threadInfo.getThreadId() == threadId)
                {
                    writer.write(threadInfoToString(threadInfo, skippedDepth));
                    break;
                }
            }
        }
        catch (java.lang.Error err)
        {
            writer.write("Cannot use ThreadMXBean " + err.getMessage() + ". Using Thread#getAllStackTraces() instead\n");
            if (skippedDepth > 0)
            {
                skippedDepth += 2; // because Thread.getAllStackTraces() adds 2 stack elements
            }
            Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
            Thread[] threads = allStackTraces.keySet().toArray(new Thread[allStackTraces.size()]);
            for (Thread thread : threads)
            {
                if (thread.getId() == threadId)
                {
                    writer.write("\"" + thread.getName() + "\" Id=" + thread.getId() + "\n");
                    int depth = 0;
                    for (StackTraceElement ste : allStackTraces.get(thread))
                    {
                        depth++;
                        if (depth <= skippedDepth)
                        {
                            continue;
                        }
                        writer.write("\tat " + ste + "\n");
                    }
                    writer.write("\n");
                }
            }
        }
        writer.flush();
    }

    public static String threadInfoToString(ThreadInfo threadInfo, int skippedDepth)
    {
        if (skippedDepth > 0)
        {
            skippedDepth++;
        }
        StringBuilder sb = new StringBuilder("\"" + threadInfo.getThreadName() + "\"" +
                                             " Id=" + threadInfo.getThreadId() + " " +
                                             threadInfo.getThreadState());
        if (threadInfo.getLockName() != null) {
            sb.append(" on " + threadInfo.getLockName());
        }
        if (threadInfo.getLockOwnerName() != null) {
            sb.append(" owned by \"" + threadInfo.getLockOwnerName() +
                      "\" Id=" + threadInfo.getLockOwnerId());
        }
        if (threadInfo.isSuspended()) {
            sb.append(" (suspended)");
        }
        if (threadInfo.isInNative()) {
            sb.append(" (in native)");
        }
        sb.append('\n');
        StackTraceElement[] stackTrace = threadInfo.getStackTrace();
        int depth = 0;
        for (int i = 0; i < stackTrace.length; i++)
        {
            depth++;
            if (depth <= skippedDepth)
            {
                continue;
            }
            StackTraceElement ste = stackTrace[i];
            sb.append("\tat " + ste.toString());
            sb.append('\n');
            if (i == 0 && threadInfo.getLockInfo() != null) {
                Thread.State ts = threadInfo.getThreadState();
                switch (ts) {
                    case BLOCKED:
                        sb.append("\t-  blocked on " + threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    case WAITING:
                        sb.append("\t-  waiting on " + threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    case TIMED_WAITING:
                        sb.append("\t-  waiting on " + threadInfo.getLockInfo());
                        sb.append('\n');
                        break;
                    default:
                }
            }

            for (MonitorInfo mi : threadInfo.getLockedMonitors()) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append("\t-  locked " + mi);
                    sb.append('\n');
                }
            }
       }

       LockInfo[] locks = threadInfo.getLockedSynchronizers();
       if (locks.length > 0) {
           sb.append("\n\tNumber of locked synchronizers = " + locks.length);
           sb.append('\n');
           for (LockInfo li : locks) {
               sb.append("\t- " + li);
               sb.append('\n');
           }
       }
       sb.append('\n');
       return sb.toString();
    }
}
