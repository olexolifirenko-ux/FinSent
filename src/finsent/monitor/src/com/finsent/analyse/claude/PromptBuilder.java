package com.finsent.analyse.claude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.signal.PreTrend;

/**
 * Assembles the article and {@code market_signals} text blocks Claude's prompts interpolate (ports
 * Python {@code analyse.build_screener_prompt} / {@code build_claude_prompt}). Pure string assembly:
 * the caller supplies the window's articles and the pre-computed mechanical signals, and obtains the
 * blocks to substitute into the templates loaded by {@link PromptTemplates}. Each builder populates
 * {@code idMap} as {@code promptIndex -> articleId} so the passes can map Claude's per-article
 * {@code "i"} back to the stored article.
 */
public final class PromptBuilder
{
    private static final int SCREENER_DESC_MAX = 300;
    // The decisive deep pass runs on only the resonant few, so it can afford the full stored
    // description (RSS caps it at 500 on collection) -- richer context for the directional call.
    private static final int DEEP_DESC_MAX = 500;
    private static final int PUB_PREFIX_LEN = 16; // "YYYY-MM-DDTHH:MM"

    private PromptBuilder()
    {
    }

    /**
     * Screener (Pass 1) article block: {@code [i] source | pub | title} with an indented
     * description line. {@code indexOffset} supports 100-article batching; {@code idMap} is
     * populated {@code promptIndex -> articleId}.
     */
    public static String screenerArticles(List<ObjectNode> articles, Map<Integer, Integer> idMap, int indexOffset)
    {
        List<String> lines = new ArrayList<>();
        for (int p = 0; p < articles.size(); p++)
        {
            ObjectNode article = articles.get(p);
            int promptIndex = indexOffset + p + 1;
            idMap.put(promptIndex, article.path("id").asInt());
            String desc = truncate(text(article, "description"), SCREENER_DESC_MAX);
            lines.add("[" + promptIndex + "] " + source(article) + " | " + pub(article) + " | " + text(article, "title"));
            if (!desc.isEmpty())
            {
                lines.add("     " + desc);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * The "ALREADY COVERED" reference block for the screener (cross-window dedup): recently-resonant
     * articles (publish time + title) the screener scores follow-ups/recaps of an already-covered story
     * against. Empty string when nothing is recent, so the section is omitted entirely.
     */
    public static String coveredBlock(List<ObjectNode> covered)
    {
        String block = "";
        if (covered != null && !covered.isEmpty())
        {
            List<String> lines = new ArrayList<>();
            lines.add("-- ALREADY COVERED (recent; reference only, do NOT score) --");
            for (ObjectNode note : covered)
            {
                lines.add("- " + pub(note) + " | " + text(note, "title"));
            }
            block = String.join("\n", lines);
        }
        return block;
    }

    /**
     * The {@code {event}} block for the article-less econ prompt (#21): the mechanical surprise label
     * plus the mechanical direction/tier as a labelled prior the deep pass weighs against the market
     * context. {@code signal} is the {@code EconEventSignals} object for a resolved scheduled release.
     */
    public static String econEvent(ObjectNode signal)
    {
        return text(signal, "label") + "\n"
                + "mechanical_prior: " + signal.path("direction").asText("neutral")
                + " / " + signal.path("impact_tier").asText("noise");
    }

    /**
     * Deep-analysis (Pass 2) article block: {@code [i] source | pub}, an indented title and
     * description, and a {@code pre_trend} label when {@code ohlcByArticleId} carries bars for the
     * article. A blank line separates entries (and trails the block), matching Python. {@code idMap}
     * is populated {@code promptIndex -> articleId} (1-based).
     */
    public static String deepArticles(List<ObjectNode> articles, Map<Integer, ArrayNode> ohlcByArticleId,
                                      Map<Integer, Integer> idMap)
    {
        // Most-recent-first: the freshest catalyst should dominate the directional call, so the deep
        // pass lists the resonant articles newest-first (by publishedAt) rather than in window order.
        List<ObjectNode> ordered = new ArrayList<>(articles);
        ordered.sort(Comparator.comparing((ObjectNode a) -> text(a, "publishedAt")).reversed());
        List<String> lines = new ArrayList<>();
        for (int p = 0; p < ordered.size(); p++)
        {
            ObjectNode article = ordered.get(p);
            int promptIndex = p + 1;
            int articleId = article.path("id").asInt();
            idMap.put(promptIndex, articleId);
            appendDeepArticle(lines, article, promptIndex, articleId, ohlcByArticleId);
        }
        return String.join("\n", lines);
    }

    private static void appendDeepArticle(List<String> lines, ObjectNode article, int promptIndex,
                                          int articleId, Map<Integer, ArrayNode> ohlcByArticleId)
    {
        String desc = truncate(text(article, "description"), DEEP_DESC_MAX);
        lines.add("[" + promptIndex + "] " + source(article) + " | " + pub(article));
        lines.add("     " + text(article, "title"));
        if (!desc.isEmpty())
        {
            lines.add("     " + desc);
        }
        ArrayNode bars = ohlcByArticleId == null ? null : ohlcByArticleId.get(articleId);
        if (bars != null && bars.size() > 0)
        {
            lines.add("     pre_trend: " + preTrendText(PreTrend.of(bars)));
        }
        lines.add("");
    }

    /**
     * Format the pre-trend for the prompt: the label, plus the pre-move size and trend cleanliness
     * for the directional labels (where the magnitude is the "already-absorbed" dose). {@code flat} /
     * {@code volatile} / absent stay bare -- their size is not the absorption signal.
     */
    private static String preTrendText(ObjectNode preTrend)
    {
        JsonNode label = preTrend.path("label");
        String name = label.isNull() ? "None" : label.asText();
        String text = name;
        if (name.equals("rising") || name.equals("falling"))
        {
            text += String.format(Locale.ROOT, " %+.2f%% (r2=%.2f)",
                    preTrend.path("slope_pct").asDouble(), preTrend.path("r_squared").asDouble());
        }
        return text;
    }

    /**
     * The {@code {market_signals}} block: a leading BTC price-context line (when the window's price
     * snapshot is available), the {@code btc_regime} multi-day line (only when EXTENDED &mdash; see
     * {@code RegimeSignal}; {@code regimeLine} is {@code ""}/null otherwise), an options priced-in
     * (complacency) line, and a funding-positioning line &mdash; each emitted only when present.
     * {@code optionsSignal} / {@code fundingSignal} / {@code priceContext} may be null. Deliberately
     * carries NO macro-regime / macro-trend line: the deep pass is a per-item event detector, and an
     * ambient macro mood would tint the fact/new/channel judgment (and corrupt the already-priced read)
     * without adding precision the positioning and pre_trend signals do not already provide. The
     * {@code btc_regime} line is different &mdash; a mechanical multi-day PRICE read, not a mood. Broad
     * market-state lives in the mechanical price lane.
     */
    public static String marketSignals(ObjectNode optionsSignal, ObjectNode fundingSignal,
                                       ObjectNode priceContext, String regimeLine)
    {
        List<String> lines = new ArrayList<>();
        String price = priceContextLine(priceContext);
        if (!price.isEmpty())
        {
            lines.add(price);
        }
        if (regimeLine != null && !regimeLine.isEmpty())
        {
            lines.add(regimeLine);
        }
        appendOptionsLine(lines, optionsSignal);
        appendFundingLine(lines, fundingSignal);
        return String.join("\n", lines);
    }

    /**
     * Perpetual-positioning line, e.g. {@code "positioning: crowded_long (funding +0.038%) | OI
     * +2.1%/1h building -> down_cascade_fuel"} -- which side is crowded, whether leverage is
     * building/unwinding, and the fused cascade/squeeze setup (the OI and setup parts appear only when
     * an open-interest delta is available). Omitted entirely when no funding snapshot exists.
     */
    private static void appendFundingLine(List<String> lines, ObjectNode funding)
    {
        if (funding != null && funding.has("positioning"))
        {
            StringBuilder line = new StringBuilder("positioning: ");
            line.append(funding.path("positioning").asText());
            line.append(String.format(Locale.ROOT, " (funding %+.3f%%)", funding.path("funding_rate_pct").asDouble()));
            if (funding.path("oi_change_pct").isNumber())
            {
                line.append(String.format(Locale.ROOT, " | OI %+.1f%%/1h %s",
                        funding.path("oi_change_pct").asDouble(), funding.path("oi_trend").asText()));
            }
            String setup = funding.path("setup").asText("");
            if (!setup.isEmpty() && !setup.equals("neutral"))
            {
                line.append(" -> ").append(setup);
            }
            lines.add(line.toString());
        }
    }

    /**
     * Window price-context line from the collector's stored snapshot: the current BTC price plus its
     * 1h and 24h percent change and its position in the 24h range. Empty string when no snapshot
     * exists, so the caller can omit the line. The 1h/24h frame is backdrop (already-priced-in
     * context), not a causal attribution -- the prompt guidance spells that out.
     */
    static String priceContextLine(ObjectNode price)
    {
        String line = "";
        if (price != null && price.has("btc_price"))
        {
            line = formatPriceContext(price);
        }
        return line;
    }

    private static String formatPriceContext(ObjectNode price)
    {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "btc_price: $%.2f", price.path("btc_price").asDouble()));
        if (price.has("change_1h_pct"))
        {
            text.append(String.format(Locale.ROOT, " | 1h %+.2f%%", price.path("change_1h_pct").asDouble()));
        }
        if (price.has("change_24h_pct"))
        {
            text.append(String.format(Locale.ROOT, " | 24h %+.2f%%", price.path("change_24h_pct").asDouble()));
            appendRangePosition(text, price);
        }
        return text.toString();
    }

    private static void appendRangePosition(StringBuilder text, ObjectNode price)
    {
        if (price.has("range_pos_24h"))
        {
            double pos = price.path("range_pos_24h").asDouble();
            text.append(" (").append(pos <= 0.25 ? "near 24h low" : (pos >= 0.75 ? "near 24h high" : "mid 24h range"))
                    .append(")");
        }
    }

    private static void appendOptionsLine(List<String> lines, ObjectNode options)
    {
        String pricedIn = options == null ? "" : options.path("priced_in").asText("");
        // Only the actionable states render -- complacent (amplify a real catalyst) or braced (fragility
        // note); normal/unknown add nothing and are omitted, like the other signals' neutral states.
        if (pricedIn.equals("complacent") || pricedIn.equals("braced"))
        {
            lines.add("options: " + pricedIn + optionsDetail(options));
        }
    }

    /** The IV level and trend behind the priced-in verdict, e.g. {@code " (IV 88%, rising)"}; empty when absent. */
    private static String optionsDetail(ObjectNode options)
    {
        List<String> parts = new ArrayList<>();
        JsonNode iv = options.path("near_atm_iv");
        if (iv.isNumber())
        {
            parts.add(String.format(Locale.ROOT, "IV %.0f%%", iv.asDouble()));
        }
        JsonNode trend = options.path("dvol_trend");
        if (trend.isTextual() && !trend.asText().equals("flat"))
        {
            parts.add(trend.asText());
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    /** The article's source name (Python {@code (a.get("source") or {}).get("name", "")}). */
    private static String source(ObjectNode article)
    {
        return article.path("source").path("name").asText("");
    }

    /** {@code publishedAt} trimmed to {@code "YYYY-MM-DD HH:MM"} (first 16 chars, {@code T} &rarr; space). */
    private static String pub(ObjectNode article)
    {
        String value = text(article, "publishedAt");
        String prefix = value.length() > PUB_PREFIX_LEN ? value.substring(0, PUB_PREFIX_LEN) : value;
        return prefix.replace('T', ' ');
    }

    private static String text(ObjectNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String truncate(String value, int max)
    {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
