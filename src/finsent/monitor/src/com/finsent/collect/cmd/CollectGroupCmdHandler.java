package com.finsent.collect.cmd;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.finsent.collect.EconScheduler;
import com.finsent.collect.FSCollector;
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
 * </ul>
 * The {@code econ} fetch is fetch-only (stores the actual but does not analyse/notify, so a back-dated
 * catch-up never fires a stale alert -- run {@code anal econ} afterwards). Registered once the components
 * exist (see {@code FSApp}).
 */
public final class CollectGroupCmdHandler extends CmdGroupHandler
{
    public static final String COMMAND = "collect";
    public static final String[] COMMAND_ALIASES = null;
    public static final String DESCRIPTION = "Collector control,\nusage: " + COMMAND
            + " <econ [YYYYMMDD] <event name> | x <on|off|status>>";

    public CollectGroupCmdHandler(EconScheduler econScheduler, FSCollector collector)
    {
        registerCmdHandler("econ", new EconFetchCmdHandler(econScheduler),
                "Fetch a scheduled release's BLS actual on demand: econ [YYYYMMDD] <event name> "
                        + "(day defaults to today). Fetch-only -- run `anal econ` afterwards to analyse.", null);
        registerCmdHandler("x", new XToggleCmdHandler(collector),
                "Turn the X (Twitter) source's polling on/off at runtime: x <on|off|status>.", null);
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
