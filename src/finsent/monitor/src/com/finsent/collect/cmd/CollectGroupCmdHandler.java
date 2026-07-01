package com.finsent.collect.cmd;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.finsent.collect.EconScheduler;
import com.finsent.collect.FSCollector;
import com.finsent.collect.source.ArticleSources;
import com.finsent.core.Config;
import com.finsent.directory.DirectorySystem;
import com.finsent.util.CmdGroupHandler;
import com.finsent.util.ICmdHandler;
import com.finsent.util.UtilityFunctions;

/**
 * The {@code collect} command group: on-demand collector operations over the command interpreter.
 * Sub-commands:
 * <ul>
 *   <li>{@code econ [YYYYMMDD] <event name>} &mdash; fetch a scheduled release's BLS actual on demand
 *       (the catch-up for a release the scheduler missed, e.g. one that fired while the app was down).</li>
 *   <li>{@code x <on|off|status>} &mdash; turn the fast X (Twitter) amplifier source's polling on/off
 *       at runtime (no restart), or report its state. The initial state comes from the {@code -DfetchX}
 *       launcher flag.</li>
 *   <li>{@code list} &mdash; print the configured-source manifest across both lanes (the same one logged
 *       at startup).</li>
 *   <li>{@code mergein <file>} &mdash; ingest articles from an external articles JSONL file (e.g. another
 *       instance's {@code articles_<day>.jsonl}) into the store, with fresh ids and content-hash dedup.</li>
 * </ul>
 * The {@code econ} fetch is fetch-only (stores the actual but does not analyse/notify, so a back-dated
 * catch-up never fires a stale alert -- run {@code anal econ} afterwards). The {@code mergein} ingest is
 * likewise store-only -- run {@code anal windows ... -force} afterwards to analyse the merged windows.
 * Registered once the components exist (see {@code FSApp}).
 */
public final class CollectGroupCmdHandler extends CmdGroupHandler
{
    public static final String COMMAND = "collect";
    public static final String[] COMMAND_ALIASES = null;
    public static final String DESCRIPTION = "Collector control,\nusage: " + COMMAND
            + " <econ [YYYYMMDD] <event name> | x <on|off|status> | list | mergein <file>>";

    public CollectGroupCmdHandler(EconScheduler econScheduler, FSCollector collector, Config config)
    {
        registerCmdHandler("econ", new EconFetchCmdHandler(econScheduler),
                "Fetch a scheduled release's BLS actual on demand: econ [YYYYMMDD] <event name> "
                        + "(day defaults to today). Fetch-only -- run `anal econ` afterwards to analyse.", null);
        registerCmdHandler("x", new XToggleCmdHandler(collector),
                "Turn the X (Twitter) source's polling on/off at runtime: x <on|off|status>.", null);
        registerCmdHandler("list", new ListCmdHandler(config),
                "List the configured sources across both lanes: list.", null);
        registerCmdHandler("mergein", new MergeInCmdHandler(collector),
                "Ingest articles from an external JSONL file (fresh ids, content-hash dedup): mergein <file> "
                        + "(a relative path roots at the release home). Read-only on the source -- run "
                        + "`anal windows ... -force` afterwards to analyse.", null);
    }

    /**
     * {@code collect mergein <file>}: ingest articles from an external articles JSONL file (e.g. another
     * instance's {@code articles_<day>.jsonl}) into the store. Fresh ids are assigned and content-hash dedup
     * applies, so duplicates are skipped and no id collides. The path resolves like every other resource
     * path (a relative path roots at the release home, an absolute path is used as-is); the source file is
     * read only. Store-only -- it prints the exact {@code anal windows ... -force} follow-up for the affected
     * days.
     */
    private static final class MergeInCmdHandler implements ICmdHandler
    {
        private static final String USAGE = "Usage: collect mergein <file>";
        private final FSCollector collector_;

        private MergeInCmdHandler(FSCollector collector)
        {
            collector_ = collector;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            int code = 0;
            String pathArg = String.join(" ", args).trim(); // a path may contain spaces
            if (pathArg.isEmpty())
            {
                UtilityFunctions.writeln(writer, USAGE);
                code = 1;
            }
            else
            {
                code = runMerge(writer, pathArg);
            }
            return code;
        }

        private int runMerge(Writer writer, String pathArg)
        {
            int code = 0;
            // Resolve like every other resource path: a relative path roots at the release home
            // (-Dfinsent.home), an absolute path is used as-is -- so it does not depend on the CWD.
            Path file = DirectorySystem.resolveToFile(pathArg).toPath();
            if (!Files.isRegularFile(file))
            {
                UtilityFunctions.writeln(writer, "No such file: " + file);
                code = 1;
            }
            else
            {
                code = ingest(writer, file);
            }
            return code;
        }

        private int ingest(Writer writer, Path file)
        {
            int code = 0;
            UtilityFunctions.writeln(writer, "Merging articles from " + file + "...");
            try
            {
                report(writer, collector_.mergeIn(file));
            }
            catch (Exception mergeFailed)
            {
                // Abort just this command; the interpreter thread stays alive for the next command.
                UtilityFunctions.writeln(writer, "Merge failed (aborted): " + mergeFailed);
                code = 1;
            }
            return code;
        }

        private void report(Writer writer, FSCollector.MergeReport report)
        {
            UtilityFunctions.writeln(writer, "Merged: " + report.stored() + " new, " + report.duplicates()
                    + " duplicate(s), " + report.skipped() + " skipped (of " + report.read() + " read).");
            if (report.firstDay() != null)
            {
                String range = report.lastDay().equals(report.firstDay())
                        ? report.firstDay() : report.firstDay() + " .. " + report.lastDay();
                UtilityFunctions.writeln(writer, "Affected days: " + range + ".");
                UtilityFunctions.writeln(writer, "Now analyse:  anal windows " + report.firstDay()
                        + " 00:00 " + report.lastDay() + " 23:59 -force");
            }
        }
    }

    /** {@code collect list}: print the configured-source manifest (both lanes) -- the same one logged at start. */
    private static final class ListCmdHandler implements ICmdHandler
    {
        private final Config config_;

        private ListCmdHandler(Config config)
        {
            config_ = config;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            UtilityFunctions.writeln(writer, "Configured sources:");
            for (String line : ArticleSources.describe(config_))
            {
                UtilityFunctions.writeln(writer, "  " + line);
            }
            return 0;
        }
    }

    /** {@code collect x <on|off|status>}: toggle (or report) the X amplifier source's polling at runtime. */
    private static final class XToggleCmdHandler implements ICmdHandler
    {
        private static final String USAGE = "Usage: collect x <on|off|status>";
        private final FSCollector collector_;

        private XToggleCmdHandler(FSCollector collector)
        {
            collector_ = collector;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            int code = 0;
            String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
            if (sub.equals("on"))
            {
                code = setX(writer, true);
            }
            else if (sub.equals("off"))
            {
                code = setX(writer, false);
            }
            else if (sub.equals("status"))
            {
                UtilityFunctions.writeln(writer, "X fetching: " + state());
            }
            else
            {
                UtilityFunctions.writeln(writer, USAGE);
                code = 1;
            }
            return code;
        }

        private int setX(Writer writer, boolean on)
        {
            int code = 0;
            if (collector_.xConfigured())
            {
                collector_.setXEnabled(on);
                UtilityFunctions.writeln(writer, "X fetching " + (on ? "ON" : "OFF") + ".");
            }
            else
            {
                UtilityFunctions.writeln(writer, "X source not configured (needs getxapiKey + <XAccounts>).");
                code = 1;
            }
            return code;
        }

        private String state()
        {
            return !collector_.xConfigured() ? "not configured" : (collector_.xEnabled() ? "ON" : "OFF");
        }
    }

    /**
     * {@code collect econ [YYYYMMDD] <event name>}: fetch a scheduled release's actual now. An optional
     * leading {@code YYYYMMDD} pins the release day (defaults to today); the event name may contain spaces
     * (the remaining tokens joined).
     */
    private static final class EconFetchCmdHandler implements ICmdHandler
    {
        private static final String USAGE = "Usage: collect econ [YYYYMMDD] <event name> (e.g. econ CPI MoM)";
        private final EconScheduler scheduler_;

        private EconFetchCmdHandler(EconScheduler scheduler)
        {
            scheduler_ = scheduler;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            int code = 0;
            List<String> rest = new ArrayList<>(List.of(args));
            String day = !rest.isEmpty() && rest.get(0).matches("\\d{8}") ? rest.remove(0) : null;
            String eventName = String.join(" ", rest).trim();
            if (eventName.isEmpty())
            {
                UtilityFunctions.writeln(writer, USAGE);
                code = 1;
            }
            else
            {
                code = runFetch(writer, day, eventName);
            }
            return code;
        }

        private int runFetch(Writer writer, String day, String eventName)
        {
            int code = 0;
            UtilityFunctions.writeln(writer, "Fetching BLS actual for '" + eventName + "'"
                    + (day == null ? " (today)" : " on " + day) + "...");
            try
            {
                UtilityFunctions.writeln(writer, scheduler_.resolveNow(day, eventName));
            }
            catch (Exception fetchFailed)
            {
                // Abort just this command; the interpreter thread stays alive for the next command.
                UtilityFunctions.writeln(writer, "Econ fetch failed (aborted): " + fetchFailed);
                code = 1;
            }
            return code;
        }
    }
}
