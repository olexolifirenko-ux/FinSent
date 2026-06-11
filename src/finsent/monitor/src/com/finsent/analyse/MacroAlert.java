package com.finsent.analyse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.signal.MacroSignals;
import com.finsent.core.Config.MacroThresholds;
import com.finsent.core.Json;
import com.finsent.core.Num;

/**
 * The standalone macro-alert path (ports Python {@code analyse._detect_macro_alert} and
 * {@code macro_only_assessment}): when a monitoring window carries no resonant news but the macro
 * tape moves enough, produce a mechanical directional assessment (the prior the analyser escalates
 * to Claude). Both methods here are pure; the runtime concerns &mdash; weekend skip, snapshot
 * loading, persistence and dispatch &mdash; live in the Stage-7 wiring that drives them.
 */
public final class MacroAlert
{
    private static final int TREND_STREAK_THRESHOLD = 4; // matches Python _MACRO_TREND_STREAK_THRESHOLD
    private static final double EXTREME_MULTIPLE = 2.0;
    private static final double MISSING_THRESHOLD = 999.0; // Python's get(name, 999) sentinel

    /** The delta direction that is risk-off for each indicator (Python {@code _MACRO_RISK_OFF_DIRECTION}). */
    private static final Map<String, String> RISK_OFF_DIRECTION = Map.of(
            "VIX", "up", "DXY", "up", "SP500", "down", "US10Y", "up", "Gold", "up");

    private MacroAlert()
    {
    }

    /** A macro indicator and its breach threshold, in the canonical evaluation order. */
    private record Gauge(String name, double threshold)
    {
    }

    /**
     * Compare the current macro snapshot against the previous one for significant moves. Returns the
     * list of triggered indicators ({@code {name, change_pct, prev_change_pct, delta_pct}}), or
     * {@code null} when nothing breaches or the weighted gate is not met. Gate: any single indicator
     * at &ge; 2&times; threshold, OR 2+ indicators breaching, OR one breach confirmed by a strong
     * options signal.
     */
    public static ArrayNode detect(MacroThresholds thresholds, ObjectNode currentSnap, ObjectNode prevSnap,
                                   ObjectNode optionsSignal)
    {
        ArrayNode result = null;
        JsonNode curYahoo = currentSnap == null ? null : currentSnap.get("yahoo");
        if (curYahoo != null && curYahoo.size() > 0)
        {
            JsonNode prevYahoo = prevSnap == null ? null : prevSnap.get("yahoo");
            ArrayNode triggers = Json.newArray();
            boolean extreme = false;
            for (Gauge gauge : gauges(thresholds))
            {
                extreme = collectTrigger(triggers, gauge, curYahoo, prevYahoo) || extreme;
            }
            result = gate(triggers, extreme, optionsSignal);
        }
        return result;
    }

    /**
     * Build the mechanical macro-alert record from the triggers and pre-computed context (regime via
     * {@link MacroSignals}, the supplied options signal and rolling macro trend). {@code analyzedAt}
     * is injected so the result is deterministic. Returns
     * {@code {analyzed_at, source, triggers, direction, impact_tier, macro_regime, reasoning[,
     * options_signal][, macro_trend]}}.
     */
    public static ObjectNode assess(MacroThresholds thresholds, ObjectNode macroSnap, ArrayNode triggers,
                                    ObjectNode optionsSignal, ObjectNode macroTrend, String analyzedAt)
    {
        String regime = MacroSignals.regime(macroSnap).path("regime").asText();
        String direction = direction(regime, triggers);
        String impactTier = impactTier(thresholds, triggers, optionsSignal, macroTrend, direction);
        String reasoning = reasoning(triggers, regime, optionsSignal, macroTrend);

        ObjectNode result = Json.newObject();
        result.put("analyzed_at", analyzedAt);
        result.put("source", "macro_mechanical");
        result.set("triggers", triggers);
        result.put("direction", direction);
        result.put("impact_tier", impactTier);
        result.put("macro_regime", regime);
        result.put("reasoning", reasoning);
        if (optionsSignal != null)
        {
            result.set("options_signal", optionsSignal);
        }
        if (macroTrend != null && !macroTrend.path("net_trend").asText("flat").equals("flat"))
        {
            result.set("macro_trend", macroTrend);
        }
        return result;
    }

    private static boolean collectTrigger(ArrayNode triggers, Gauge gauge, JsonNode curYahoo, JsonNode prevYahoo)
    {
        boolean extreme = false;
        JsonNode cur = curYahoo.get(gauge.name());
        if (cur != null && !cur.isNull())
        {
            double curChg = cur.path("change_pct").asDouble(0.0);
            JsonNode prev = prevYahoo == null ? null : prevYahoo.get(gauge.name());
            boolean hasPrev = prev != null && !prev.isNull();
            double prevChg = hasPrev ? prev.path("change_pct").asDouble(0.0) : 0.0;
            double delta = hasPrev ? curChg - prevChg : curChg;
            if (Math.abs(delta) >= gauge.threshold())
            {
                triggers.add(trigger(gauge.name(), curChg, hasPrev ? prevChg : null, delta));
                extreme = Math.abs(delta) >= gauge.threshold() * EXTREME_MULTIPLE;
            }
        }
        return extreme;
    }

    private static ObjectNode trigger(String name, double curChg, Double prevChg, double delta)
    {
        ObjectNode node = Json.newObject();
        node.put("name", name);
        node.put("change_pct", curChg);
        if (prevChg == null)
        {
            node.putNull("prev_change_pct");
        }
        else
        {
            node.put("prev_change_pct", prevChg.doubleValue());
        }
        node.put("delta_pct", Num.round(delta, 2));
        return node;
    }

    private static ArrayNode gate(ArrayNode triggers, boolean extreme, ObjectNode optionsSignal)
    {
        boolean optsStrong = optionsSignal != null
                && optionsSignal.path("signal_strength").asText().equals("strong");
        boolean fires = triggers.size() > 0
                && (extreme || triggers.size() >= 2 || (triggers.size() == 1 && optsStrong));
        return fires ? triggers : null;
    }

    private static String direction(String regime, ArrayNode triggers)
    {
        String direction;
        if (regime.equals("risk_off"))
        {
            direction = "bearish";
        }
        else if (regime.equals("risk_on"))
        {
            direction = "bullish";
        }
        else if (regime.equals("mixed"))
        {
            direction = mixedDirection(triggers);
        }
        else
        {
            direction = "neutral";
        }
        return direction;
    }

    private static String mixedDirection(ArrayNode triggers)
    {
        int riskOff = 0;
        int riskOn = 0;
        for (JsonNode trigger : triggers)
        {
            String riskOffDir = RISK_OFF_DIRECTION.getOrDefault(trigger.path("name").asText(), "up");
            double delta = trigger.path("delta_pct").asDouble();
            boolean isRiskOff = (riskOffDir.equals("up") && delta > 0) || (riskOffDir.equals("down") && delta < 0);
            if (isRiskOff)
            {
                riskOff++;
            }
            else
            {
                riskOn++;
            }
        }
        String direction = "neutral";
        if (riskOff > riskOn)
        {
            direction = "bearish";
        }
        else if (riskOn > riskOff)
        {
            direction = "bullish";
        }
        return direction;
    }

    private static String impactTier(MacroThresholds thresholds, ArrayNode triggers,
                                     ObjectNode optionsSignal, ObjectNode macroTrend, String direction)
    {
        Map<String, Double> byName = thresholdsByName(thresholds);
        boolean extreme = false;
        for (JsonNode trigger : triggers)
        {
            double threshold = byName.getOrDefault(trigger.path("name").asText(), MISSING_THRESHOLD);
            extreme = extreme || Math.abs(trigger.path("delta_pct").asDouble()) >= threshold * EXTREME_MULTIPLE;
        }
        boolean optsStrong = optionsSignal != null
                && optionsSignal.path("signal_strength").asText().equals("strong");
        boolean trendAligned = trendAligned(macroTrend, direction);

        String tier = "low";
        if (extreme || (triggers.size() >= 2 && optsStrong) || trendAligned)
        {
            tier = "high";
        }
        return tier;
    }

    private static boolean trendAligned(ObjectNode macroTrend, String direction)
    {
        boolean aligned = false;
        if (macroTrend != null && macroTrend.path("sustained").asBoolean()
                && !macroTrend.path("net_trend").asText("flat").equals("flat"))
        {
            boolean triggerRiskOff = direction.equals("bearish");
            boolean trendRiskOff = macroTrend.path("net_trend").asText().equals("risk_off");
            aligned = triggerRiskOff == trendRiskOff;
        }
        return aligned;
    }

    private static String reasoning(ArrayNode triggers, String regime, ObjectNode optionsSignal, ObjectNode macroTrend)
    {
        StringBuilder reasoning = new StringBuilder("Macro: ").append(triggerParts(triggers))
                .append(" (").append(regime).append(")");
        appendOptionsReasoning(reasoning, optionsSignal);
        appendTrendReasoning(reasoning, macroTrend);
        return reasoning.toString();
    }

    private static void appendOptionsReasoning(StringBuilder reasoning, ObjectNode optionsSignal)
    {
        boolean present = optionsSignal != null
                && !optionsSignal.path("signal_strength").asText("none").equals("none");
        if (present)
        {
            List<String> parts = new ArrayList<>();
            parts.add(optionsSignal.path("positioning").asText());
            JsonNode pc = optionsSignal.path("near_pc_ratio");
            if (pc.isNumber())
            {
                parts.add(String.format(Locale.ROOT, "P/C=%.2f", pc.asDouble()));
            }
            JsonNode dvol = optionsSignal.path("dvol_trend");
            if (dvol.isTextual() && !dvol.asText().equals("flat"))
            {
                parts.add("DVOL " + dvol.asText());
            }
            reasoning.append("; options: ").append(String.join(", ", parts));
        }
    }

    private static void appendTrendReasoning(StringBuilder reasoning, ObjectNode macroTrend)
    {
        if (macroTrend != null && macroTrend.path("sustained").asBoolean())
        {
            List<String> trending = new ArrayList<>();
            JsonNode indicators = macroTrend.path("indicators");
            Iterator<Map.Entry<String, JsonNode>> fields = indicators.fields();
            while (fields.hasNext())
            {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode info = entry.getValue();
                if (info.path("streak").asInt() >= TREND_STREAK_THRESHOLD)
                {
                    trending.add(entry.getKey() + " " + info.path("direction").asText() + " "
                            + info.path("streak").asInt() + "w "
                            + String.format(Locale.ROOT, "%+.1f%%", info.path("cumulative_delta").asDouble()));
                }
            }
            if (!trending.isEmpty())
            {
                reasoning.append("; trend: ").append(String.join(", ", trending));
            }
        }
    }

    private static String triggerParts(ArrayNode triggers)
    {
        List<String> parts = new ArrayList<>();
        for (JsonNode trigger : triggers)
        {
            parts.add(trigger.path("name").asText() + " "
                    + String.format(Locale.ROOT, "%+.1f%%", trigger.path("delta_pct").asDouble()));
        }
        return String.join(", ", parts);
    }

    private static List<Gauge> gauges(MacroThresholds thresholds)
    {
        return List.of(
                new Gauge("VIX", thresholds.vixInPct()),
                new Gauge("DXY", thresholds.dxyInPct()),
                new Gauge("SP500", thresholds.sp500InPct()),
                new Gauge("US10Y", thresholds.us10yInPct()),
                new Gauge("Gold", thresholds.goldInPct()));
    }

    private static Map<String, Double> thresholdsByName(MacroThresholds thresholds)
    {
        Map<String, Double> byName = new LinkedHashMap<>();
        for (Gauge gauge : gauges(thresholds))
        {
            byName.put(gauge.name(), gauge.threshold());
        }
        return byName;
    }
}
