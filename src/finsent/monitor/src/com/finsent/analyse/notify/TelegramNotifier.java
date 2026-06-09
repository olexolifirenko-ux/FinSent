package com.finsent.analyse.notify;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * Sends alert messages through the Telegram Bot API (ports Python {@code send_telegram}). A POST to
 * {@code <base>/bot<token>/sendMessage} as plain text with link previews disabled. Send failures are
 * logged, not thrown &mdash; notification is best-effort and must never break analysis.
 */
public final class TelegramNotifier
{
    private static final String NAME = "TelegramNotifier";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String token_;
    private final String chatId_;
    private final String baseUrl_;

    public TelegramNotifier(String token, String chatId, String baseUrl)
    {
        token_ = token;
        chatId_ = chatId;
        baseUrl_ = baseUrl;
    }

    /** Whether both a bot token and a chat id are configured. */
    public boolean isConfigured()
    {
        return !token_.isEmpty() && !chatId_.isEmpty();
    }

    /** Send {@code message} (plain text) to the configured chat; logs and swallows any failure. */
    public void send(String message, String intervalKey)
    {
        String tag = intervalKey.isEmpty() ? "" : "[" + intervalKey + "] ";
        try
        {
            Http.postJson(baseUrl_ + "/bot" + token_ + "/sendMessage", requestBody(message), Map.of(), TIMEOUT);
            GlobalSystem.info().writes(NAME, tag + "Telegram notification sent.");
        }
        catch (IOException sendFailed)
        {
            GlobalSystem.warning().writes(NAME, tag + "Telegram send failed", sendFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, tag + "Telegram send interrupted");
        }
    }

    private String requestBody(String message) throws IOException
    {
        ObjectNode body = Json.newObject();
        body.put("chat_id", chatId_);
        body.put("text", message);
        // Sent as plain text deliberately: the body interpolates LLM-generated free text (reasoning,
        // key_events) and values like the macro regime "risk_off", which contain Markdown
        // metacharacters (_ * [ `). Telegram's legacy "Markdown" parse mode has no reliable escape
        // mechanism, so any such character triggers an "can't parse entities" HTTP 400. Do not
        // re-add a parse_mode without escaping every interpolated field (e.g. via MarkdownV2/HTML).
        body.put("disable_web_page_preview", true);
        return Json.toCompactString(body);
    }
}
