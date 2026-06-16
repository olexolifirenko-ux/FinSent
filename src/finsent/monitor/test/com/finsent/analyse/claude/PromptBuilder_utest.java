package com.finsent.analyse.claude;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.analyse.signal.EconEventSignals;
import com.finsent.analyse.signal.FundingSignals;
import com.finsent.analyse.signal.MacroSignals;
import com.finsent.analyse.signal.MacroTrend;
import com.finsent.core.Json;

/**
 * Byte-match of {@link PromptBuilder}'s blocks against Python {@code analyse.build_screener_prompt}
 * / {@code build_claude_prompt}: the article-line layout (source | pub | title, indented
 * description, per-article pre_trend, blank-line separators), the 1-based / offset id maps, and the
 * {@code market_signals} formatting (regime detail, options parts, macro-trend line).
 */
public class PromptBuilder_utest
{
    @Test
    public void screenerArticleLayoutAndIdMap()
    {
        List<ObjectNode> articles = List.of(
                article(101, "Reuters", "2026-06-04T07:13:00Z", "BTC ETF approved", "Huge inflows expected"),
                article(102, "WSJ", "2026-06-04T07:20:00Z", "Fed holds rates", ""));
        Map<Integer, Integer> idMap = new HashMap<>();

        String block = PromptBuilder.screenerArticles(articles, idMap, 0);

        assertEquals(""
                + "[1] Reuters | 2026-06-04 07:13 | BTC ETF approved\n"
                + "     Huge inflows expected\n"
                + "[2] WSJ | 2026-06-04 07:20 | Fed holds rates", block);
        assertEquals(Integer.valueOf(101), idMap.get(1));
        assertEquals(Integer.valueOf(102), idMap.get(2));
    }

    @Test
    public void screenerIndexOffsetShiftsNumberingAndIdMap()
    {
        List<ObjectNode> articles = List.of(
                article(55, "AP", "2026-06-04T08:00:00Z", "Headline", "Body"));
        Map<Integer, Integer> idMap = new HashMap<>();

        String block = PromptBuilder.screenerArticles(articles, idMap, 100);

        assertEquals("[101] AP | 2026-06-04 08:00 | Headline\n     Body", block);
        assertEquals(Integer.valueOf(55), idMap.get(101));
    }

    @Test
    public void coveredBlockListsRecentResonantOrIsEmpty()
    {
        assertEquals("", PromptBuilder.coveredBlock(List.of()));
        List<ObjectNode> covered = List.of(
                article(1, "AlJazeera", "2026-06-09T21:17:00Z", "Trump accuses Iran of downing US Apache", ""),
                article(2, "Bloomberg", "2026-06-09T22:03:00Z", "Oil rebounded after US strikes", ""));
        assertEquals(""
                + "-- ALREADY COVERED (recent; reference only, do NOT score) --\n"
                + "- 2026-06-09 21:17 | Trump accuses Iran of downing US Apache\n"
                + "- 2026-06-09 22:03 | Oil rebounded after US strikes",
                PromptBuilder.coveredBlock(covered));
    }

    @Test
    public void econEventRendersLabelAndMechanicalPrior()
    {
        ObjectNode signal = EconEventSignals.signal("CPI MoM", "%", 0.3, 0.6, "bearish", 0.1, 0.2);
        assertEquals(""
                + "CPI MoM 0.6% vs 0.3% est (+0.3%, high -> bearish)\n"
                + "mechanical_prior: bearish / high", PromptBuilder.econEvent(signal));
    }

    @Test
    public void macroAlertRendersTriggersAndMechanicalPrior()
    {
        ObjectNode trigger = Json.newObject();
        trigger.put("name", "VIX");
        trigger.put("delta_pct", 12.0);
        ArrayNode triggers = Json.newArray();
        triggers.add(trigger);
        ObjectNode mechanical = Json.newObject();
        mechanical.set("triggers", triggers);
        mechanical.put("direction", "bearish");
        mechanical.put("impact_tier", "high");

        assertEquals(""
                + "macro tape breach: VIX +12.0%\n"
                + "mechanical_prior: bearish / high", PromptBuilder.macroAlert(mechanical));
    }

    @Test
    public void deepArticleLayoutWithPreTrendAndBlankSeparators()
    {
        List<ObjectNode> articles = List.of(
                article(101, "Reuters", "2026-06-04T07:13:00Z", "Title A", "Desc A"),
                article(102, "WSJ", "2026-06-04T07:20:00Z", "Title B", ""));
        Map<Integer, ArrayNode> ohlc = new HashMap<>();
        ohlc.put(101, bars(100, 101, 102, 103, 104)); // monotone up -> rising
        Map<Integer, Integer> idMap = new HashMap<>();

        String block = PromptBuilder.deepArticles(articles, ohlc, idMap);

        // Articles are listed most-recent-first: WSJ (07:20) precedes Reuters (07:13).
        assertEquals(""
                + "[1] WSJ | 2026-06-04 07:20\n"
                + "     Title B\n"
                + "\n"
                + "[2] Reuters | 2026-06-04 07:13\n"
                + "     Title A\n"
                + "     Desc A\n"
                + "     pre_trend: rising +4.00% (r2=1.00)\n", block);
        assertEquals(Integer.valueOf(102), idMap.get(1));
        assertEquals(Integer.valueOf(101), idMap.get(2));
    }

    @Test
    public void deepArticlesOrderedMostRecentFirst()
    {
        List<ObjectNode> articles = List.of(
                article(201, "Reuters", "2026-06-04T07:05:00Z", "Older", ""),
                article(202, "WSJ", "2026-06-04T07:40:00Z", "Newest", ""),
                article(203, "CoinDesk", "2026-06-04T07:20:00Z", "Middle", ""));
        Map<Integer, Integer> idMap = new HashMap<>();

        PromptBuilder.deepArticles(articles, null, idMap);

        assertEquals(Integer.valueOf(202), idMap.get(1));
        assertEquals(Integer.valueOf(203), idMap.get(2));
        assertEquals(Integer.valueOf(201), idMap.get(3));
    }

    @Test
    public void marketSignalsNeutralIsRegimeOnly()
    {
        ObjectNode regime = MacroSignals.regime(macroSnap(0, 0, 0));
        assertEquals("macro_regime: neutral", PromptBuilder.marketSignals(regime, null, null, null, null));
    }

    @Test
    public void marketSignalsOmitsRegimeWhenMacroAbsent()
    {
        // No macro snapshot (e.g. macro collection disabled) must NOT fabricate a "neutral" regime line.
        ObjectNode regime = MacroSignals.regime(Json.newObject());
        assertEquals("", PromptBuilder.marketSignals(regime, null, null, null, null));
    }

    @Test
    public void marketSignalsLeadsWithPriceContext()
    {
        ObjectNode regime = MacroSignals.regime(macroSnap(0, 0, 0));

        String block = PromptBuilder.marketSignals(regime, null, null, null, priceCtx(67432.10, 0.8, -3.4, 0.12));

        assertEquals(""
                + "btc_price: $67432.10 | 1h +0.80% | 24h -3.40% (near 24h low)\n"
                + "macro_regime: neutral", block);
    }

    @Test
    public void marketSignalsFoldsRegimeDetailOptionsAndTrend()
    {
        ObjectNode regime = MacroSignals.regime(macroSnap(6.0, 0.4, -0.6)); // VIX/DXY/SP500 breach -> risk_off
        ObjectNode options = optionsSignal();
        ObjectNode macroTrend = MacroTrend.of(List.of(vix(1), vix(2), vix(3), vix(4), vix(5)));

        String block = PromptBuilder.marketSignals(regime, options, null, macroTrend, null);

        assertEquals(""
                + "macro_regime: risk_off\n"
                + "macro_detail: 3/5 indicators (VIX↑, DXY↑, SP500↓)\n"
                + "options_signal: strong (bearish, P/C=1.25, IV elevated, DVOL rising)\n"
                + "macro_trend: risk_off sustained (VIX rising 4w +4.0%)", block);
    }

    @Test
    public void marketSignalsIncludesFundingLine()
    {
        ObjectNode regime = MacroSignals.regime(macroSnap(0, 0, 0));
        ObjectNode funding = FundingSignals.signal(funding(0.00038));

        String block = PromptBuilder.marketSignals(regime, null, funding, null, null);

        assertEquals(""
                + "macro_regime: neutral\n"
                + "funding: +0.038% crowded_long", block);
    }

    private static ObjectNode funding(double rate)
    {
        ObjectNode snapshot = Json.newObject();
        snapshot.put("funding_rate", rate);
        return snapshot;
    }

    private static ObjectNode optionsSignal()
    {
        ObjectNode options = Json.newObject();
        options.put("signal_strength", "strong");
        options.put("positioning", "bearish");
        options.put("near_pc_ratio", 1.25);
        options.put("iv_elevated", true);
        options.put("oi_surge", false);
        options.put("dvol_trend", "rising");
        return options;
    }

    private static ObjectNode macroSnap(double vix, double dxy, double sp500)
    {
        ObjectNode yahoo = Json.newObject();
        yahoo.set("VIX", changePct(vix));
        yahoo.set("DXY", changePct(dxy));
        yahoo.set("SP500", changePct(sp500));
        ObjectNode snap = Json.newObject();
        snap.set("yahoo", yahoo);
        return snap;
    }

    private static ObjectNode vix(double changePct)
    {
        ObjectNode yahoo = Json.newObject();
        yahoo.set("VIX", changePct(changePct));
        ObjectNode snap = Json.newObject();
        snap.set("yahoo", yahoo);
        return snap;
    }

    private static ObjectNode changePct(double value)
    {
        ObjectNode indicator = Json.newObject();
        indicator.put("change_pct", value);
        return indicator;
    }

    private static ObjectNode article(int id, String src, String pub, String title, String desc)
    {
        ObjectNode source = Json.newObject();
        source.put("name", src);
        ObjectNode article = Json.newObject();
        article.put("id", id);
        article.set("source", source);
        article.put("publishedAt", pub);
        article.put("title", title);
        article.put("description", desc);
        return article;
    }

    private static ArrayNode bars(double... closes)
    {
        ArrayNode array = Json.newArray();
        for (double c : closes)
        {
            ObjectNode bar = Json.newObject();
            bar.put("c", c);
            array.add(bar);
        }
        return array;
    }

    private static ObjectNode priceCtx(double price, double change1h, double change24h, double rangePos24h)
    {
        ObjectNode node = Json.newObject();
        node.put("btc_price", price);
        node.put("change_1h_pct", change1h);
        node.put("change_24h_pct", change24h);
        node.put("range_pos_24h", rangePos24h);
        return node;
    }
}
