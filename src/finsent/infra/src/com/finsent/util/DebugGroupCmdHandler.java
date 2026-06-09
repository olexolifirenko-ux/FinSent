/*
 * Copyright (c) 1997-2003 InfoReach, Inc. All Rights Reserved.
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
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.management.MBeanServer;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;

/**
 * The {@code debug} command group: process-level diagnostics gathered under a
 * single sub-command dispatcher.
 *
 * <p>This is a trimmed variant of the full InfoReach {@code DebugGroupCmdHandler}.
 * It keeps the JDK-only sub-commands ({@code processInfo}, {@code listThreads},
 * {@code dumpAllStacks}, {@code runGC}, {@code dumpHeap}, {@code cpuUsage}); the
 * RMI reference dump, jar-info dump, diagnose and security (user name) commands
 * of the original, which depend on subsystems omitted from this project, are
 * intentionally left out.
 */
public class DebugGroupCmdHandler extends CmdGroupHandler
{
    public final static String COMMAND = "debug";
    public final static String[] COMMAND_ALIASES = new String[] {"d"};
    public final static String DESCRIPTION = "Debug command group,\nusage: " +
       COMMAND + " <sub_command> [<parameters>]";

    private final static DebugGroupCmdHandler DefaultInstance_ = new DebugGroupCmdHandler();

    private static final int FULL_GARBAGE_COLLECTION_ITERATION_LIMIT =
        Integer.getInteger("fullGarbageCollectionIterationLimit", 3);

    public DebugGroupCmdHandler()
    {
        // process info
        registerCmdHandler(ProcessInfoCmdHandler.COMMAND, new ProcessInfoCmdHandler(),
            ProcessInfoCmdHandler.DESCRIPTION, ProcessInfoCmdHandler.ALIASES);

        // list threads command
        registerCmdHandler(ListThreadsCmdHandler.COMMAND, new ListThreadsCmdHandler(),
            ListThreadsCmdHandler.DESCRIPTION, ListThreadsCmdHandler.ALIASES);

        // dump all stacks
        registerCmdHandler(DumpAllStacksCmdHandler.COMMAND, new DumpAllStacksCmdHandler(),
            DumpAllStacksCmdHandler.DESCRIPTION, DumpAllStacksCmdHandler.ALIASES);

        // garbage collector
        registerCmdHandler(RunGarbageCollectorCmdHandler.COMMAND, new RunGarbageCollectorCmdHandler(),
            RunGarbageCollectorCmdHandler.DESCRIPTION, RunGarbageCollectorCmdHandler.ALIASES);

        registerCmdHandler(DumpHeapCmdHandler.COMMAND, new DumpHeapCmdHandler(),
            DumpHeapCmdHandler.DESCRIPTION, DumpHeapCmdHandler.ALIASES);

        registerCmdHandler(CpuUsageCmdHandler.COMMAND, new CpuUsageCmdHandler(),
            CpuUsageCmdHandler.DESCRIPTION, CpuUsageCmdHandler.ALIASES);
    }

    public static DebugGroupCmdHandler getDefaultInstance()
    {
        return DefaultInstance_;
    }

    private static String getMemoryDump()
    {
        long free = Math.round((double)Runtime.getRuntime().freeMemory()/1024);
        long total = Math.round((double)Runtime.getRuntime().totalMemory()/1024);
        long used = total - free;
        StringBuilder buf = new StringBuilder();
        buf.append("used: ").append(used).append("K, ");
        buf.append("free: ").append(free).append("K, ");
        buf.append("total: ").append(total).append("K");
        return buf.toString();
    }

    private static void runFullGarbageCollection()
    {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < FULL_GARBAGE_COLLECTION_ITERATION_LIMIT; i++)
        {
            final long freeBeforeGC = rt.freeMemory();
            rt.gc();
            final long freeAfterGC = rt.freeMemory();
            if (freeAfterGC < freeBeforeGC)
                break;
        }
    }

    private static void dumpHeap(String fileName, boolean liveOnly) throws IOException
    {
        final String hotspotBeanName = "com.sun.management:type=HotSpotDiagnostic";

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        HotSpotDiagnosticMXBean hotspotMBean =
            ManagementFactory.newPlatformMXBeanProxy(server, hotspotBeanName, HotSpotDiagnosticMXBean.class);
        hotspotMBean.dumpHeap(fileName, liveOnly);
    }

    // processInfo command handler implementation
    static class ProcessInfoCmdHandler implements ICmdHandler
    {
        static final String COMMAND = "processInfo";
        static final String[] ALIASES = { "pi" };
        static final String DESCRIPTION = "Display process information";

        public int commandEntered(Writer writer, String command, String[] args)
        {
            NumberFormat format = NumberFormat.getInstance();
            format.setGroupingUsed(true);
            RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
            UtilityFunctions.writeln(writer, "process type: " + GlobalSystem.getProcessType(), false);
            UtilityFunctions.writeln(writer, "process name: " + GlobalSystem.getProcessName(), false);
            UtilityFunctions.writeln(writer, "started at: " + new Date(runtimeMXBean.getStartTime()) +
                " (uptime " + formatUptime(runtimeMXBean.getUptime()) + ")", false);
            UtilityFunctions.writeln(writer, "memory size: " + format.format(Runtime.getRuntime().totalMemory()), false);
            UtilityFunctions.writeln(writer, "free memory size: " + format.format(Runtime.getRuntime().freeMemory()), false);
            UtilityFunctions.writeln(writer, "thread count: " + ManagementFactory.getThreadMXBean().getThreadCount(), false);
            UtilityFunctions.writeln(writer, "PID: " + ProcessHandle.current().pid());
            return 0;
        }

        private static String formatUptime(long diffMillis)
        {
            long days = diffMillis / (24 * 60 * 60 * 1000L);
            diffMillis -= days * (24 * 60 * 60 * 1000L);
            long hours = diffMillis / (60 * 60 * 1000L);
            diffMillis -= hours * (60 * 60 * 1000L);
            long minutes = diffMillis / (60 * 1000L);
            diffMillis -= minutes * (60 * 1000L);
            long seconds = diffMillis / 1000L;
            if (days > 0)
                return days + " days " + hours + " hours";
            if (hours > 0)
                return hours + " hours " + minutes + " minutes";
            if (minutes > 0)
                return minutes + " minutes " + seconds + " seconds";

            return seconds + " seconds";
        }
    }

    // listThreads command handler implementation
    static class ListThreadsCmdHandler implements ICmdHandler
    {
        static final String COMMAND = "listThreads";
        static final String[] ALIASES = { "lt" };
        static final String DESCRIPTION = "List all threads.\nUsage: " + COMMAND +
            " [-sort=<asc|desc>] [-filter=<regexp filter>] [-stacks]";

        private static final String PARAM_SORT   = "-sort=";
        private static final String PARAM_FILTER = "-filter=";
        private static final String PARAM_STACKS = "-stacks";

        public int commandEntered(Writer writer, String command, String[] args)
        {
            ThreadLister.SortMode sort = ThreadLister.SortMode.NONE;
            String filter = null;
            boolean stacks = false;

            if (args != null && args.length > 0)
            {
                for (String s : args)
                {
                    if (s.toLowerCase().startsWith(PARAM_SORT))
                    {
                        String val = s.substring(PARAM_SORT.length());
                        try
                        {
                            sort = ThreadLister.SortMode.valueOf(val.toUpperCase());
                        }
                        catch (IllegalArgumentException ex)
                        {
                            UtilityFunctions.writeln(writer, "Wrong value: '" + val + "'.\n");
                            return 1;
                        }
                    }
                    if (s.toLowerCase().startsWith(PARAM_FILTER))
                    {
                        filter = s.substring(PARAM_FILTER.length());
                    }
                    if (s.toLowerCase().startsWith(PARAM_STACKS))
                    {
                        stacks = true;
                    }
                }
            }
            ThreadLister.listAllThreads(new PrintWriter(writer), sort, filter, stacks);
            return 0;
        }
    }

    // dumpAllStacks command handler implementation
    static class DumpAllStacksCmdHandler implements ICmdHandler
    {
        static final String COMMAND = "dumpAllStacks";
        static final String[] ALIASES = { "das" };

        private static final String NAME_PARAM = "n";
        private static final String USAGE = "Usage: " + COMMAND + " [-" + NAME_PARAM + " <thread name pattern>]";
        static final String DESCRIPTION = "Dump All Stacks\n" + USAGE;

        private static final Map<String, int[]> VALID_OPTIONS = new HashMap<>();
        static
        {
            VALID_OPTIONS.put(NAME_PARAM, new int[] {1, 0});
        }

        public int commandEntered(Writer writer, String command, String[] args)
        {
            CmdArgParser argParser = new CmdArgParser(args);
            argParser.setValidOptionMetaData(VALID_OPTIONS);
            if (!argParser.isValid())
            {
                UtilityFunctions.writeln(writer, argParser.getErrorsString());
                UtilityFunctions.writeln(writer, USAGE);
                return 1;
            }

            String namePattern = argParser.getOptionValue(NAME_PARAM);
            ThreadLister.listAllThreads(new PrintWriter(writer), ThreadLister.SortMode.NONE, namePattern, true);
            return 0;
        }
    }

    // runGC command handler implementation
    static class RunGarbageCollectorCmdHandler implements ICmdHandler
    {
        static final String COMMAND = "runGC";
        static final String[] ALIASES = { "gc" };
        static final String DESCRIPTION = "Run garbage collector";

        public int commandEntered(Writer writer, String command, String[] args)
        {
            UtilityFunctions.writeln(writer, "Memory state before garbage collection: " + getMemoryDump());
            runFullGarbageCollection();
            UtilityFunctions.writeln(writer, "Memory state after garbage collection: " + getMemoryDump());
            return 0;
        }
    }

    // dumpHeap command handler implementation
    static class DumpHeapCmdHandler implements ICmdHandler
    {
        static final String COMMAND = "dumpHeap";
        static final String[] ALIASES = { "dh" };

        private static final String FILE_PARAM = "file";
        private static final String LIVE_PARAM = "liveObjects";
        private static final String USAGE =
            "Usage: " + COMMAND + " [-" + FILE_PARAM + " <path_to_heap_dump_file>] [-" + LIVE_PARAM + "]";
        static final String DESCRIPTION =
            "Dumps the heap to the file in the same format as the hprof heap dump.\n" + USAGE;

        private static final String DEFAULT_FORMAT = "{0}/heap_{1}_{2,date,yyyyMMddHHmmss}.hprof";

        private static final Map<String, int[]> VALID_OPTIONS = new HashMap<>();
        static
        {
            VALID_OPTIONS.put(FILE_PARAM, new int[] {1, 0});
            VALID_OPTIONS.put(LIVE_PARAM, new int[] {0, 0});
        }

        public int commandEntered(Writer writer, String command, String[] args)
        {
            CmdArgParser argParser = new CmdArgParser(args);
            argParser.setValidOptionMetaData(VALID_OPTIONS);
            if (!argParser.isValid())
            {
                UtilityFunctions.writeln(writer, argParser.getErrorsString());
                UtilityFunctions.writeln(writer, USAGE);
                return 1;
            }

            String fileName;
            if (argParser.isOptionSet(FILE_PARAM))
            {
                fileName = argParser.getOptionValue(FILE_PARAM);
            }
            else
            {
                String folder = System.getProperty(GlobalDefs.CFG_LOGS_DIR, ".");
                String processName = GlobalSystem.getProcessName();
                fileName = MessageFormat.format(DEFAULT_FORMAT, folder, processName, new Date());
            }
            boolean liveOnly = argParser.isOptionSet(LIVE_PARAM);

            try
            {
                UtilityFunctions.writeln(writer, "Starting the heap dump generation into '" + fileName + "'...");
                dumpHeap(fileName, liveOnly);
                UtilityFunctions.writeln(writer, "Done!");
                return 0;
            }
            catch (IOException e)
            {
                UtilityFunctions.writeln(writer, "Error during generation: " + e.getMessage());
                UtilityFunctions.writeln(writer, "Generation stopped!");
                return 1;
            }
        }
    }

    // cpuUsage command handler implementation
    static class CpuUsageCmdHandler implements ICmdHandler
    {
        static final String COMMAND = "cpuUsage";
        static final String[] ALIASES = { "cpu" };

        private static final String DELAY_PARAM = "d";
        private static final String NUMBER_PARAM = "n";
        private static final String USAGE = "Usage: " + COMMAND +
            " [-" + DELAY_PARAM + " <delay in seconds, default 3>]" +
            " [-" + NUMBER_PARAM + " <number of checks, 0 for infinite, default 1>]";
        static final String DESCRIPTION = "Show CPU usage for current process.\n" + USAGE;

        private static final Map<String, int[]> VALID_OPTIONS = new HashMap<>();
        static
        {
            VALID_OPTIONS.put(DELAY_PARAM, new int[] {1, 0});
            VALID_OPTIONS.put(NUMBER_PARAM, new int[] {1, 0});
        }

        public int commandEntered(Writer writer, String command, String[] args)
        {
            CmdArgParser argParser = new CmdArgParser(args);
            argParser.setValidOptionMetaData(VALID_OPTIONS);
            if (!argParser.isValid())
            {
                UtilityFunctions.writeln(writer, argParser.getErrorsString());
                UtilityFunctions.writeln(writer, USAGE);
                return 1;
            }

            try
            {
                int delayInSec = Integer.parseInt(argParser.getOptionValue(DELAY_PARAM, "3"));
                int numberOfChecks = Integer.parseInt(argParser.getOptionValue(NUMBER_PARAM, "1"));
                if (numberOfChecks == 0)
                    numberOfChecks = Integer.MAX_VALUE;

                OperatingSystemMXBean os = (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
                int availableProcessors = os.getAvailableProcessors();

                for (int i = 0; i < numberOfChecks; i++)
                {
                    long cpuTime = os.getProcessCpuTime();
                    long time = System.nanoTime();

                    Thread.sleep(delayInSec * 1000L);

                    time = System.nanoTime() - time;
                    cpuTime = os.getProcessCpuTime() - cpuTime;

                    double cpuUsage = 100d * cpuTime / time / availableProcessors;
                    UtilityFunctions.writeln(writer, String.format("CPU usage: %.1f%%", cpuUsage));
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                UtilityFunctions.writeln(writer, e.getMessage());
            }

            return 0;
        }
    }
}
