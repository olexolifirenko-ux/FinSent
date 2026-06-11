package com.finsent.analyse.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure text formatting for the alert channels (ports Python {@code _format_telegram_message},
 * {@code _format_email_body}, and the message bodies built inside {@code _maybe_notify} /
 * {@code _maybe_notify_macro_alert}). The Stage-7 notifiers call these to render the payload; the
 * gate decision lives in {@link NotifyGate}.
 */
public final class NotifyMessages
{
    private static final int MAX_KEY_EVENTS = 3;
    private static final int MAX_TITLE_LEN = 80;

    private NotifyMessages()
    {
    }

    /**
     * Concise Telegram prediction message (plain text). {@code realtimePrice} is the live BTC price at
     * send time (live regular/urgent path), or null when unavailable (manual re-analysis / fetch failed);
     * when present it is shown next to the catalyst-time price with the % move since.
     */
    public static String telegram(ObjectNode pred, int resonantCount, Double realtimePrice)
    {
        String direction = pred.path("direction").asText("?");
        String tier = pred.path("impact_tier").asText("?");
        String regime = pred.path("macro_regime").asText("?");
        String reasoning = pred.path("reasoning").asText("");
        String price = priceText(pred, realtimePrice);
        String context = contextLine(pred);
        // confidence is deliberately NOT shown in the alert body: it's model self-rated and
        // uncalibrated, so surfacing it to a trader manufactures false certainty. It stays in the
        // stored record (for the feedback loop to validate) and the operational log line only.
        String message = "BTC ALERT (" + countPhrase(resonantCount) + ")\n"
                + (price.isEmpty() ? "" : price + "\n")
                + upper(direction) + " | " + tier + " impact | macro: " + regime + "\n"
                + (context.isEmpty() ? "" : context + "\n")
                + reasoning + "\n"
                + bulletEvents(pred.path("key_events"));
        return message.strip();
    }

    /** Email subject for a window alert: {@code BTC Alert: <DIRECTION>}. */
    public static String emailSubject(ObjectNode pred)
    {
        String direction = pred.path("direction").asText("");
        return "BTC Alert: " + upper(direction.isEmpty() ? "neutral" : direction);
    }

    /** Extended email body: aggregate prediction followed by the per-article breakdown. */
    public static String emailBody(ObjectNode pred, List<ObjectNode> articlePreds, int resonantCount,
                                   Double realtimePrice)
    {
        List<String> lines = new ArrayList<>();
        lines.add("BTC ANALYSIS (" + countPhrase(resonantCount) + ")");
        lines.add("Direction: " + upper(pred.path("direction").asText("?")));
        lines.add("Impact: " + pred.path("impact_tier").asText("?"));
        lines.add("Macro regime: " + pred.path("macro_regime").asText("?"));
        String price = priceText(pred, realtimePrice);
        if (!price.isEmpty())
        {
            lines.add(price);
        }
        String positioning = positioningTag(pred);
        if (!positioning.isEmpty())
        {
            lines.add("Positioning: " + positioning);
        }
        String range = rangeTag(pred);
        if (!range.isEmpty())
        {
            lines.add("24h range: " + range);
        }
        lines.add("Reasoning: " + pred.path("reasoning").asText(""));
        appendKeyEvents(lines, pred.path("key_events"));
        lines.add("");
        lines.add("--- PER-ARTICLE ANALYSIS ---");
        lines.add("");
        appendArticleBreakdown(lines, articlePreds);
        return String.join("\n", lines);
    }

    /** Telegram message for a macro-only alert. */
    public static String macroTelegram(ObjectNode macroAlert)
    {
        return "MACRO ALERT (no resonant news)\n"
                + upper(macroAlert.path("direction").asText("neutral")) + " | "
                + macroAlert.path("impact_tier").asText("noise") + " impact | macro: "
                + macroAlert.path("macro_regime").asText("?") + "\n"
                + "Triggers: " + triggerString(macroAlert.path("triggers")) + "\n"
                + macroAlert.path("reasoning").asText("");
    }

    /** Email subject for a macro-only alert: {@code BTC Macro Alert: <DIRECTION>}. */
    public static String macroEmailSubject(ObjectNode macroAlert)
    {
        return "BTC Macro Alert: " + upper(macroAlert.path("direction").asText("neutral"));
    }

    /** Email body for a macro-only alert. */
    public static String macroEmailBody(ObjectNode macroAlert)
    {
        return "MACRO-ONLY BTC ALERT\n"
                + "Direction: " + upper(macroAlert.path("direction").asText("neutral")) + "\n"
                + "Impact: " + macroAlert.path("impact_tier").asText("noise") + "\n"
                + "Macro regime: " + macroAlert.path("macro_regime").asText("?") + "\n"
                + "Reasoning: " + macroAlert.path("reasoning").asText("") + "\n\n"
                + "Triggered indicators: " + triggerString(macroAlert.path("triggers")) + "\n"
                + "No resonant news articles in this monitoring window.\n";
    }

    /** Telegram message for a scheduled-data-release alert (no resonant news). */
    public static String econTelegram(ObjectNode econAlert)
    {
        return "DATA ALERT (" + econAlert.path("event").asText("scheduled release") + ")\n"
                + upper(econAlert.path("direction").asText("neutral")) + " | "
                + econAlert.path("impact_tier").asText("noise") + " impact | macro: "
                + econAlert.path("macro_regime").asText("?") + "\n"
                + econAlert.path("label").asText("") + "\n"
                + econAlert.path("reasoning").asText("");
    }

    /** Email subject for a scheduled-data-release alert: {@code BTC Data Alert: <DIRECTION>}. */
    public static String econEmailSubject(ObjectNode econAlert)
    {
        return "BTC Data Alert: " + upper(econAlert.path("direction").asText("neutral"));
    }

    /** Email body for a scheduled-data-release alert. */
    public static String econEmailBody(ObjectNode econAlert)
    {
        return "SCHEDULED DATA RELEASE BTC ALERT\n"
                + "Event: " + econAlert.path("label").asText("") + "\n"
                + "Direction: " + upper(econAlert.path("direction").asText("neutral")) + "\n"
                + "Impact: " + econAlert.path("impact_tier").asText("noise") + "\n"
                + "Macro regime: " + econAlert.path("macro_regime").asText("?") + "\n"
                + "Mechanical prior: " + econAlert.path("mechanical_direction").asText("neutral")
                + " / " + econAlert.path("mechanical_tier").asText("noise") + "\n"
                + "Reasoning: " + econAlert.path("reasoning").asText("") + "\n\n"
                + "No resonant news -- this is a scheduled data-release surprise.\n";
    }

    private static void appendKeyEvents(List<String> lines, JsonNode keyEvents)
    {
        if (keyEvents.size() > 0)
        {
            lines.add("Key events:");
            int count = Math.min(MAX_KEY_EVENTS, keyEvents.size());
            for (int i = 0; i < count; i++)
            {
                lines.add("  - " + keyEvents.get(i).asText());
            }
        }
    }

    private static void appendArticleBreakdown(List<String> lines, List<ObjectNode> articlePreds)
    {
        if (articlePreds == null || articlePreds.isEmpty())
        {
            lines.add("(no per-article analysis available)");
        }
        else
        {
            for (ObjectNode article : articlePreds)
            {
                appendArticleEntry(lines, article);
            }
        }
    }

    private static void appendArticleEntry(List<String> lines, ObjectNode article)
    {
        String title = truncate(text(article, "title", "untitled"), MAX_TITLE_LEN);
        String time = hhmm(article.path("published_at").asText(""));
        String url = article.path("url").asText("");
        String reasoning = article.path("reasoning").asText("");
        lines.add("[" + upper(article.path("direction").asText("?")) + "] "
                + (time.isEmpty() ? "" : time + "  ") + title);
        lines.add("  Scenario: " + article.path("scenario").asText("?"));
        if (!url.isEmpty())
        {
            lines.add("  " + url);
        }
        if (!reasoning.isEmpty())
        {
            lines.add("  " + reasoning);
        }
        lines.add("");
    }

    /** {@code "<n> article(s)"} with the Python plural rule (singular only at exactly 1). */
    private static String countPhrase(int count)
    {
        return count + " article" + (count != 1 ? "s" : "");
    }

    /**
     * The BTC price line (no trailing newline), {@code ""} when no price is available. With a live
     * {@code realtime} price it reads {@code "BTC: $60,900 now | $61,362 at news (-0.8% since, 16:38)"}
     * &mdash; the catalyst-time price ({@code btc_at_prediction}), the live price, and the % move since
     * the freshest resonant article; otherwise it falls back to just the catalyst (or just-now) price.
     */
    private static String priceText(ObjectNode pred, Double realtime)
    {
        JsonNode catalystNode = pred.path("btc_at_prediction");
        boolean haveCatalyst = catalystNode.isNumber() && catalystNode.asDouble() != 0.0;
        boolean haveRealtime = realtime != null && realtime != 0.0;
        String text = "";
        if (haveRealtime && haveCatalyst)
        {
            double cat = catalystNode.asDouble();
            String move = String.format(Locale.US, "%+.1f%%", (realtime - cat) / cat * 100.0);
            String at = catalystTime(pred);
            text = String.format(Locale.US, "BTC: $%,.0f now | $%,.0f at news (%s since%s)",
                    realtime, cat, move, at.isEmpty() ? "" : ", " + at);
        }
        else if (haveCatalyst)
        {
            text = String.format(Locale.US, "BTC: $%,.0f", catalystNode.asDouble());
        }
        else if (haveRealtime)
        {
            text = String.format(Locale.US, "BTC: $%,.0f now", realtime);
        }
        return text;
    }

    /** {@code HH:MM} of the newest resonant article (the catalyst {@code btc_at_prediction} anchors to). */
    private static String catalystTime(ObjectNode pred)
    {
        String latest = "";
        for (JsonNode article : pred.path("articles"))
        {
            String pub = article.path("published_at").asText("");
            if (pub.compareTo(latest) > 0)
            {
                latest = pub;
            }
        }
        return hhmm(latest);
    }

    /** {@code "2026-06-09T16:38:07Z"} &rarr; {@code "16:38"}; {@code ""} when not a parseable ISO instant. */
    private static String hhmm(String iso)
    {
        String result = "";
        if (iso.length() >= 16 && iso.charAt(10) == 'T')
        {
            result = iso.substring(11, 16);
        }
        return result;
    }

    /** Mechanical-context line for telegram: funding crowding + 24h range position, " · "-joined; "" when neither. */
    private static String contextLine(ObjectNode pred)
    {
        List<String> parts = new ArrayList<>();
        String positioning = positioningTag(pred);
        if (!positioning.isEmpty())
        {
            parts.add(positioning);
        }
        String range = rangeTag(pred);
        if (!range.isEmpty())
        {
            parts.add(range);
        }
        return String.join(" · ", parts);
    }

    /** Funding crowding as {@code "crowded_long (cascade risk)"} / {@code "crowded_short (squeeze risk)"}; "" when
     *  neutral/absent. Crowded longs cascade on a drop (liquidations), crowded shorts squeeze on a pop. */
    private static String positioningTag(ObjectNode pred)
    {
        String positioning = pred.path("funding_signal").path("positioning").asText("");
        String tag = "";
        if (!positioning.isEmpty() && !positioning.equals("neutral"))
        {
            tag = positioning + (positioning.endsWith("long") ? " (cascade risk)" : " (squeeze risk)");
        }
        return tag;
    }

    /** 24h range position as {@code "near 24h low"|"near 24h high"|"mid 24h range"}; "" when no price context. */
    private static String rangeTag(ObjectNode pred)
    {
        JsonNode pos = pred.path("price_context").path("range_pos_24h");
        String tag = "";
        if (pos.isNumber())
        {
            double position = pos.asDouble();
            tag = position <= 0.25 ? "near 24h low" : (position >= 0.75 ? "near 24h high" : "mid 24h range");
        }
        return tag;
    }

    private static String bulletEvents(JsonNode keyEvents)
    {
        List<String> bullets = new ArrayList<>();
        int count = Math.min(MAX_KEY_EVENTS, keyEvents.size());
        for (int i = 0; i < count; i++)
        {
            bullets.add("• " + keyEvents.get(i).asText());
        }
        return String.join("\n", bullets);
    }

    private static String triggerString(JsonNode triggers)
    {
        List<String> parts = new ArrayList<>();
        for (JsonNode trigger : triggers)
        {
            parts.add(trigger.path("name").asText() + " "
                    + String.format(Locale.ROOT, "%+.1f%%", trigger.path("delta_pct").asDouble()));
        }
        return String.join(", ", parts);
    }

    private static String text(ObjectNode node, String field, String defaultValue)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asText();
    }

    private static String truncate(String value, int max)
    {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String upper(String value)
    {
        return value.toUpperCase(Locale.ROOT);
    }
}
