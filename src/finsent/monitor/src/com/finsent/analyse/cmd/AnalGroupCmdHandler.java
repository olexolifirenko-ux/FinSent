package com.finsent.analyse.cmd;

import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.finsent.analyse.FSAnalyser;
import com.finsent.analyse.Intervals;
import com.finsent.util.CmdArgParser;
import com.finsent.util.CmdGroupHandler;
import com.finsent.util.ICmdHandler;
import com.finsent.util.UtilityFunctions;

/**
 * The {@code anal} command group: runtime control of the analyser over the command interpreter
 * (mirrors the {@code [anal]} log tag and replaces Python's {@code --no-analyse} flag with a live
 * toggle). Sub-commands:
 * <ul>
 *   <li>{@code on} / {@code off} / {@code status} &mdash; live analysis control ({@code start} /
 *       {@code pause} are accepted as aliases);</li>
 *   <li>{@code window <YYYYMMDD_HHMM>} &mdash; re-analyse one stored window now (ports {@code --window});</li>
 *   <li>{@code windows <N>} or {@code windows -start .. -end ..} &mdash; scan/backfill a range of windows
 *       ({@code <N>} = the last N days through now); dry-run by default;</li>
 *   <li>{@code econ [YYYYMMDD] <event name> [-quiet]} &mdash; manually run the econ analysis for a resolved release;</li>
 *   <li>{@code show <YYYYMMDD_HHMM>} &mdash; print the stored analysis record (read-only).</li>
 * </ul>
 * Registered against the running interpreter once the analyser exists (see {@code FSApp}).
 */
public final class AnalGroupCmdHandler extends CmdGroupHandler
{
    public static final String COMMAND = "anal";
    public static final String[] COMMAND_ALIASES = null;
    public static final String DESCRIPTION = "Analyser control,\nusage: " + COMMAND
            + " <on|off|status|window <YYYYMMDD_HHMM>|windows <N>|windows -start .. -end ..|show <YYYYMMDD_HHMM>"
            + "|econ [YYYYMMDD] <event name> [-quiet]|feedback [--days N]>";

    public AnalGroupCmdHandler(FSAnalyser analyser)
    {
        registerCmdHandler("on", new OnCmdHandler(analyser), "Turn analysis on (resume).", new String[] {"start"});
        registerCmdHandler("off", new OffCmdHandler(analyser), "Turn analysis off (pause).", new String[] {"pause"});
        registerCmdHandler("status", new StatusCmdHandler(analyser), "Show analyser status.", null);
        registerCmdHandler("window", new WindowCmdHandler(analyser),
                "Re-analyse a stored window now: window <YYYYMMDD_HHMM> (e.g. 20260517_2210).", null);
        registerCmdHandler("windows", new WindowsCmdHandler(analyser), WindowsCmdHandler.USAGE, null);
        registerCmdHandler("econ", new EconCmdHandler(analyser),
                "Manually run the econ analysis for a resolved scheduled release: econ [YYYYMMDD] <event name> "
                        + "[-quiet] (e.g. econ CPI MoM; day defaults to today). -quiet records without notifying.", null);
        registerCmdHandler("show", new ShowCmdHandler(analyser),
                "Show a stored analysis record (read-only): show <YYYYMMDD_HHMM>.", null);
        registerCmdHandler("feedback", new FeedbackCmdHandler(analyser),
                "Score stored predictions vs realized BTC moves and print the accuracy report (BL#6); "
                        + "feedback [--days N] bounds the scan to the last N days.", null);
    }

    /** Parse {@code YYYYMMDD_HHMM} into {@code {day, "HH:MM"}}, or null when malformed/absent. */
    static String[] parseDayKey(String token)
    {
        String[] result = null;
        if (token != null && token.matches("\\d{8}_\\d{4}"))
        {
            String hhmm = token.substring(9);
            result = new String[] { token.substring(0, 8), hhmm.substring(0, 2) + ":" + hhmm.substring(2, 4) };
        }
        return result;
    }

    private static final class OnCmdHandler implements ICmdHandler
    {
        private final FSAnalyser analyser_;

        private OnCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            analyser_.resume();
            UtilityFunctions.writeln(writer, "Analysis ON (running).");
            return 0;
        }
    }

    private static final class OffCmdHandler implements ICmdHandler
    {
        private final FSAnalyser analyser_;

        private OffCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            analyser_.pause();
            UtilityFunctions.writeln(writer, "Analysis OFF (paused).");
            return 0;
        }
    }

    private static final class StatusCmdHandler implements ICmdHandler
    {
        private final FSAnalyser analyser_;

        private StatusCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            UtilityFunctions.writeln(writer, "Analyser: " + analyser_.status()
                    + " (queue depth " + analyser_.queueDepth() + ").");
            return 0;
        }
    }

    /** {@code anal window <YYYYMMDD_HHMM>}: re-analyse one stored window synchronously, on demand. */
    private static final class WindowCmdHandler implements ICmdHandler
    {
        private static final String USAGE = "Usage: anal window <YYYYMMDD_HHMM> (e.g. 20260517_2210)";
        private final FSAnalyser analyser_;

        private WindowCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            int code = 0;
            String[] dayKey = parseDayKey(args.length > 0 ? args[0] : null);
            if (dayKey == null)
            {
                UtilityFunctions.writeln(writer, USAGE);
                code = 1;
            }
            else
            {
                UtilityFunctions.writeln(writer, "Re-analysing " + dayKey[0] + " " + dayKey[1]
                        + " (runs Claude synchronously)...");
                try
                {
                    UtilityFunctions.writeln(writer, "Done: " + analyser_.reanalyse(dayKey[0], dayKey[1]));
                }
                catch (Exception reanalyseFailed)
                {
                    // Abort just this command; the interpreter thread stays alive for the next command.
                    UtilityFunctions.writeln(writer, "Re-analysis failed (aborted): " + reanalyseFailed);
                    code = 1;
                }
            }
            return code;
        }
    }

    /**
     * {@code anal windows -start <YYYYMMDD_HHMM> -end <YYYYMMDD_HHMM> [-missing|-force] [-run] [-notify]}:
     * scan a window range for gaps and (with {@code -run}) backfill them. Dry-run by default; backfill
     * is record-only unless {@code -notify}.
     */
    private static final class WindowsCmdHandler implements ICmdHandler
    {
        static final String USAGE = "Backfill a window range: windows <N> | -start <YYYYMMDD_HHMM> -end <YYYYMMDD_HHMM>"
                + " [-missing|-force] [-run] [-notify]; <N> = last N days (today and the N-1 prior), 00:00 of the"
                + " first day through now";
        private static final int MAX_LISTED = 50;
        private static final Map<String, int[]> VALID_OPTIONS = new HashMap<>();
        static
        {
            VALID_OPTIONS.put("start", new int[] {1, 0});
            VALID_OPTIONS.put("end", new int[] {1, 0});
            VALID_OPTIONS.put("missing", new int[] {0, 0});
            VALID_OPTIONS.put("force", new int[] {0, 0});
            VALID_OPTIONS.put("run", new int[] {0, 0});
            VALID_OPTIONS.put("notify", new int[] {0, 0});
        }

        private final FSAnalyser analyser_;

        private WindowsCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            // A leading positive integer is the convenience days-form (windows <N>): the option flags
            // still parse from the remaining tokens, but start/end come from "last N days .. now".
            Integer days = leadingDays(args);
            String[] flagArgs = days == null ? args : tail(args);
            CmdArgParser parser = new CmdArgParser(flagArgs);
            parser.setValidOptionMetaData(VALID_OPTIONS);
            int code = 0;
            String[] start;
            String[] end;
            if (days != null)
            {
                Intervals.DayKey now = analyser_.currentWindow();
                end = new String[] {now.day(), now.key()};
                start = new String[] {Intervals.minusDays(now.day(), days - 1), "00:00"};
            }
            else
            {
                start = parseDayKey(parser.getOptionValue("start"));
                end = parseDayKey(parser.getOptionValue("end"));
            }
            if (!parser.isValid() || start == null || end == null)
            {
                if (!parser.isValid())
                {
                    UtilityFunctions.writeln(writer, parser.getErrorsString());
                }
                UtilityFunctions.writeln(writer, USAGE);
                code = 1;
            }
            else
            {
                runScan(writer, start, end, parser.isOptionSet("force"), parser.isOptionSet("run"),
                        parser.isOptionSet("notify"));
            }
            return code;
        }

        /** The leading {@code <N>} days token as a positive int, or null when args don't start with one. */
        private static Integer leadingDays(String[] args)
        {
            Integer days = null;
            if (args.length > 0 && args[0].matches("\\d+"))
            {
                int parsed = Integer.parseInt(args[0]);
                days = parsed > 0 ? parsed : null;
            }
            return days;
        }

        /** {@code args} without its first element (the consumed {@code <N>} token). */
        private static String[] tail(String[] args)
        {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return rest;
        }

        private void runScan(Writer writer, String[] start, String[] end, boolean force, boolean run, boolean notify)
        {
            FSAnalyser.BackfillPlan plan = analyser_.planBackfill(start[0], start[1], end[0], end[1], force);
            List<Intervals.DayKey> windows = plan.windows();
            UtilityFunctions.writeln(writer, windows.size() + " window(s) " + (force ? "with news in range"
                    : "missing analysis") + (plan.truncated() ? " (scan hit cap -- narrow the range)" : "") + ":");
            for (int i = 0; i < windows.size() && i < MAX_LISTED; i++)
            {
                UtilityFunctions.writeln(writer, "  " + windows.get(i).day() + " " + windows.get(i).key());
            }
            if (windows.size() > MAX_LISTED)
            {
                UtilityFunctions.writeln(writer, "  ... and " + (windows.size() - MAX_LISTED) + " more.");
            }
            report(writer, windows, run, notify);
        }

        private void report(Writer writer, List<Intervals.DayKey> windows, boolean run, boolean notify)
        {
            if (windows.isEmpty())
            {
                UtilityFunctions.writeln(writer, "Nothing to do.");
            }
            else if (run)
            {
                analyser_.runBackfill(windows, notify);
                UtilityFunctions.writeln(writer, "Backfill started for " + windows.size() + " window(s) -- "
                        + (notify ? "notifications ON" : "record-only") + ". See log for progress.");
            }
            else
            {
                UtilityFunctions.writeln(writer, "Dry run -- add -run to analyse these " + windows.size()
                        + " window(s)" + (notify ? " (with -notify)" : "") + ".");
            }
        }
    }

    /** {@code anal feedback}: score stored predictions against realized BTC moves and print the report. */
    private static final class FeedbackCmdHandler implements ICmdHandler
    {
        private final FSAnalyser analyser_;

        private FeedbackCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            int days = parseDays(args);
            String scope = days > 0 ? "last " + days + " day(s)" : "all history";
            UtilityFunctions.writeln(writer, "Scoring predictions (" + scope
                    + ") against realized BTC prices (fetches Binance klines)...");
            UtilityFunctions.writeln(writer, analyser_.runFeedback(days));
            return 0;
        }

        /** Parse an optional {@code --days N} (or {@code -days N}); 0 when absent or malformed = all history. */
        private static int parseDays(String[] args)
        {
            int days = 0;
            for (int i = 0; i + 1 < args.length; i++)
            {
                if (args[i].equals("--days") || args[i].equals("-days"))
                {
                    days = parseIntOrZero(args[i + 1]);
                }
            }
            return days;
        }

        private static int parseIntOrZero(String value)
        {
            int parsed = 0;
            try
            {
                parsed = Integer.parseInt(value);
            }
            catch (NumberFormatException notANumber)
            {
                parsed = 0; // malformed --days value -> fall back to all history
            }
            return parsed;
        }
    }

    /**
     * {@code anal econ [YYYYMMDD] <event name> [-quiet]}: manually run the econ analysis for a resolved
     * scheduled release. An optional leading {@code YYYYMMDD} pins the release day (defaults to today); the
     * event name may contain spaces (the remaining tokens joined); {@code -quiet} records without notifying
     * (notify is on by default, like {@code anal window}).
     */
    private static final class EconCmdHandler implements ICmdHandler
    {
        private static final String USAGE = "Usage: anal econ [YYYYMMDD] <event name> [-quiet] (e.g. econ CPI MoM)";
        private final FSAnalyser analyser_;

        private EconCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            int code = 0;
            boolean quiet = false;
            List<String> rest = new ArrayList<>();
            for (String arg : args)
            {
                if (arg.equals("-quiet"))
                {
                    quiet = true;
                }
                else
                {
                    rest.add(arg);
                }
            }
            String day = !rest.isEmpty() && rest.get(0).matches("\\d{8}") ? rest.remove(0) : null;
            String eventName = String.join(" ", rest).trim();
            if (eventName.isEmpty())
            {
                UtilityFunctions.writeln(writer, USAGE);
                code = 1;
            }
            else
            {
                code = runEcon(writer, day, eventName, !quiet);
            }
            return code;
        }

        private int runEcon(Writer writer, String day, String eventName, boolean notify)
        {
            int code = 0;
            UtilityFunctions.writeln(writer, "Running econ analysis for '" + eventName + "'"
                    + (day == null ? " (today)" : " on " + day) + (notify ? "" : " (quiet)")
                    + " (runs Claude synchronously)...");
            try
            {
                UtilityFunctions.writeln(writer, "Done: " + analyser_.reanalyseEcon(day, eventName, notify));
            }
            catch (Exception econFailed)
            {
                // Abort just this command; the interpreter thread stays alive for the next command.
                UtilityFunctions.writeln(writer, "Econ analysis failed (aborted): " + econFailed);
                code = 1;
            }
            return code;
        }
    }

    /** {@code anal show <YYYYMMDD_HHMM>}: print the stored analysis record for a window (read-only). */
    private static final class ShowCmdHandler implements ICmdHandler
    {
        private static final String USAGE = "Usage: anal show <YYYYMMDD_HHMM>";
        private final FSAnalyser analyser_;

        private ShowCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            int code = 0;
            String[] dayKey = parseDayKey(args.length > 0 ? args[0] : null);
            if (dayKey == null)
            {
                UtilityFunctions.writeln(writer, USAGE);
                code = 1;
            }
            else
            {
                UtilityFunctions.writeln(writer, analyser_.describe(dayKey[0], dayKey[1]));
            }
            return code;
        }
    }
}
