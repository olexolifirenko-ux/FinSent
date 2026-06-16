package com.finsent.analyse.signal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;

/**
 * Mechanical macro-regime classification over a macro snapshot (ports Python
 * {@code analyse._compute_macro_regime}). The analyser computes this in Java and passes only the
 * one-line {@code regime} label into Claude's prompt &mdash; Claude never sees the raw indicator
 * numbers. An indicator counts as risk-off / risk-on when its daily change breaches the
 * per-indicator threshold in the risk-off / opposite direction.
 */
public final class MacroSignals
{
    /** Per-indicator thresholds: change &gt; offThreshold (offDirUp) or &lt; offThreshold (else) is risk-off. */
    private record Indicator(String name, double offThreshold, double onThreshold, boolean offDirUp)
    {
    }

    private static final Indicator[] INDICATORS = {
            new Indicator("VIX", 5.0, -5.0, true),
            new Indicator("DXY", 0.3, -0.3, true),
            new Indicator("SP500", -0.5, 0.5, false),
            new Indicator("US10Y", 2.0, -2.0, true),
            new Indicator("Gold", 0.5, -0.5, true),
    };

    private MacroSignals()
    {
    }

    /**
     * Classify the macro regime. Returns {@code {regime, risk_off_count, risk_on_count, total,
     * triggered}} where {@code regime} is one of {@code risk_off|risk_on|mixed|neutral} and
     * {@code triggered} lists the breaching indicators (e.g. {@code ["VIX↑","SP500↓"]}).
     */
    public static ObjectNode regime(ObjectNode macroSnap)
    {
        JsonNode yahoo = macroSnap == null ? null : macroSnap.get("yahoo");
        int riskOff = 0;
        int riskOn = 0;
        int total = 0;
        ArrayNode triggered = Json.newArray();

        if (yahoo != null && yahoo.size() > 0)
        {
            for (Indicator indicator : INDICATORS)
            {
                JsonNode data = yahoo.get(indicator.name());
                double change = data == null ? 0.0 : data.path("change_pct").asDouble(0.0);
                boolean breachOff = indicator.offDirUp() ? change > indicator.offThreshold()
                        : change < indicator.offThreshold();
                boolean breachOn = indicator.offDirUp() ? change < indicator.onThreshold()
                        : change > indicator.onThreshold();
                if (breachOff)
                {
                    riskOff++;
                    total++;
                    triggered.add(indicator.name() + (indicator.offDirUp() ? "↑" : "↓"));
                }
                else if (breachOn)
                {
                    riskOn++;
                    total++;
                    triggered.add(indicator.name() + (indicator.offDirUp() ? "↓" : "↑"));
                }
            }
        }

        ObjectNode result = Json.newObject();
        // Whether a macro snapshot was actually present: distinguishes a real "neutral" reading from
        // the degenerate "no macro data" case (e.g. macro collection disabled), so a fabricated regime
        // is not fed to the prompt or stored as if it were a reading.
        result.put("has_data", yahoo != null && yahoo.size() > 0);
        result.put("regime", classify(riskOff, riskOn));
        result.put("risk_off_count", riskOff);
        result.put("risk_on_count", riskOn);
        result.put("total", total);
        result.set("triggered", triggered);
        return result;
    }

    private static String classify(int riskOff, int riskOn)
    {
        String regime;
        if (riskOff >= 3)
        {
            regime = "risk_off";
        }
        else if (riskOn >= 3)
        {
            regime = "risk_on";
        }
        else if (riskOff > 0 || riskOn > 0)
        {
            regime = "mixed";
        }
        else
        {
            regime = "neutral";
        }
        return regime;
    }
}
