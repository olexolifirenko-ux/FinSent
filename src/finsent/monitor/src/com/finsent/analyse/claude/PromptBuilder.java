package com.finsent.analyse.claude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
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
    private static final int TREND_STREAK_MIN = 2;

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
     * snapshot is available), the macro regime (with the breaching-indicator detail), an
     * options-positioning line, a funding-positioning line, and a macro-trend line &mdash; each
     * emitted only when its signal is present. {@code optionsSignal} / {@code fundingSignal} /
     * {@code macroTrend} / {@code priceContext} may be null.
     */
    public static String marketSignals(ObjectNode regime, ObjectNode optionsSignal, ObjectNode fundingSignal,
                                       ObjectNode macroTrend, ObjectNode priceContext)
    {
        List<String> lines = new ArrayList<>();
        String price = priceContextLine(priceContext);
        if (!price.isEmpty())
        {
            lines.add(price);
        }
        appendRegimeLines(lines, regime);
        appendOptionsLine(lines, optionsSignal);
        appendFundingLine(lines, fundingSignal);
        appendMacroTrendLine(lines, macroTrend);
        return String.join("\n", lines);
    }

    /**
     * Funding-rate positioning line, e.g. {@code "funding: +0.038% crowded_long"} -- which side of the
     * perpetual market is crowded (and thus where a catalyst can cascade/squeeze). Omitted when absent.
     */
    private static void appendFundingLine(List<String> lines, ObjectNode funding)
    {
        if (funding != null && funding.has("positioning"))
        {
            lines.add("funding: "
                    + String.format(Locale.ROOT, "%+.3f%%", funding.path("funding_rate_pct").asDouble())
                    + " " + funding.path("positioning").asText());
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

    private static void appendRegimeLines(List<String> lines, ObjectNode regime)
    {
        String name = regime.path("regime").asText();
        JsonNode triggered = regime.path("triggered");
        lines.add("macro_regime: " + name);
        boolean detailed = (name.equals("risk_off") || name.equals("risk_on") || name.equals("mixed"))
                && triggered.size() > 0;
        if (detailed)
        {
            lines.add("macro_detail: " + triggered.size() + "/5 indicators (" + joinNode(triggered) + ")");
        }
    }

    private static void appendOptionsLine(List<String> lines, ObjectNode options)
    {
        boolean present = options != null && !options.path("signal_strength").asText("none").equals("none");
        if (present)
        {
            lines.add("options_signal: " + options.path("signal_strength").asText()
                    + " (" + String.join(", ", optionsParts(options)) + ")");
        }
    }

    private static List<String> optionsParts(ObjectNode options)
    {
        List<String> parts = new ArrayList<>();
        parts.add(options.path("positioning").asText());
        JsonNode pc = options.path("near_pc_ratio");
        if (pc.isNumber())
        {
            parts.add(String.format(Locale.ROOT, "P/C=%.2f", pc.asDouble()));
        }
        if (options.path("iv_elevated").asBoolean())
        {
            parts.add("IV elevated");
        }
        if (options.path("oi_surge").asBoolean())
        {
            parts.add("OI surge");
        }
        JsonNode dvol = options.path("dvol_trend");
        if (dvol.isTextual() && !dvol.asText().equals("flat"))
        {
            parts.add("DVOL " + dvol.asText());
        }
        return parts;
    }

    private static void appendMacroTrendLine(List<String> lines, ObjectNode macroTrend)
    {
        boolean active = macroTrend != null && (macroTrend.path("sustained").asBoolean()
                || !macroTrend.path("net_trend").asText("flat").equals("flat"));
        if (active)
        {
            List<String> parts = trendingParts(macroTrend.path("indicators"));
            if (!parts.isEmpty())
            {
                String sustainedTag = macroTrend.path("sustained").asBoolean() ? " sustained" : "";
                lines.add("macro_trend: " + macroTrend.path("net_trend").asText() + sustainedTag
                        + " (" + String.join(", ", parts) + ")");
            }
        }
    }

    private static List<String> trendingParts(JsonNode indicators)
    {
        List<String> parts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = indicators.fields();
        while (fields.hasNext())
        {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode info = entry.getValue();
            boolean trending = !info.path("direction").asText().equals("flat")
                    && info.path("streak").asInt() >= TREND_STREAK_MIN;
            if (trending)
            {
                parts.add(entry.getKey() + " " + info.path("direction").asText() + " "
                        + info.path("streak").asInt() + "w "
                        + String.format(Locale.ROOT, "%+.1f%%", info.path("cumulative_delta").asDouble()));
            }
        }
        return parts;
    }

    private static String joinNode(JsonNode array)
    {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array)
        {
            values.add(element.asText());
        }
        return String.join(", ", values);
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
