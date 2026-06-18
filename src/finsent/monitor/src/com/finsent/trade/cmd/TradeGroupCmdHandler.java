package com.finsent.trade.cmd;

import java.io.Writer;
import java.time.Instant;

import com.finsent.trade.FSTrader;
import com.finsent.util.CmdGroupHandler;
import com.finsent.util.ICmdHandler;
import com.finsent.util.UtilityFunctions;

/**
 * The {@code trade} command group: runtime control of the trading module over the command
 * interpreter (mirrors the {@code anal} group). Sub-commands:
 * <ul>
 *   <li>{@code on} / {@code off} / {@code status} &mdash; resume/pause acting on signals, show state
 *       ({@code start} / {@code pause} are accepted as aliases);</li>
 *   <li>{@code flatten} &mdash; close the open position now at the current price.</li>
 * </ul>
 * Registered against the running interpreter once the trader exists (see {@code FSApp}).
 */
public final class TradeGroupCmdHandler extends CmdGroupHandler
{
    public static final String COMMAND = "trade";
    public static final String[] COMMAND_ALIASES = null;
    public static final String DESCRIPTION = "Trader control,\nusage: " + COMMAND + " <on|off|status|flatten>";

    public TradeGroupCmdHandler(FSTrader trader)
    {
        registerCmdHandler("on", new OnCmdHandler(trader), "Turn trading on (resume).", new String[] {"start"});
        registerCmdHandler("off", new OffCmdHandler(trader), "Turn trading off (pause).", new String[] {"pause"});
        registerCmdHandler("status", new StatusCmdHandler(trader), "Show trader status and open position.", null);
        registerCmdHandler("flatten", new FlattenCmdHandler(trader), "Close the open position now.", null);
    }

    private static final class OnCmdHandler implements ICmdHandler
    {
        private final FSTrader trader_;

        private OnCmdHandler(FSTrader trader)
        {
            trader_ = trader;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            trader_.resume();
            UtilityFunctions.writeln(writer, "Trading ON (running).");
            return 0;
        }
    }

    private static final class OffCmdHandler implements ICmdHandler
    {
        private final FSTrader trader_;

        private OffCmdHandler(FSTrader trader)
        {
            trader_ = trader;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            trader_.pause();
            UtilityFunctions.writeln(writer, "Trading OFF (paused).");
            return 0;
        }
    }

    private static final class StatusCmdHandler implements ICmdHandler
    {
        private final FSTrader trader_;

        private StatusCmdHandler(FSTrader trader)
        {
            trader_ = trader;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            UtilityFunctions.writeln(writer, trader_.describe(Instant.now()));
            return 0;
        }
    }

    private static final class FlattenCmdHandler implements ICmdHandler
    {
        private final FSTrader trader_;

        private FlattenCmdHandler(FSTrader trader)
        {
            trader_ = trader;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            UtilityFunctions.writeln(writer, trader_.flatten(Instant.now()));
            return 0;
        }
    }
}
