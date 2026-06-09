package com.finsent.analyse.signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;

/**
 * Rolling macro trend over a window of macro snapshots (ports Python {@code
 * analyse._compute_macro_trend}). Pure: the caller supplies the snapshots oldest-first (e.g. from
 * {@code IntervalSnapshotRegistry.range}, with any prev-trading-day stitch already applied); this
 * computes each indicator's direction/streak/cumulative change and rolls them up into
 * {@code {net_trend, sustained, lookback_windows, indicators{...}}}.
 */
public final class MacroTrend
{
    private static final int MIN_SNAPSHOTS = 3;
    private static final int STREAK_THRESHOLD = 4; // consecutive same-direction deltas = "sustained"
    private static final int DIRECTION_STREAK_MIN = 2;
    private static final String[] INDICATORS = { "VIX", "DXY", "SP500", "US10Y", "Gold" };
    /** The direction that is risk-off for each indicator. */
    private static final Map<String, String> RISK_OFF_DIR = Map.of(
            "VIX", "rising", "DXY", "rising", "SP500", "falling", "US10Y", "rising", "Gold", "rising");

    private MacroTrend()
    {
    }

    public static ObjectNode of(List<ObjectNode> snapshotsOldestFirst)
    {
        ObjectNode indicators = Json.newObject();
        String netTrend = "flat";
        boolean sustained = false;
        int lookbackWindows = 0;

        int snaps = snapshotsOldestFirst == null ? 0 : snapshotsOldestFirst.size();
        if (snaps >= MIN_SNAPSHOTS)
        {
            lookbackWindows = snaps - 1;
            int riskOff = 0;
            int riskOn = 0;
            int maxStreak = 0;
            for (String name : INDICATORS)
            {
                ObjectNode trend = indicatorTrend(name, snapshotsOldestFirst);
                indicators.set(name, trend);
                String direction = trend.path("direction").asText();
                if (!direction.equals("flat"))
                {
                    if (direction.equals(RISK_OFF_DIR.get(name)))
                    {
                        riskOff++;
                    }
                    else
                    {
                        riskOn++;
                    }
                    maxStreak = Math.max(maxStreak, trend.path("streak").asInt());
                }
            }
            sustained = maxStreak >= STREAK_THRESHOLD;
            netTrend = rollup(riskOff, riskOn);
        }

        ObjectNode result = Json.newObject();
        result.put("net_trend", netTrend);
        result.put("sustained", sustained);
        result.put("lookback_windows", lookbackWindows);
        result.set("indicators", indicators);
        return result;
    }

    /** Per-indicator {@code {direction, streak, cumulative_delta}} over the change_pct series. */
    private static ObjectNode indicatorTrend(String name, List<ObjectNode> snapshots)
    {
        List<Double> series = new ArrayList<>();
        for (ObjectNode snap : snapshots)
        {
            JsonNode indicator = snap.path("yahoo").get(name);
            series.add(indicator != null && !indicator.isNull()
                    ? indicator.path("change_pct").asDouble(0.0) : null);
        }

        String direction = "flat";
        int streak = 0;
        double cumulative = 0.0;
        if (hasAnyDelta(series))
        {
            int sign = streakSign(series);
            streak = streakLength(series);
            if (streak >= DIRECTION_STREAK_MIN && sign > 0)
            {
                direction = "rising";
            }
            else if (streak >= DIRECTION_STREAK_MIN && sign < 0)
            {
                direction = "falling";
            }
            cumulative = Num.round(lastValid(series) - firstValid(series), 2);
        }

        ObjectNode result = Json.newObject();
        result.put("direction", direction);
        result.put("streak", streak);
        result.put("cumulative_delta", cumulative);
        return result;
    }

    /** Length of the consecutive same-sign run of deltas counting back from the newest. */
    private static int streakLength(List<Double> series)
    {
        int streak = 0;
        int last = 0;
        for (int j = series.size() - 1; j > 0; j--)
        {
            Double cur = series.get(j);
            Double prev = series.get(j - 1);
            if (cur == null || prev == null)
            {
                break;
            }
            int sign = Double.compare(cur - prev, 0.0);
            if (sign == 0)
            {
                break;
            }
            if (last == 0)
            {
                last = sign;
                streak = 1;
            }
            else if (sign == last)
            {
                streak++;
            }
            else
            {
                break;
            }
        }
        return streak;
    }

    /** Sign of the newest delta run (matches the sign used by {@link #streakLength}). */
    private static int streakSign(List<Double> series)
    {
        int sign = 0;
        for (int j = series.size() - 1; j > 0; j--)
        {
            Double cur = series.get(j);
            Double prev = series.get(j - 1);
            if (cur != null && prev != null)
            {
                sign = Double.compare(cur - prev, 0.0);
                break;
            }
        }
        return sign;
    }

    private static boolean hasAnyDelta(List<Double> series)
    {
        boolean any = false;
        for (int j = 1; j < series.size(); j++)
        {
            if (series.get(j) != null && series.get(j - 1) != null)
            {
                any = true;
                break;
            }
        }
        return any;
    }

    private static double firstValid(List<Double> series)
    {
        double value = 0.0;
        for (Double v : series)
        {
            if (v != null)
            {
                value = v;
                break;
            }
        }
        return value;
    }

    private static double lastValid(List<Double> series)
    {
        double value = 0.0;
        for (int i = series.size() - 1; i >= 0; i--)
        {
            if (series.get(i) != null)
            {
                value = series.get(i);
                break;
            }
        }
        return value;
    }

    private static String rollup(int riskOff, int riskOn)
    {
        String netTrend;
        if (riskOff == 0 && riskOn == 0)
        {
            netTrend = "flat";
        }
        else if (riskOff > riskOn)
        {
            netTrend = "risk_off";
        }
        else if (riskOn > riskOff)
        {
            netTrend = "risk_on";
        }
        else
        {
            netTrend = "mixed";
        }
        return netTrend;
    }
}
