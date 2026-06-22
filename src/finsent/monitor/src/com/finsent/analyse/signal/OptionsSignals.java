package com.finsent.analyse.signal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;

/**
 * Mechanical options signals over the per-interval snapshots produced by
 * {@code com.finsent.collect.OptionsFetcher} (ports Python {@code options.compute_options_delta}
 * and {@code options.compute_options_signal}). {@link #delta} measures the change between the
 * current and previous snapshot; {@link #signal} derives a priced-in reading (positioning, IV
 * elevation, OI surge, DVOL trend) the analyser folds into Claude's prompt context.
 */
public final class OptionsSignals
{
    /** Near-term put/call ratio above this reads bearish; below {@link #PC_RATIO_BULLISH_BELOW}, bullish. */
    private static final double PC_RATIO_BEARISH_ABOVE = 1.0;
    private static final double PC_RATIO_BULLISH_BELOW = 0.5;
    /** ATM IV above this (percent) suggests the market is bracing for a move. */
    private static final double IV_ELEVATED_ABOVE_PCT = 80.0;
    /** Near-term ATM IV below this (percent) reads the tape as complacent (option vol asleep). */
    private static final double IV_COMPLACENT_BELOW_PCT = 50.0;
    /** Open-interest change (percent) beyond this magnitude in the delta window counts as a surge. */
    private static final double OI_SURGE_ABS_PCT = 5.0;
    /** DVOL move (percent) beyond this magnitude over the last hour is "rising"/"falling", else "flat". */
    private static final double DVOL_TREND_PCT = 3.0;

    private OptionsSignals()
    {
    }

    /**
     * Compute the change between the {@code current} and {@code previous} snapshot. Returns
     * {@code {has_delta:false}} when there is no previous snapshot; otherwise the per-metric
     * now/prev/delta figures plus {@code total_oi_change_pct}. Ports
     * {@code options.compute_options_delta}.
     */
    public static ObjectNode delta(ObjectNode current, ObjectNode previous)
    {
        ObjectNode result = Json.newObject();
        if (previous == null || previous.isEmpty())
        {
            result.put("has_delta", false);
        }
        else
        {
            result.put("has_delta", true);
            copyAndDiff(result, current, previous, "pc_ratio");
            putNullable(result, "total_oi_change_pct", pctChange(current, previous, "total_oi"));
            copyAndDiff(result, current, previous, "near_pc_ratio");
            copyAndDiff(result, current, previous, "near_atm_iv");
            copyAndDiff(result, current, previous, "dvol");
        }
        return result;
    }

    /**
     * Derive the mechanical options signal from a snapshot and its delta. Returns
     * {@code {positioning, iv_elevated, oi_surge, signal_strength, near_pc_ratio, dvol_trend,
     * near_atm_iv, priced_in}}; a missing snapshot yields the neutral default. The direction view
     * ({@code positioning} / {@code signal_strength}) feeds the mechanical macro-alert confluence; the
     * {@code priced_in} view (complacent/braced) feeds the deep prompt as an amplifier-only context.
     * Ports {@code options.compute_options_signal}, extended with the priced-in read.
     */
    public static ObjectNode signal(ObjectNode snapshot, ObjectNode delta)
    {
        ObjectNode result = Json.newObject();
        if (snapshot == null || snapshot.isEmpty())
        {
            buildNeutral(result);
        }
        else
        {
            Double nearPc = optDouble(snapshot, "near_pc_ratio");
            String positioning = positioning(nearPc, optDouble(snapshot, "pc_ratio"));
            Double atmIv = optDouble(snapshot, "near_atm_iv");
            boolean ivElevated = ivElevated(atmIv);
            boolean oiSurge = oiSurge(delta);
            String dvolTrend = dvolTrend(optDouble(snapshot, "dvol"), optDouble(snapshot, "dvol_1h_ago"));
            int signals = (positioning.equals("neutral") ? 0 : 1) + (ivElevated ? 1 : 0) + (oiSurge ? 1 : 0);

            result.put("positioning", positioning);
            result.put("iv_elevated", ivElevated);
            result.put("oi_surge", oiSurge);
            result.put("signal_strength", strength(signals));
            putNullable(result, "near_pc_ratio", nearPc);
            result.put("dvol_trend", dvolTrend);
            putNullable(result, "near_atm_iv", atmIv);
            result.put("priced_in", pricedIn(atmIv, dvolTrend));
        }
        return result;
    }

    private static void buildNeutral(ObjectNode result)
    {
        result.put("positioning", "neutral");
        result.put("iv_elevated", false);
        result.put("oi_surge", false);
        result.put("signal_strength", "none");
        result.putNull("near_pc_ratio");
        result.putNull("dvol_trend");
        result.putNull("near_atm_iv");
        result.put("priced_in", "unknown");
    }

    /**
     * Priced-in / complacency read for the deep prompt: {@code braced} when option vol is elevated or
     * rising (the market expects a big move -- fragile, NOT "already priced"); {@code complacent} when
     * vol is low and not rising (a real catalyst would land unhedged, the surprise larger); else
     * {@code normal}. Amplifier-only by design -- the prompt may scale a confirmed catalyst with it but
     * never discount one.
     */
    private static String pricedIn(Double atmIv, String dvolTrend)
    {
        String verdict = "normal";
        if (ivElevated(atmIv) || "rising".equals(dvolTrend))
        {
            verdict = "braced";
        }
        else if (atmIv != null && atmIv < IV_COMPLACENT_BELOW_PCT)
        {
            verdict = "complacent";
        }
        return verdict;
    }

    /**
     * Positioning from the near-term put/call ratio (most sensitive to imminent positioning),
     * falling back to the aggregate ratio when near-term is unavailable.
     */
    private static String positioning(Double nearPc, Double aggregatePc)
    {
        Double pc = nearPc != null ? nearPc : aggregatePc;
        String positioning = "neutral";
        if (pc != null)
        {
            if (pc > PC_RATIO_BEARISH_ABOVE)
            {
                positioning = "bearish";
            }
            else if (pc < PC_RATIO_BULLISH_BELOW)
            {
                positioning = "bullish";
            }
        }
        return positioning;
    }

    private static boolean ivElevated(Double atmIv)
    {
        return atmIv != null && atmIv > IV_ELEVATED_ABOVE_PCT;
    }

    private static boolean oiSurge(ObjectNode delta)
    {
        boolean surge = false;
        if (delta != null && delta.path("has_delta").asBoolean(false))
        {
            Double change = optDouble(delta, "total_oi_change_pct");
            surge = change != null && Math.abs(change) > OI_SURGE_ABS_PCT;
        }
        return surge;
    }

    private static String dvolTrend(Double now, Double oneHourAgo)
    {
        String trend = null;
        if (now != null && oneHourAgo != null && oneHourAgo > 0.0)
        {
            double changePct = (now - oneHourAgo) / oneHourAgo * 100.0;
            if (changePct > DVOL_TREND_PCT)
            {
                trend = "rising";
            }
            else if (changePct < -DVOL_TREND_PCT)
            {
                trend = "falling";
            }
            else
            {
                trend = "flat";
            }
        }
        return trend;
    }

    private static String strength(int signals)
    {
        String strength = "none";
        if (signals >= 2)
        {
            strength = "strong";
        }
        else if (signals == 1)
        {
            strength = "moderate";
        }
        return strength;
    }

    /** Write {@code key_now}, {@code key_prev} and the rounded {@code key_delta} for a metric. */
    private static void copyAndDiff(ObjectNode result, ObjectNode current, ObjectNode previous, String key)
    {
        Double cur = optDouble(current, key);
        Double prev = optDouble(previous, key);
        putNullable(result, key + "_now", cur);
        putNullable(result, key + "_prev", prev);
        putNullable(result, key + "_delta", diff(cur, prev));
    }

    private static Double diff(Double a, Double b)
    {
        return (a != null && b != null) ? Num.round(a - b, 4) : null;
    }

    private static Double pctChange(ObjectNode current, ObjectNode previous, String key)
    {
        Double cur = optDouble(current, key);
        Double prev = optDouble(previous, key);
        Double change = null;
        if (cur != null && prev != null && prev != 0.0)
        {
            change = Num.round((cur - prev) / prev * 100.0, 2);
        }
        return change;
    }

    private static Double optDouble(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private static void putNullable(ObjectNode node, String field, Double value)
    {
        if (value == null)
        {
            node.putNull(field);
        }
        else
        {
            node.put(field, value.doubleValue());
        }
    }
}
