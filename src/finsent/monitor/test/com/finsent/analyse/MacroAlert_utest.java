package com.finsent.analyse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.analyse.signal.MacroTrend;
import com.finsent.core.Config.MacroThresholds;
import com.finsent.core.Json;

/**
 * Verifies {@link MacroAlert} against Python {@code _detect_macro_alert} / {@code macro_only_assessment}:
 * the weighted detection gate (extreme / 2+ / options-confirmed), the regime-driven direction and
 * trend-aligned impact upgrade, and the templated reasoning string.
 */
public class MacroAlert_utest
{
    private static final MacroThresholds THRESHOLDS = new MacroThresholds(10.0, 0.5, 1.0, 3.0, 1.0);
    private static final String AT = "2026-06-04T08:00:00Z";

    @Test
    public void detectsSingleExtremeMove()
    {
        ArrayNode triggers = MacroAlert.detect(THRESHOLDS, snap(yahoo("VIX", 25.0)), snap(yahoo("VIX", 5.0)), null);
        assertEquals(1, triggers.size());
        assertEquals("VIX", triggers.get(0).path("name").asText());
        assertEquals(20.0, triggers.get(0).path("delta_pct").asDouble(), 1e-9); // 25 - 5
        assertEquals(25.0, triggers.get(0).path("change_pct").asDouble(), 1e-9);
        assertEquals(5.0, triggers.get(0).path("prev_change_pct").asDouble(), 1e-9);
    }

    @Test
    public void singleSubExtremeBreachWithoutOptionsDoesNotFire()
    {
        // VIX delta 10 == threshold (breach) but < 2x (not extreme); one trigger, no options -> gate fails.
        assertNull(MacroAlert.detect(THRESHOLDS, snap(yahoo("VIX", 15.0)), snap(yahoo("VIX", 5.0)), null));
    }

    @Test
    public void singleBreachFiresWhenOptionsStrong()
    {
        ObjectNode options = Json.newObject();
        options.put("signal_strength", "strong");
        ArrayNode triggers = MacroAlert.detect(THRESHOLDS, snap(yahoo("VIX", 15.0)), snap(yahoo("VIX", 5.0)), options);
        assertEquals(1, triggers.size());
    }

    @Test
    public void twoSubExtremeBreachesFire()
    {
        ObjectNode cur = snap(yahoo("VIX", 15.0));
        ((ObjectNode) cur.path("yahoo")).set("DXY", changePct(0.6));
        ObjectNode prev = snap(yahoo("VIX", 5.0));
        ((ObjectNode) prev.path("yahoo")).set("DXY", changePct(0.0));
        assertEquals(2, MacroAlert.detect(THRESHOLDS, cur, prev, null).size());
    }

    @Test
    public void noYahooSectionDoesNotDetect()
    {
        assertNull(MacroAlert.detect(THRESHOLDS, Json.newObject(), null, null));
    }

    @Test
    public void missingPreviousLeavesPrevChangeNull()
    {
        ArrayNode triggers = MacroAlert.detect(THRESHOLDS, snap(yahoo("VIX", 25.0)), null, null);
        assertEquals(1, triggers.size());
        assertTrue(triggers.get(0).path("prev_change_pct").isNull());
        assertEquals(25.0, triggers.get(0).path("delta_pct").asDouble(), 1e-9); // no prev -> delta == change
    }

    @Test
    public void assessRiskOffWithExtremeIsBearishHigh()
    {
        // VIX/DXY/SP500 all breach the regime thresholds -> risk_off; SP500 -2.0 is extreme (>= 2x 1.0).
        ObjectNode macroSnap = snap(yahoo("VIX", 12.0));
        ((ObjectNode) macroSnap.path("yahoo")).set("DXY", changePct(0.4));
        ((ObjectNode) macroSnap.path("yahoo")).set("SP500", changePct(-0.6));
        ArrayNode triggers = triggers(trigger("VIX", 12.0), trigger("SP500", -2.0));

        ObjectNode alert = MacroAlert.assess(THRESHOLDS, macroSnap, triggers, null, null, AT);

        assertEquals("macro_mechanical", alert.path("source").asText());
        assertEquals("bearish", alert.path("direction").asText());
        assertEquals("high", alert.path("impact_tier").asText());
        assertEquals("risk_off", alert.path("macro_regime").asText());
        assertEquals("Macro: VIX +12.0%, SP500 -2.0% (risk_off)", alert.path("reasoning").asText());
        assertEquals(AT, alert.path("analyzed_at").asText());
    }

    @Test
    public void assessMixedRegimeCountsTriggerDirections()
    {
        // VIX +6 and DXY +0.4 each breach risk-off (2 < 3) -> regime is mixed, not full risk_off.
        ObjectNode macroSnap = snap(yahoo("VIX", 6.0));
        ((ObjectNode) macroSnap.path("yahoo")).set("DXY", changePct(0.4));
        ArrayNode triggers = triggers(trigger("VIX", 12.0), trigger("DXY", 0.6)); // both risk-off (up, +)

        ObjectNode alert = MacroAlert.assess(THRESHOLDS, macroSnap, triggers, null, null, AT);

        assertEquals("mixed", alert.path("macro_regime").asText());
        assertEquals("bearish", alert.path("direction").asText());
        assertEquals("low", alert.path("impact_tier").asText());
        assertEquals("Macro: VIX +12.0%, DXY +0.6% (mixed)", alert.path("reasoning").asText());
    }

    @Test
    public void assessSustainedTrendUpgradesImpactAndAppendsReasoning()
    {
        ObjectNode macroSnap = snap(yahoo("VIX", 12.0));
        ((ObjectNode) macroSnap.path("yahoo")).set("DXY", changePct(0.4));
        ((ObjectNode) macroSnap.path("yahoo")).set("SP500", changePct(-0.6));
        ArrayNode triggers = triggers(trigger("VIX", 6.0)); // single, sub-extreme
        ObjectNode macroTrend = MacroTrend.of(List.of(vix(1), vix(2), vix(3), vix(4), vix(5)));

        ObjectNode alert = MacroAlert.assess(THRESHOLDS, macroSnap, triggers, null, macroTrend, AT);

        assertEquals("bearish", alert.path("direction").asText());
        assertEquals("high", alert.path("impact_tier").asText()); // trend-aligned upgrade
        assertEquals("Macro: VIX +6.0% (risk_off); trend: VIX rising 4w +4.0%", alert.path("reasoning").asText());
        assertEquals("risk_off", alert.path("macro_trend").path("net_trend").asText());
    }

    @Test
    public void assessFoldsOptionsIntoReasoning()
    {
        ObjectNode macroSnap = snap(yahoo("VIX", 12.0));
        ((ObjectNode) macroSnap.path("yahoo")).set("DXY", changePct(0.4));
        ((ObjectNode) macroSnap.path("yahoo")).set("SP500", changePct(-0.6));
        ArrayNode triggers = triggers(trigger("VIX", 6.0));
        ObjectNode options = Json.newObject();
        options.put("signal_strength", "strong");
        options.put("positioning", "bearish");
        options.put("near_pc_ratio", 1.25);
        options.put("dvol_trend", "rising");

        ObjectNode alert = MacroAlert.assess(THRESHOLDS, macroSnap, triggers, options, null, AT);

        assertEquals("Macro: VIX +6.0% (risk_off); options: bearish, P/C=1.25, DVOL rising",
                alert.path("reasoning").asText());
        assertTrue(alert.has("options_signal"));
    }

    private static ObjectNode trigger(String name, double deltaPct)
    {
        ObjectNode trigger = Json.newObject();
        trigger.put("name", name);
        trigger.put("delta_pct", deltaPct);
        return trigger;
    }

    private static ArrayNode triggers(ObjectNode... entries)
    {
        ArrayNode array = Json.newArray();
        for (ObjectNode entry : entries)
        {
            array.add(entry);
        }
        return array;
    }

    private static ObjectNode yahoo(String name, double changePct)
    {
        ObjectNode yahoo = Json.newObject();
        yahoo.set(name, changePct(changePct));
        return yahoo;
    }

    private static ObjectNode snap(ObjectNode yahoo)
    {
        ObjectNode snap = Json.newObject();
        snap.set("yahoo", yahoo);
        return snap;
    }

    private static ObjectNode vix(double changePct)
    {
        return snap(yahoo("VIX", changePct));
    }

    private static ObjectNode changePct(double value)
    {
        ObjectNode node = Json.newObject();
        node.put("change_pct", value);
        return node;
    }
}
