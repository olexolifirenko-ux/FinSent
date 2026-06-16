package com.finsent.analyse.notify;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Coordinates the notification channels (ports Python {@code _maybe_notify} /
 * {@code _maybe_notify_macro_alert}). It applies the gate, formats the messages on the calling
 * thread, and dispatches the actual sends to a single daemon worker so analysis never blocks on the
 * network. The two channels ({@link TelegramNotifier}, {@link EmailNotifier}) self-skip when not
 * configured.
 */
public final class Notifier
{
    private final TelegramNotifier telegram_;
    private final EmailNotifier email_;
    private final String minImpactTier_;
    private final int newsAgeMinutes_;
    private final ExecutorService dispatch_;

    public Notifier(TelegramNotifier telegram, EmailNotifier email, String minImpactTier,
                    int newsAgeMinutes)
    {
        telegram_ = telegram;
        email_ = email;
        minImpactTier_ = minImpactTier;
        newsAgeMinutes_ = newsAgeMinutes;
        dispatch_ = Executors.newSingleThreadExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "FS-Notifier");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Fire window-alert notifications if the prediction passes the gate (non-neutral, tier &ge; the
     * minimum, and &mdash; unless {@code skipAgeCheck}, as for manual re-analysis of an old window
     * &mdash; a resonant article within the news-age window). Returns whether it notified.
     */
    public boolean maybeNotify(ObjectNode prediction, List<ObjectNode> articlePreds, List<ObjectNode> resonant,
                               String intervalKey, Instant now, boolean skipAgeCheck, Supplier<Double> realtimePrice)
    {
        boolean notify = NotifyGate.shouldNotify(prediction, resonant, minImpactTier_,
                newsAgeMinutes_, now, skipAgeCheck);
        if (notify)
        {
            int count = resonant.size();
            // Fetch the live BTC price only now that an alert is firing (avoids a per-analysis call).
            Double realtime = realtimePrice == null ? null : realtimePrice.get();
            sendTelegram(NotifyMessages.telegram(prediction, count, realtime), intervalKey);
            sendEmail(NotifyMessages.emailSubject(prediction),
                    NotifyMessages.emailBody(prediction, articlePreds, count, realtime), intervalKey);
        }
        return notify;
    }

    /** Fire macro-only-alert notifications if it passes the macro gate (non-neutral, tier &ge; min). */
    public void notifyMacroAlert(ObjectNode macroAlert, String intervalKey)
    {
        if (passesAlertGate(macroAlert))
        {
            sendTelegram(NotifyMessages.macroTelegram(macroAlert), intervalKey);
            sendEmail(NotifyMessages.macroEmailSubject(macroAlert),
                    NotifyMessages.macroEmailBody(macroAlert), intervalKey);
        }
    }

    /**
     * Fire scheduled-data-release alert notifications (#21) if it passes the same news-free gate as the
     * macro path (non-neutral, tier &ge; min); the release carries no resonant article, so there is no
     * age check.
     */
    public void notifyEconAlert(ObjectNode econAlert, String intervalKey)
    {
        if (passesAlertGate(econAlert))
        {
            sendTelegram(NotifyMessages.econTelegram(econAlert), intervalKey);
            sendEmail(NotifyMessages.econEmailSubject(econAlert),
                    NotifyMessages.econEmailBody(econAlert), intervalKey);
        }
    }

    /** The news-free alert gate shared by the macro and econ paths: non-neutral and tier &ge; the minimum. */
    private boolean passesAlertGate(ObjectNode alert)
    {
        int minTierVal = ImpactTier.order(minImpactTier_, 2);
        int tierVal = ImpactTier.order(alert.path("impact_tier").asText("noise"), 0);
        return !alert.path("direction").asText("neutral").equals("neutral") && tierVal >= minTierVal;
    }

    /** Stop the dispatch worker (shutdown hook). */
    public void shutdown()
    {
        dispatch_.shutdown();
    }

    private void sendTelegram(String message, String intervalKey)
    {
        if (telegram_.isConfigured())
        {
            dispatch_.submit(() -> telegram_.send(message, intervalKey));
        }
    }

    private void sendEmail(String subject, String body, String intervalKey)
    {
        if (email_.isConfigured())
        {
            dispatch_.submit(() -> email_.send(subject, body, intervalKey));
        }
    }
}
