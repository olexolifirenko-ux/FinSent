package com.finsent.analyse.cmd;

import java.io.Writer;
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
 *   <li>{@code start} / {@code pause} / {@code status} &mdash; live analysis control;</li>
 *   <li>{@code window <YYYYMMDD_HHMM>} &mdash; re-analyse one stored window now (ports {@code --window});</li>
 *   <li>{@code windows -start .. -end ..} &mdash; scan/backfill a range of windows (dry-run by default);</li>
 *   <li>{@code show <YYYYMMDD_HHMM>} &mdash; print the stored analysis record (read-only).</li>
 * </ul>
 * Registered against the running interpreter once the analyser exists (see {@code FSApp}).
 */
public final class AnalGroupCmdHandler extends CmdGroupHandler
{
    public static final String COMMAND = "anal";
    public static final String[] COMMAND_ALIASES = null;
    public static final String DESCRIPTION = "Analyser control,\nusage: " + COMMAND
            + " <start|pause|status|window <YYYYMMDD_HHMM>|windows -start .. -end ..|show <YYYYMMDD_HHMM>"
            + "|feedback [--days N]>";

    public AnalGroupCmdHandler(FSAnalyser analyser)
    {
        registerCmdHandler("start", new StartCmdHandler(analyser), "Start (resume) analysis.", null);
        registerCmdHandler("pause", new PauseCmdHandler(analyser), "Pause analysis.", null);
        registerCmdHandler("status", new StatusCmdHandler(analyser), "Show analyser status.", null);
        registerCmdHandler("window", new WindowCmdHandler(analyser),
                "Re-analyse a stored window now: window <YYYYMMDD_HHMM> (e.g. 20260517_2210).", null);
        registerCmdHandler("windows", new WindowsCmdHandler(analyser), WindowsCmdHandler.USAGE, null);
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

    private static final class StartCmdHandler implements ICmdHandler
    {
        private final FSAnalyser analyser_;

        private StartCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            analyser_.resume();
            UtilityFunctions.writeln(writer, "Analysis started.");
            return 0;
        }
    }

    private static final class PauseCmdHandler implements ICmdHandler
    {
        private final FSAnalyser analyser_;

        private PauseCmdHandler(FSAnalyser analyser)
        {
            analyser_ = analyser;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            analyser_.pause();
            UtilityFunctions.writeln(writer, "Analysis paused.");
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
        static final String USAGE = "Backfill a window range: windows -start <YYYYMMDD_HHMM> -end <YYYYMMDD_HHMM>"
                + " [-missing|-force] [-run] [-notify]";
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
            CmdArgParser parser = new CmdArgParser(args);
            parser.setValidOptionMetaData(VALID_OPTIONS);
            int code = 0;
            String[] start = parseDayKey(parser.getOptionValue("start"));
            String[] end = parseDayKey(parser.getOptionValue("end"));
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
