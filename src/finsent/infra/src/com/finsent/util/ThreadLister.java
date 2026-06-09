/*
 * Copyright (c) 1999-2000 InfoReach, Inc. All Rights Reserved.
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

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import static java.util.Arrays.stream;

public/*nodoc*/ class ThreadLister
{
    public enum SortMode
    {NONE, ASC, DESC}

    private static final NumberFormat ALLOC_BYTES_FORMAT = new DecimalFormat("###,###.##");

    /**
     * Find the root thread group and list it recursively
     */
    public static void listAllThreads(final PrintWriter out, final SortMode sort, final String filter, final boolean stacks)
    {
        listAllThreadsInAGroup(out, findRootThreadGroup(), "", sort, filter, stacks);
    }

    public static void listAllThreadsInAGroup(final PrintWriter out, final ThreadGroup threadGroup, final boolean stacks)
    {
        listAllThreadsInAGroup(out, threadGroup, "", SortMode.NONE, null, stacks);
    }

    private static void listAllThreadsInAGroup(final PrintWriter out, final ThreadGroup threadGroup, final String indent, final SortMode sort, final String filter, final boolean stacks)
    {
        final Pattern pattern = createPattern(out, filter);

        if (filter != null && pattern == null)
            // I.e. there was a failure creating pattern for matching with provided filter expression.
            return;

        printGroupInfo(out, threadGroup, indent, sort, pattern, stacks);
        out.flush();
    }

    private static Pattern createPattern(final PrintWriter out, final String filter)
    {
        if (filter == null)
            return null;

        try
        {
            return Pattern.compile(filter);
        }
        catch (PatternSyntaxException ex)
        {
            ex.printStackTrace(out);
            return null;
        }
    }

    private static ThreadGroup findRootThreadGroup()
    {
        ThreadGroup retVal = Thread.currentThread().getThreadGroup();
        ThreadGroup parent = retVal.getParent();
        while (parent != null)
        {
            retVal = parent;
            parent = parent.getParent();
        }
        return retVal;
    }

    /**
     * Display information about a thread.
     */
    private static void printThreadInfo(final PrintWriter out, final Thread thread, final String indent, final boolean stacks)
    {
        if (thread == null)
            return;

        String optionalDetails = null;
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (threadMXBean instanceof com.sun.management.ThreadMXBean)
        {
            com.sun.management.ThreadMXBean threadMXBeanImpl = (com.sun.management.ThreadMXBean)threadMXBean;
            optionalDetails = ", Allocated " + ALLOC_BYTES_FORMAT.format(threadMXBeanImpl.getThreadAllocatedBytes(thread.getId())) + " bytes";
        }

        final StringBuilder msg = new StringBuilder().append(indent)
            .append("Thread ").append(thread.getName())
            .append(" [id=0x").append(Long.toHexString(thread.getId()))
            .append("]: Priority:").append(thread.getPriority())
            .append(thread.isDaemon() ? " Daemon" : " ")
            .append(thread.isAlive() ? "" : " Not Alive")
            .append(optionalDetails != null ? optionalDetails : "");


        if (stacks)
        {
            final String stackIndent = indent + indent;
            stream(thread.getStackTrace()).forEach(stackTraceElement -> msg.append(GlobalDefs.EOL).append(stackIndent).append(stackTraceElement));
        }

        out.println(msg.toString());
    }

    /**
     * Display info about a thread group and its threads and groups
     */
    private static void printGroupInfo(final PrintWriter out, final ThreadGroup threadGroup, final String indent, final SortMode sort, final Pattern filter, final boolean stacks)
    {
        final Thread[] threads = new Thread[threadGroup.activeCount()];
        final ThreadGroup[] groups = new ThreadGroup[threadGroup.activeGroupCount()];

        threadGroup.enumerate(threads, false);
        threadGroup.enumerate(groups, false);

        out.println(
            indent + "Thread Group: " + threadGroup.getName() +
                "  Max Priority: " + threadGroup.getMaxPriority() +
                (threadGroup.isDaemon() ? " Daemon" : ""));

        Stream<Thread> threadStream = stream(threads).filter(Objects::nonNull);
        if (filter != null)
            threadStream = threadStream.filter(thread -> filter.matcher(thread.getName()).matches());

        switch (sort)
        {
            case ASC:
                threadStream = threadStream.sorted(Comparator.comparing(Thread::getName));
                break;
            case DESC:
                threadStream = threadStream.sorted(Comparator.comparing(Thread::getName).reversed());
                break;
            case NONE:
                break;
        }

        threadStream.forEach(thread -> printThreadInfo(out, thread, indent + "    ", stacks));

        stream(groups)
            .filter(Objects::nonNull)
            .forEach(subGroup -> printGroupInfo(out, subGroup, indent + "    ", sort, filter, stacks));
    }
}
