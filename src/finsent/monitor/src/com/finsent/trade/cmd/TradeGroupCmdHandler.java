package com.finsent.trade.cmd;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.trade.FSTrader;
import com.finsent.trade.broker.whitebit.WhiteBitClient;
import com.finsent.util.CmdGroupHandler;
import com.finsent.util.ICmdHandler;
import com.finsent.util.UtilityFunctions;

/**
 * The {@code trade} command group: runtime control of the trading module over the command
 * interpreter (mirrors the {@code anal} group). Sub-commands:
 * <ul>
 *   <li>{@code on} / {@code off} / {@code status} &mdash; resume/pause acting on signals, show state
 *       ({@code start} / {@code pause} are accepted as aliases);</li>
 *   <li>{@code flatten} &mdash; close the open position now at the current price;</li>
 *   <li>{@code wbcheck} &mdash; read-only WhiteBIT connectivity test (account balances; no orders).</li>
 * </ul>
 * Registered against the running interpreter once the trader exists (see {@code FSApp}).
 */
public final class TradeGroupCmdHandler extends CmdGroupHandler
{
    public static final String COMMAND = "trade";
    public static final String[] COMMAND_ALIASES = null;
    public static final String DESCRIPTION = "Trader control,\nusage: " + COMMAND
            + " <on|off|status|flatten|wbcheck>";

    public TradeGroupCmdHandler(FSTrader trader, WhiteBitClient whitebit)
    {
        registerCmdHandler("on", new OnCmdHandler(trader), "Turn trading on (resume).", new String[] {"start"});
        registerCmdHandler("off", new OffCmdHandler(trader), "Turn trading off (pause).", new String[] {"pause"});
        registerCmdHandler("status", new StatusCmdHandler(trader), "Show trader status and open position.", null);
        registerCmdHandler("flatten", new FlattenCmdHandler(trader), "Close the open position now.", null);
        registerCmdHandler("wbcheck", new WbCheckCmdHandler(whitebit),
                "Read-only WhiteBIT connectivity test: fetch account balances (no orders).", null);
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

    /**
     * {@code trade wbcheck}: a read-only WhiteBIT connectivity test. Fetches the spot trade-account and
     * the collateral (futures) account balances over the signed private API and prints each result or
     * its error. Validates the API keys + HMAC signing without placing any order.
     */
    private static final class WbCheckCmdHandler implements ICmdHandler
    {
        private final WhiteBitClient whitebit_;

        private WbCheckCmdHandler(WhiteBitClient whitebit)
        {
            whitebit_ = whitebit;
        }

        @Override
        public int commandEntered(Writer writer, String command, String[] args)
        {
            int code = 0;
            if (whitebit_.configured())
            {
                report(writer, "trade-account", () -> whitebit_.tradingBalance(""));
                report(writer, "collateral-account", whitebit_::collateralBalance);
            }
            else
            {
                UtilityFunctions.writeln(writer, "WhiteBIT keys not set (WHITEBIT_API_KEY / WHITEBIT_API_SECRET).");
                code = 1;
            }
            return code;
        }

        private static void report(Writer writer, String label, BalanceCall call)
        {
            try
            {
                UtilityFunctions.writeln(writer, label + ": " + call.fetch().toString());
            }
            catch (IOException authOrNetworkError)
            {
                // A 401 (bad key/signature) surfaces here with WhiteBIT's error body -- the point of the check.
                UtilityFunctions.writeln(writer, label + " FAILED: " + authOrNetworkError.getMessage());
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                UtilityFunctions.writeln(writer, label + " interrupted.");
            }
        }

        @FunctionalInterface
        private interface BalanceCall
        {
            JsonNode fetch() throws IOException, InterruptedException;
        }
    }
}
