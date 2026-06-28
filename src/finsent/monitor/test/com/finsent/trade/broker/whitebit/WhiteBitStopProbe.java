package com.finsent.trade.broker.whitebit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.trade.Side;
import com.finsent.trade.broker.VenueState;

/**
 * One-shot <b>LIVE</b> probe that validates the <i>standalone</i> {@code trigger-market} stop mechanism the
 * trader's {@code venueStop} will use, before it is trusted on real size. (An earlier run proved the
 * order-level OTO {@code stopLoss} param produces a stop the venue acknowledges but does not surface in
 * {@code /conditional-orders} or the position {@code tpsl} -- unobservable, so unusable.) It opens a plain
 * long, places a separate far-away {@code trigger-market} SELL stop, and answers:
 * <ol>
 *   <li><b>Observable?</b> does the standalone stop appear in {@code /conditional-orders} (so we can see and
 *       cancel it)? &mdash; read the AFTER STOP PLACED count.</li>
 *   <li><b>Auto-cancel on flat?</b> after the position is closed <i>without</i> us cancelling the stop, is it
 *       gone (reduce-only-like, safe) or still resting (a naked order that could later flip the account)?</li>
 *   <li><b>Trigger direction:</b> does {@code activation_condition} (gte/lte) infer correctly from side+price?
 *       &mdash; read it from the AFTER STOP PLACED conditional-orders dump.</li>
 * </ol>
 *
 * <p><b>This places REAL orders on WhiteBIT (live money).</b> It is deliberately safe: the smallest amount
 * (0.0001 BTC), the protective stop set far away so it can <i>never</i> trigger during the run, an explicit
 * {@code --yes-place-live-orders} confirmation, a pre-check that the account is flat, and a {@code finally}
 * cleanup that cancels leftover conditionals and flattens any leftover position. It still requires API keys
 * with order permission and a configured market leverage. <b>Always confirm the account is flat in the
 * WhiteBIT UI afterwards.</b>
 *
 * <p>Usage: {@code WhiteBitStopProbe [baseUrl] [market] --yes-place-live-orders}, with
 * {@code WHITEBIT_API_KEY}/{@code WHITEBIT_API_SECRET} in the environment.
 */
public final class WhiteBitStopProbe
{
    private static final String AMOUNT = "0.0001";          // the market minimum -- a few dollars of notional
    private static final double STOP_AWAY_FRACTION = 0.5;   // stop 50% below price -> cannot trigger during the probe
    private static final long HOLD_SECONDS = 5;             // hold the position briefly so the stop can be observed
                                                            // (in the dumps and the WhiteBIT UI) before the close
    private static final String CONFIRM = "--yes-place-live-orders";
    private static final String CLEANUP = "--cleanup";      // cancel resting stop orders only; place nothing

    private WhiteBitStopProbe()
    {
    }

    public static void main(String[] args) throws IOException, InterruptedException
    {
        boolean confirmed = false;
        boolean cleanupOnly = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args)
        {
            if (CONFIRM.equals(arg)) { confirmed = true; }
            else if (CLEANUP.equals(arg)) { cleanupOnly = true; }
            else { positional.add(arg); }
        }
        String baseUrl = positional.size() > 0 ? positional.get(0) : "https://whitebit.com";
        String market = positional.size() > 1 ? positional.get(1) : "BTC_USDT";
        String key = env("WHITEBIT_API_KEY");
        String secret = env("WHITEBIT_API_SECRET");
        if (!confirmed || key.isEmpty() || secret.isEmpty())
        {
            usage(confirmed, key.isEmpty() || secret.isEmpty());
        }
        else
        {
            banner(market, baseUrl);
            WhiteBitClient client = new WhiteBitClient(key, secret, baseUrl, market);
            if (cleanupOnly)
            {
                cleanupOnly(client);
            }
            else
            {
                probe(client, market);
            }
        }
    }

    /** Cancel any resting stop/trigger orders on the market and report -- places nothing (the {@code --cleanup} mode). */
    private static void cleanupOnly(WhiteBitClient client) throws IOException, InterruptedException
    {
        System.out.println("CLEANUP ONLY -- cancelling resting stop/trigger orders; placing no new orders.");
        dump("Active orders BEFORE", client.activeOrders());
        cancelStops(client);
        dump("Active orders AFTER", client.activeOrders());
        dump("Open position", client.openPositions());
        System.out.println("cleanup-only done -- verify the account in the WhiteBIT UI.");
    }

    private static void probe(WhiteBitClient client, String market) throws IOException, InterruptedException
    {
        if (ensureFlat(client))
        {
            try
            {
                runProbe(client, market);
            }
            finally
            {
                cleanup(client);
            }
        }
    }

    private static void runProbe(WhiteBitClient client, String market) throws IOException, InterruptedException
    {
        Double price = client.lastPrice();
        if (price == null)
        {
            System.out.println("ABORT: no venue price available -- not placing anything.");
        }
        else
        {
            String stop = WhiteBitBroker.formatPrice(price * (1.0 - STOP_AWAY_FRACTION)); // far below -> can't fire
            // 1. Open a plain long (no OTO param -- that mechanism proved unobservable).
            System.out.printf("Placing plain BUY %s %s at ~%.2f (no bracket)...%n", AMOUNT, market, price);
            System.out.println("  entry response: " + client.placeCollateralMarketOrder("buy", AMOUNT, "", ""));
            // 2. Place a STANDALONE trigger-market SELL stop to protect it -- the observable mechanism.
            System.out.println("Placing a standalone trigger-market SELL stop @ " + stop + " (far below -> won't fire)...");
            System.out.println("  stop response: " + client.placeCollateralTriggerMarket("sell", AMOUNT, stop, ""));
            hold();
            // 3. Is the standalone stop observable / manageable? (it rests in active orders, not conditional-orders)
            dump("AFTER STOP PLACED -- open position", client.openPositions());
            dump("AFTER STOP PLACED -- conditional orders (expected empty -- the stop is NOT here)", client.conditionalOrders());
            int afterPlace = report("AFTER STOP PLACED", client);
            // 4. Close the position WITHOUT cancelling the stop -- the decisive auto-cancel-on-flat test.
            closeLong(client);
            int afterClose = report("AFTER CLOSE (stop NOT cancelled by us)", client);
            conclude(afterPlace, afterClose, client);
        }
    }

    /** Print the active stop/trigger orders for the market with a count (where standalone stops rest); returns the count. */
    private static int report(String label, WhiteBitClient client) throws IOException, InterruptedException
    {
        JsonNode active = client.activeOrders();
        int stops = WhiteBitBroker.protectiveStopIds(active).size();
        System.out.println();
        System.out.println(label + " -- active stop orders (" + stops + " resting):");
        System.out.println("  " + active);
        return stops;
    }

    /** Print a raw JSON response under a label (the open-positions body is where a position-linked stop lives). */
    private static void dump(String label, JsonNode json)
    {
        System.out.println();
        System.out.println(label + ":");
        System.out.println("  " + json);
    }

    /** Hold the (far-stopped, can't-fire) position open briefly so the stop can be observed before the close. */
    private static void hold()
    {
        if (HOLD_SECONDS > 0)
        {
            System.out.println();
            System.out.println("Holding " + HOLD_SECONDS + "s -- check the position and its SL/TP in the WhiteBIT UI now...");
            try
            {
                Thread.sleep(HOLD_SECONDS * 1000L);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Close the long by selling the venue's actual open amount (no stop attached to the close). */
    private static void closeLong(WhiteBitClient client) throws IOException, InterruptedException
    {
        VenueState venue = WhiteBitBroker.parsePositions(client.openPositions());
        if (venue.kind() == VenueState.Kind.OPEN)
        {
            String amount = WhiteBitBroker.formatAmount(venue.qty());
            System.out.println();
            System.out.println("Closing: SELL " + amount + " (the venue's open amount)...");
            System.out.println("  close response: " + client.placeCollateralMarketOrder("sell", amount, "", ""));
        }
        else
        {
            System.out.println();
            System.out.println("Position already flat before the explicit close (did the stop fire? it shouldn't have).");
        }
    }

    private static void conclude(int afterPlace, int afterClose, WhiteBitClient client)
            throws IOException, InterruptedException
    {
        boolean flat = WhiteBitBroker.parsePositions(client.openPositions()).kind() != VenueState.Kind.OPEN;
        System.out.println();
        System.out.println("=== PROBE RESULT (standalone trigger-market stop) ===");
        System.out.println("- Stop observable after placement (active stop orders): " + afterPlace
                + (afterPlace >= 1 ? " -- YES, we can see it and cancel it." : " -- NO (unexpected) -- read the dumps."));
        System.out.println("- Auto-cancel on flat (active stop orders still resting after close, WITHOUT us cancelling): "
                + afterClose + (afterClose == 0
                ? " -- gone: reduce-only-like, no naked-flip risk."
                : " -- STILL RESTING: it does NOT auto-cancel, so the close path MUST cancel it (it can -- it is "
                        + "observable). Residual gap-race remains; the probe cleanup cancels it below."));
        System.out.println("- Account flat after cleanup: " + (flat ? "YES" : "NO -- CHECK THE WHITEBIT UI NOW"));
    }

    /** Refuse to probe on top of an existing position; the probe must start and end flat. */
    private static boolean ensureFlat(WhiteBitClient client) throws IOException, InterruptedException
    {
        VenueState venue = WhiteBitBroker.parsePositions(client.openPositions());
        boolean flat = venue.kind() != VenueState.Kind.OPEN;
        if (!flat)
        {
            System.out.println("ABORT: account is not flat (holds an open " + venue.side()
                    + ") -- close it first, then re-run the probe.");
        }
        return flat;
    }

    /** Best-effort teardown: cancel any resting stop order and flatten any leftover position. */
    private static void cleanup(WhiteBitClient client)
    {
        try
        {
            cancelStops(client);
            flattenLeftover(client);
            System.out.println("cleanup: done -- verify the account is FLAT with no open orders in the WhiteBIT UI.");
        }
        catch (IOException cleanupFailed)
        {
            System.out.println("cleanup FAILED -- CHECK THE ACCOUNT MANUALLY NOW: " + cleanupFailed.getMessage());
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            System.out.println("cleanup interrupted -- CHECK THE ACCOUNT MANUALLY NOW.");
        }
    }

    /** Cancel every resting stop/trigger order on the market (from the active-orders list). */
    private static void cancelStops(WhiteBitClient client) throws IOException, InterruptedException
    {
        for (String orderId : WhiteBitBroker.protectiveStopIds(client.activeOrders()))
        {
            System.out.println("cleanup: cancelling resting stop " + orderId);
            client.cancelOrder(orderId);
        }
    }

    private static void flattenLeftover(WhiteBitClient client) throws IOException, InterruptedException
    {
        VenueState venue = WhiteBitBroker.parsePositions(client.openPositions());
        if (venue.kind() == VenueState.Kind.OPEN)
        {
            String side = venue.side() == Side.LONG ? "sell" : "buy";
            System.out.println("cleanup: flattening leftover " + venue.side());
            client.placeCollateralMarketOrder(side, WhiteBitBroker.formatAmount(venue.qty()), "", "");
        }
    }

    private static void banner(String market, String baseUrl)
    {
        System.out.println("================================================================");
        System.out.println(" WhiteBIT venueStop LIVE PROBE -- THIS PLACES REAL ORDERS");
        System.out.println(" market=" + market + " baseUrl=" + baseUrl + " amount=" + AMOUNT);
        System.out.println("================================================================");
    }

    private static void usage(boolean confirmed, boolean missingKeys)
    {
        if (!confirmed)
        {
            System.out.println("Refusing to run: pass " + CONFIRM + " to acknowledge this places REAL live orders.");
        }
        if (missingKeys)
        {
            System.out.println("Missing WHITEBIT_API_KEY / WHITEBIT_API_SECRET in the environment.");
        }
        System.out.println("Usage: WhiteBitStopProbe [baseUrl] [market] " + CONFIRM
                + "   (keys via WHITEBIT_API_KEY / WHITEBIT_API_SECRET)");
    }

    private static String env(String name)
    {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
