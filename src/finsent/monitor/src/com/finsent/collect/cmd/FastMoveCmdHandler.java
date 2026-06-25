package com.finsent.collect.cmd;

import java.io.Writer;
import java.util.Locale;

import com.finsent.collect.FastMovePoller;
import com.finsent.util.ICmdHandler;
import com.finsent.util.UtilityFunctions;

/**
 * The {@code fastmove} command: runtime control of the FastMove momentum poller --
 * {@code fastmove <on|off|status>} turns its detection on/off (no restart) or reports its state. The
 * poller thread always runs and is gated by a pause flag, so the toggle is live. The initial state comes
 * from the {@code -DrunFastMove} launcher flag (default off &rarr; start paused, like the analyser/trader).
 * Registered once the poller exists (see {@code FSApp}).
 */
public final class FastMoveCmdHandler implements ICmdHandler
{
    public static final String COMMAND = "fastmove";
    public static final String[] COMMAND_ALIASES = null;
    public static final String DESCRIPTION = "FastMove momentum poller control,\nusage: " + COMMAND
            + " <on|off|status>";

    private static final String USAGE = "Usage: fastmove <on|off|status>";

    private final FastMovePoller poller_;

    public FastMoveCmdHandler(FastMovePoller poller)
    {
        poller_ = poller;
    }

    @Override
    public int commandEntered(Writer writer, String command, String[] args)
    {
        int code = 0;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("on"))
        {
            poller_.resume();
            UtilityFunctions.writeln(writer, "FastMove ON (running).");
        }
        else if (sub.equals("off"))
        {
            poller_.pause();
            UtilityFunctions.writeln(writer, "FastMove OFF (paused).");
        }
        else if (sub.equals("status"))
        {
            UtilityFunctions.writeln(writer, "FastMove: " + poller_.status() + ".");
        }
        else
        {
            UtilityFunctions.writeln(writer, USAGE);
            code = 1;
        }
        return code;
    }
}
