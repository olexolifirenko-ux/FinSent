package com.finsent.analyse.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure text formatting for the alert channels (ports Python {@code _format_telegram_message},
 * {@code _format_email_body}, and the message bodies built inside {@code _maybe_notify}). The Stage-7
 * notifiers call these to render the payload; the gate decision lives in {@link NotifyGate}.
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
    public static String telegram(ObjectNode pred, Double realtimePrice)
    {
        String events = bulletEvents(pred.path("key_events"));
        String reasoning = pred.path("reasoning").asText("");
        String price = priceText(pred, realtimePrice);
        // Event-first: lead with WHAT happened (the causative events + the analyst read), then the
        // materiality and a secondary directional lean.
        String message = "CRYPTO EVENT\n"
                + (events.isEmpty() ? "" : events + "\n")
                + (reasoning.isEmpty() ? "" : reasoning + "\n")
                + "Materiality " + upper(pred.path("impact_tier").asText("?")) + " | Lean " + lean(pred)
                + (price.isEmpty() ? "" : " | " + price);
        return message.strip();
    }

    /** The crypto lean for display: {@code BULLISH}/{@code BEARISH}, or {@code unclear} for a neutral lean. */
    private static String lean(ObjectNode pred)
    {
        String direction = pred.path("direction").asText("neutral");
        return direction.equals("neutral") ? "unclear" : upper(direction);
    }

    /** Email subject for an event alert: {@code Crypto Event -- <materiality> materiality, <lean> lean}. */
    public static String emailSubject(ObjectNode pred)
    {
        return "Crypto Event -- " + pred.path("impact_tier").asText("?") + " materiality, " + lean(pred) + " lean";
    }

    /** Extended email body: the event (materiality, lean, what happened) followed by the per-item breakdown. */
    public static String emailBody(ObjectNode pred, List<ObjectNode> articlePreds, Double realtimePrice)
    {
        List<String> lines = new ArrayList<>();
        lines.add("CRYPTO EVENT");
        lines.add("Materiality: " + pred.path("impact_tier").asText("?"));
        lines.add("Lean: " + lean(pred));
        String price = priceText(pred, realtimePrice);
        if (!price.isEmpty())
        {
            lines.add(price);
        }
        lines.add("Reasoning: " + pred.path("reasoning").asText(""));
        appendKeyEvents(lines, pred.path("key_events"));
        lines.add("");
        lines.add("--- ITEMS ---");
        lines.add("");
        appendArticleBreakdown(lines, articlePreds);
        return String.join("\n", lines);
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
