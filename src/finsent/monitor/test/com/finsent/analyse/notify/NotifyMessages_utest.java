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
    public void telegramWithPriceAndEvents()
    {
        ObjectNode pred = pred("bullish", "high", "risk_on", "ETF inflows accelerating.");
        pred.put("btc_at_prediction", 79000);
        pred.set("key_events", events("ETF approval", "Whale accumulation"));

        assertEquals(""
                + "BTC ALERT (2 articles)\n"
                + "BTC: $79,000\n"
                + "BULLISH | high impact | macro: risk_on\n"
                + "ETF inflows accelerating.\n"
                + "• ETF approval\n"
                + "• Whale accumulation", NotifyMessages.telegram(pred, 2, null));
    }

    @Test
    public void telegramShowsRealtimePriceMoveCatalystTimeAndContext()
    {
        ObjectNode pred = pred("bearish", "high", "risk_off", "Geopolitical shock.");
        pred.put("btc_at_prediction", 61362);
        pred.set("key_events", events("Trump vows response"));
        ObjectNode article = Json.newObject();
        article.put("published_at", "2026-06-09T16:38:07Z");
        ArrayNode articles = Json.newArray();
        articles.add(article);
        pred.set("articles", articles);
        ObjectNode funding = Json.newObject();
        funding.put("positioning", "crowded_long");
        pred.set("funding_signal", funding);
        ObjectNode priceCtx = Json.newObject();
        priceCtx.put("range_pos_24h", 0.10);
        pred.set("price_context", priceCtx);

        assertEquals(""
                + "BTC ALERT (1 article)\n"
                + "BTC: $60,900 now | $61,362 at news (-0.8% since, 16:38)\n"
                + "BEARISH | high impact | macro: risk_off\n"
                + "crowded_long (cascade risk) · near 24h low\n"
                + "Geopolitical shock.\n"
                + "• Trump vows response", NotifyMessages.telegram(pred, 1, 60900.0));
    }

    @Test
    public void telegramSingularNoPriceNoEventsStripsTrailing()
    {
        ObjectNode pred = pred("bearish", "low", "neutral", "Minor.");
        assertEquals(""
                + "BTC ALERT (1 article)\n"
                + "BEARISH | low impact | macro: neutral\n"
                + "Minor.", NotifyMessages.telegram(pred, 1, null));
    }

    @Test
    public void emailSubjectUsesDirectionOrNeutral()
    {
        assertEquals("BTC Alert: BULLISH",
                NotifyMessages.emailSubject(pred("bullish", "high", "risk_on", "x")));
        assertEquals("BTC Alert: NEUTRAL", NotifyMessages.emailSubject(Json.newObject()));
    }

    @Test
    public void emailBodyWithPerArticleBreakdown()
    {
        ObjectNode pred = pred("bullish", "high", "risk_on", "ETF inflows accelerating.");
        pred.put("btc_at_prediction", 79000);
        pred.set("key_events", events("ETF approval", "Whale accumulation"));
        ObjectNode funding = Json.newObject();
        funding.put("positioning", "crowded_short");
        pred.set("funding_signal", funding);
        ObjectNode priceCtx = Json.newObject();
        priceCtx.put("range_pos_24h", 0.85);
        pred.set("price_context", priceCtx);
        ObjectNode etf = articlePred("Spot ETF approved by SEC", "bullish", "fresh_bullish", "Institutional inflows");
        etf.put("published_at", "2026-06-09T14:05:00Z");
        etf.put("url", "https://x/1");
        List<ObjectNode> articles = List.of(etf,
                articlePred("Minor wallet update", "neutral", "noise", ""));

        assertEquals(""
                + "BTC ANALYSIS (2 articles)\n"
                + "Direction: BULLISH\n"
                + "Impact: high\n"
                + "Macro regime: risk_on\n"
                + "BTC: $79,500 now | $79,000 at news (+0.6% since)\n"
                + "Positioning: crowded_short (squeeze risk)\n"
                + "24h range: near 24h high\n"
                + "Reasoning: ETF inflows accelerating.\n"
                + "Key events:\n"
                + "  - ETF approval\n"
                + "  - Whale accumulation\n"
                + "\n"
                + "--- PER-ARTICLE ANALYSIS ---\n"
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
    public void emailBodyWithoutPerArticleAnalysis()
    {
        ObjectNode pred = pred("neutral", "noise", "neutral", "No catalysts.");
        assertEquals(""
                + "BTC ANALYSIS (1 article)\n"
                + "Direction: NEUTRAL\n"
                + "Impact: noise\n"
                + "Macro regime: neutral\n"
                + "Reasoning: No catalysts.\n"
                + "\n"
                + "--- PER-ARTICLE ANALYSIS ---\n"
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
