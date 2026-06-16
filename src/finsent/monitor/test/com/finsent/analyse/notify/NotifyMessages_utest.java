package com.finsent.analyse.notify;

import static org.junit.Assert.assertEquals;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Byte-match of {@link NotifyMessages} against Python {@code _format_telegram_message} /
 * {@code _format_email_body} and the macro-alert message bodies: the header/price/event layout, the
 * {@code .strip()} trim, the per-article breakdown, and the macro-only subject/body.
 */
public class NotifyMessages_utest
{
    @Test
    public void telegramLeadsWithEventThenMaterialityAndLean()
    {
        ObjectNode pred = pred("bullish", "high", "risk_on", "ETF inflows accelerating.");
        pred.put("btc_at_prediction", 79000);
        pred.set("key_events", events("ETF approval", "Whale accumulation"));

        assertEquals(""
                + "CRYPTO EVENT (2 articles)\n"
                + "• ETF approval\n"
                + "• Whale accumulation\n"
                + "ETF inflows accelerating.\n"
                + "Materiality HIGH | Lean BULLISH | BTC: $79,000", NotifyMessages.telegram(pred, 2, null));
    }

    @Test
    public void telegramShowsRealtimePriceMove()
    {
        ObjectNode pred = pred("bearish", "high", "risk_off", "Geopolitical shock.");
        pred.put("btc_at_prediction", 61362);
        pred.set("key_events", events("Trump vows response"));
        ObjectNode article = Json.newObject();
        article.put("published_at", "2026-06-09T16:38:07Z");
        ArrayNode articles = Json.newArray();
        articles.add(article);
        pred.set("articles", articles);

        assertEquals(""
                + "CRYPTO EVENT (1 article)\n"
                + "• Trump vows response\n"
                + "Geopolitical shock.\n"
                + "Materiality HIGH | Lean BEARISH | BTC: $60,900 now | $61,362 at news (-0.8% since, 16:38)",
                NotifyMessages.telegram(pred, 1, 60900.0));
    }

    @Test
    public void telegramSingularNoPriceNoEventsStripsTrailing()
    {
        ObjectNode pred = pred("bearish", "low", "neutral", "Minor.");
        assertEquals(""
                + "CRYPTO EVENT (1 article)\n"
                + "Minor.\n"
                + "Materiality LOW | Lean BEARISH", NotifyMessages.telegram(pred, 1, null));
    }

    @Test
    public void emailSubjectShowsMaterialityAndLean()
    {
        assertEquals("Crypto Event -- high materiality, BULLISH lean",
                NotifyMessages.emailSubject(pred("bullish", "high", "risk_on", "x")));
        assertEquals("Crypto Event -- ? materiality, unclear lean", NotifyMessages.emailSubject(Json.newObject()));
    }

    @Test
    public void emailBodyWithPerItemBreakdown()
    {
        ObjectNode pred = pred("bullish", "high", "risk_on", "ETF inflows accelerating.");
        pred.put("btc_at_prediction", 79000);
        pred.set("key_events", events("ETF approval", "Whale accumulation"));
        ObjectNode etf = articlePred("Spot ETF approved by SEC", "bullish", "fresh_bullish", "Institutional inflows");
        etf.put("published_at", "2026-06-09T14:05:00Z");
        etf.put("url", "https://x/1");
        List<ObjectNode> articles = List.of(etf,
                articlePred("Minor wallet update", "neutral", "noise", ""));

        assertEquals(""
                + "CRYPTO EVENT (2 articles)\n"
                + "Materiality: high\n"
                + "Lean: BULLISH\n"
                + "BTC: $79,500 now | $79,000 at news (+0.6% since)\n"
                + "Reasoning: ETF inflows accelerating.\n"
                + "Key events:\n"
                + "  - ETF approval\n"
                + "  - Whale accumulation\n"
                + "\n"
                + "--- ITEMS ---\n"
                + "\n"
                + "[BULLISH] 14:05  Spot ETF approved by SEC\n"
                + "  Scenario: fresh_bullish\n"
                + "  https://x/1\n"
                + "  Institutional inflows\n"
                + "\n"
                + "[NEUTRAL] Minor wallet update\n"
                + "  Scenario: noise\n"
                + "", NotifyMessages.emailBody(pred, articles, 2, 79500.0));
    }

    @Test
    public void emailBodyWithoutPerItemAnalysis()
    {
        ObjectNode pred = pred("neutral", "noise", "neutral", "No catalysts.");
        assertEquals(""
                + "CRYPTO EVENT (1 article)\n"
                + "Materiality: noise\n"
                + "Lean: unclear\n"
                + "Reasoning: No catalysts.\n"
                + "\n"
                + "--- ITEMS ---\n"
                + "\n"
                + "(no per-article analysis available)", NotifyMessages.emailBody(pred, List.of(), 1, null));
    }

    @Test
    public void macroTelegramSubjectAndBody()
    {
        ObjectNode alert = macroAlert("bearish", "high", "risk_off", "Macro: VIX +12.0% (risk_off)");

        assertEquals(""
                + "MACRO ALERT (no resonant news)\n"
                + "BEARISH | high impact | macro: risk_off\n"
                + "Triggers: VIX +12.0%\n"
                + "Macro: VIX +12.0% (risk_off)", NotifyMessages.macroTelegram(alert));
        assertEquals("BTC Macro Alert: BEARISH", NotifyMessages.macroEmailSubject(alert));
        assertEquals(""
                + "MACRO-ONLY BTC ALERT\n"
                + "Direction: BEARISH\n"
                + "Impact: high\n"
                + "Macro regime: risk_off\n"
                + "Reasoning: Macro: VIX +12.0% (risk_off)\n"
                + "\n"
                + "Triggered indicators: VIX +12.0%\n"
                + "No resonant news articles in this monitoring window.\n", NotifyMessages.macroEmailBody(alert));
    }

    @Test
    public void econTelegramSubjectAndBody()
    {
        ObjectNode alert = econAlert("bearish", "high", "risk_off",
                "CPI MoM 0.6% vs 0.3% est (+0.3%, high -> bearish)", "Hot inflation print, risk-off for BTC.");

        assertEquals(""
                + "DATA ALERT (CPI MoM)\n"
                + "BEARISH | high impact | macro: risk_off\n"
                + "CPI MoM 0.6% vs 0.3% est (+0.3%, high -> bearish)\n"
                + "Hot inflation print, risk-off for BTC.", NotifyMessages.econTelegram(alert));
        assertEquals("BTC Data Alert: BEARISH", NotifyMessages.econEmailSubject(alert));
        assertEquals(""
                + "SCHEDULED DATA RELEASE BTC ALERT\n"
                + "Event: CPI MoM 0.6% vs 0.3% est (+0.3%, high -> bearish)\n"
                + "Direction: BEARISH\n"
                + "Impact: high\n"
                + "Macro regime: risk_off\n"
                + "Mechanical prior: bearish / high\n"
                + "Reasoning: Hot inflation print, risk-off for BTC.\n"
                + "\n"
                + "No resonant news -- this is a scheduled data-release surprise.\n", NotifyMessages.econEmailBody(alert));
    }

    private static ObjectNode econAlert(String direction, String tier, String regime, String label, String reasoning)
    {
        ObjectNode alert = Json.newObject();
        alert.put("event", "CPI MoM");
        alert.put("label", label);
        alert.put("direction", direction);
        alert.put("impact_tier", tier);
        alert.put("macro_regime", regime);
        alert.put("mechanical_direction", "bearish");
        alert.put("mechanical_tier", "high");
        alert.put("reasoning", reasoning);
        return alert;
    }

    private static ObjectNode pred(String direction, String tier, String regime, String reasoning)
    {
        ObjectNode pred = Json.newObject();
        pred.put("direction", direction);
        pred.put("impact_tier", tier);
        pred.put("confidence", "high"); // set but intentionally not rendered in the alert body
        pred.put("macro_regime", regime);
        pred.put("reasoning", reasoning);
        return pred;
    }

    private static ObjectNode articlePred(String title, String direction, String scenario, String reasoning)
    {
        ObjectNode article = Json.newObject();
        article.put("title", title);
        article.put("direction", direction);
        article.put("scenario", scenario);
        article.put("reasoning", reasoning);
        return article;
    }

    private static ObjectNode macroAlert(String direction, String tier, String regime, String reasoning)
    {
        ObjectNode trigger = Json.newObject();
        trigger.put("name", "VIX");
        trigger.put("delta_pct", 12.0);
        ArrayNode triggers = Json.newArray();
        triggers.add(trigger);
        ObjectNode alert = Json.newObject();
        alert.put("direction", direction);
        alert.put("impact_tier", tier);
        alert.put("macro_regime", regime);
        alert.put("reasoning", reasoning);
        alert.set("triggers", triggers);
        return alert;
    }

    private static ArrayNode events(String... values)
    {
        ArrayNode array = Json.newArray();
        for (String value : values)
        {
            array.add(value);
        }
        return array;
    }
}
