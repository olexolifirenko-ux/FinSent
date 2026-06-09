package com.finsent.analyse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.notify.Notifier;
import com.finsent.collect.FSCollector;
import com.finsent.core.Config;
import com.finsent.core.Config.MacroThresholds;
import com.finsent.core.Times;
import com.finsent.util.GlobalSystem;

/**
 * Drives the standalone macro-alert path for a window (ports Python {@code check_macro_alert}): when
 * the window carries no resonant news, the tape is not weekend-stale, the cooldown has elapsed, and
 * {@link MacroAlert#detect} fires, it builds the mechanical assessment, persists it, and notifies.
 * Holds the in-memory cooldown timestamp; the detect/assess decisions themselves are pure
 * ({@link MacroAlert}). All reads are of the collector's context registries.
 */
public final class MacroAlertChecker
{
    private static final String NAME = "MacroAlertChecker";

    private final FSCollector collector_;
    private final AnalysisStore store_;
    private final Notifier notifier_;
    private final Config config_;
    private Instant lastAlertAt_;

    public MacroAlertChecker(FSCollector collector, AnalysisStore store, Notifier notifier, Config config)
    {
        collector_ = collector;
        store_ = store;
        notifier_ = notifier;
        config_ = config;
    }

    /** Check the window for a macro-only alert and fire it if all conditions hold. */
    public synchronized void check(String day, String intervalKey, Instant now)
    {
        if (shouldCheck(day, intervalKey))
        {
            int windowMinutes = config_.windowMinutes();
            ObjectNode current = collector_.macro().get(day, intervalKey);
            ObjectNode previous = WindowContext.previousMacro(collector_, day, intervalKey, windowMinutes);
            ObjectNode optionsSignal = WindowContext.optionsSignal(collector_, day, intervalKey, windowMinutes);
            MacroThresholds thresholds = config_.macroAlertThresholds();
            ArrayNode triggers = MacroAlert.detect(thresholds, current, previous, optionsSignal);
            if (triggers != null)
            {
                handleTriggers(day, intervalKey, now, thresholds, current, optionsSignal, triggers);
            }
        }
    }

    /** Fire the macro alert, or log (at debug) that it was suppressed because the cooldown is active. */
    private void handleTriggers(String day, String intervalKey, Instant now, MacroThresholds thresholds,
                                ObjectNode current, ObjectNode optionsSignal, ArrayNode triggers)
    {
        if (onCooldown(now, thresholds.cooldownInMin()))
        {
            GlobalSystem.debug().writes(NAME, "Macro thresholds tripped " + day + " " + intervalKey
                    + " -- suppressed (cooldown)");
        }
        else
        {
            fire(day, intervalKey, now, thresholds, current, optionsSignal, triggers);
        }
    }

    private boolean shouldCheck(String day, String intervalKey)
    {
        boolean check = true;
        if (store_.hasResonant(day, intervalKey))
        {
            check = false; // window already has resonant news
        }
        else if (Intervals.isWeekend(day))
        {
            check = false; // weekend data is stale (Friday close repeating)
        }
        else if (collector_.macro().get(day, intervalKey).isEmpty())
        {
            check = false; // no macro snapshot for the window
        }
        return check;
    }

    private void fire(String day, String intervalKey, Instant now, MacroThresholds thresholds,
                      ObjectNode current, ObjectNode optionsSignal, ArrayNode triggers)
    {
        ObjectNode macroTrend = WindowContext.macroTrend(collector_, day, intervalKey, config_.windowMinutes());
        ObjectNode alert = MacroAlert.assess(thresholds, current, triggers, optionsSignal, macroTrend, Times.formatUtcIso(now));
        store_.recordMacroAlert(day, intervalKey, alert);
        lastAlertAt_ = now;
        GlobalSystem.info().writes(NAME, "Macro alert " + day + " " + intervalKey
                + " -- direction=" + alert.path("direction").asText("?")
                + " impact=" + alert.path("impact_tier").asText("?")
                + " regime=" + alert.path("macro_regime").asText("?")
                + " triggers=" + alert.path("triggers").size()
                + " -- " + alert.path("reasoning").asText());
        notifier_.notifyMacroAlert(alert, intervalKey);
    }

    private boolean onCooldown(Instant now, int cooldownMinutes)
    {
        return lastAlertAt_ != null && now.isBefore(lastAlertAt_.plus(cooldownMinutes, ChronoUnit.MINUTES));
    }
}
