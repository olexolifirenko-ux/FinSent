package com.finsent.trade.cmd;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
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
 *   <li>{@code wbcheck [all]} &mdash; read-only WhiteBIT account snapshot (balances, futures summary,
 *       open positions; non-zero assets only unless {@code all}; no orders).</li>
 * </ul>
 * Registered against the running interpreter once the trader exists (see {@code FSApp}).
 */
public final class TradeGroupCmdHandler extends CmdGroupHandler
{
    public static final String COMMAND = "trade";
    public static final String[] COMMAND_ALIASES = null;
    public static final String DESCRIPTION = "Trader control,\nusage: " + COMMAND
            + " <on|off|status|flatten|wbcheck [all]>";

    public TradeGroupCmdHandler(FSTrader trader, WhiteBitClient whitebit)
    {
        registerCmdHandler("on", new OnCmdHandler(trader), "Turn trading on (resume).", new String[] {"start"});
        registerCmdHandler("off", new OffCmdHandler(trader), "Turn trading off (pause).", new String[] {"pause"});
        registerCmdHandler("status", new StatusCmdHandler(trader), "Show trader status and open position.", null);
        registerCmdHandler("flatten", new FlattenCmdHandler(trader), "Close the open position now.", null);
        registerCmdHandler("wbcheck", new WbCheckCmdHandler(whitebit),
                "Read-only WhiteBIT account snapshot: balances, futures summary, open positions; non-zero only "
                        + "unless 'all' (no orders).", null);
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
     * {@code trade wbcheck}: a read-only WhiteBIT account snapshot over the signed private API &mdash;
     * spot and collateral balances, the futures margin summary and any open positions &mdash; printing
     * each result or its error. Validates the API keys + HMAC signing and surfaces the account state the
     * live broker will reconcile against, without placing any order.
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
                // Default to non-zero assets only (the full lists are mostly zero rows); `all` shows everything.
                boolean all = args.length > 0 && args[0].equalsIgnoreCase("all");
                report(writer, "trade-account", () -> whitebit_.tradingBalance(""),
                        node -> nonZeroFields(node, all, value -> heldEntry(value)));
                report(writer, "collateral-account", whitebit_::collateralBalance,
                        node -> nonZeroFields(node, all, WbCheckCmdHandler::nonZero));
                report(writer, "collateral-summary", whitebit_::collateralSummary,
                        node -> nonZeroElements(node, all, value -> nonZero(value.path("balance"))));
                report(writer, "open-positions", whitebit_::openPositions, JsonNode::toString);
            }
            else
            {
                UtilityFunctions.writeln(writer, "WhiteBIT keys not set (WHITEBIT_API_KEY / WHITEBIT_API_SECRET).");
                code = 1;
            }
            return code;
        }

        private static void report(Writer writer, String label, BalanceCall call, Function<JsonNode, String> format)
        {
            try
            {
                UtilityFunctions.writeln(writer, label + ": " + format.apply(call.fetch()));
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

        /** True when a trade-account entry ({@code {available,freeze}}) holds anything. */
        private static boolean heldEntry(JsonNode entry)
        {
            return nonZero(entry.path("available")) || nonZero(entry.path("freeze"));
        }

        /** Keep only the fields of a {@code ticker -> value} object whose value is non-zero (unless {@code all}). */
        private static String nonZeroFields(JsonNode node, boolean all, Function<JsonNode, Boolean> keep)
        {
            String result;
            if (all || !node.isObject())
            {
                result = node.toString();
            }
            else
            {
                ObjectNode kept = Json.newObject();
                node.fields().forEachRemaining(field ->
                {
                    if (keep.apply(field.getValue()))
                    {
                        kept.set(field.getKey(), field.getValue());
                    }
                });
                result = kept.isEmpty() ? "(none)" : kept.toString();
            }
            return result;
        }

        /** Keep only the array elements that {@code keep} accepts (unless {@code all}). */
        private static String nonZeroElements(JsonNode node, boolean all, Function<JsonNode, Boolean> keep)
        {
            String result;
            if (all || !node.isArray())
            {
                result = node.toString();
            }
            else
            {
                ArrayNode kept = Json.newArray();
                for (JsonNode element : node)
                {
                    if (keep.apply(element))
                    {
                        kept.add(element);
                    }
                }
                result = kept.isEmpty() ? "(none)" : kept.toString();
            }
            return result;
        }

        /** Whether a numeric-string amount node is non-zero (tolerant of any decimal scale). */
        private static boolean nonZero(JsonNode amount)
        {
            String text = amount == null ? "" : amount.asText("");
            boolean zero;
            try
            {
                zero = new BigDecimal(text.isEmpty() ? "0" : text).signum() == 0;
            }
            catch (NumberFormatException notNumeric)
            {
                zero = text.isEmpty();
            }
            return !zero;
        }

        @FunctionalInterface
        private interface BalanceCall
        {
            JsonNode fetch() throws IOException, InterruptedException;
        }
    }
}
