package com.finsent.analyse.signal;

import java.util.Locale;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;

/**
 * Mechanical scheduled-event signal (#21): given a data release's <b>manual consensus</b> and the
 * <b>fetched actual</b>, compute the <i>surprise</i> and map it to a BTC direction + impact tier. Pure
 * and deterministic — the market trades the surprise versus expectations, not the absolute number, so
 * this is mechanical, not a Claude call.
 *
 * <p>{@code hotDirection} is the BTC direction when the actual comes in <i>above</i> consensus (an
 * upside / "hot" surprise). For inflation (CPI/Core/PCE) and jobs (NFP) that is {@code bearish} — a hot
 * print is hawkish for the Fed, risk-off for BTC; for the unemployment rate it is {@code bullish} — a
 * hot print is labor slack, dovish. A below-consensus surprise flips the direction. A surprise within
 * {@code inlineBand} is in line (neutral / {@code noise}); beyond {@code highBand} it is {@code high}
 * impact, otherwise {@code low}. (NFP's hawkish→bearish read is the conventional risk-asset channel; it
 * is the one debatable mapping — adjust the event's {@code hotDirection} in config if you disagree.)
 */
public final class EconEventSignals
{
    private EconEventSignals()
    {
    }

    /** The signal object for a resolved release, mirroring the other mechanical signals' shape. */
    public static ObjectNode signal(String name, String unit, double consensus, double actual,
                                    String hotDirection, double inlineBand, double highBand)
    {
        double surprise = actual - consensus;
        double magnitude = Math.abs(surprise);
        String direction;
        String tier;
        if (magnitude <= inlineBand)
        {
            direction = "neutral";
            tier = "noise";
        }
        else
        {
            direction = surprise > 0 ? hotDirection : opposite(hotDirection);
            tier = magnitude > highBand ? "high" : "low";
        }
        ObjectNode signal = Json.newObject();
        signal.put("event", name);
        signal.put("consensus", Num.round(consensus, 4));
        signal.put("actual", Num.round(actual, 4));
        signal.put("surprise", Num.round(surprise, 4));
        signal.put("direction", direction);
        signal.put("impact_tier", tier);
        signal.put("label", label(name, unit, consensus, actual, surprise, direction, tier));
        return signal;
    }

    /** e.g. {@code "CPI MoM 0.5% vs 0.3% est (+0.2, high → bearish)"} / {@code "... (in line)"}. */
    private static String label(String name, String unit, double consensus, double actual,
                                double surprise, String direction, String tier)
    {
        String head = name + " " + num(actual) + unit + " vs " + num(consensus) + unit + " est";
        String detail = "neutral".equals(direction)
                ? "in line"
                : (surprise >= 0 ? "+" : "-") + num(Math.abs(surprise)) + unit + ", " + tier + " -> " + direction;
        return head + " (" + detail + ")";
    }

    /** Compact number: integer when whole, else up to 2 decimals with trailing zeros trimmed. */
    private static String num(double value)
    {
        String text;
        if (value == Math.rint(value))
        {
            text = String.format(Locale.ROOT, "%.0f", value);
        }
        else
        {
            text = String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    private static String opposite(String direction)
    {
        String flipped = "neutral";
        if ("bullish".equals(direction))
        {
            flipped = "bearish";
        }
        else if ("bearish".equals(direction))
        {
            flipped = "bullish";
        }
        return flipped;
    }
}
