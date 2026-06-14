package com.finsent.analyse;

import java.io.File;
import java.io.IOException;
import java.time.Instant;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.claude.PromptBuilder;
import com.finsent.analyse.claude.PromptTemplates;
import com.finsent.analyse.notify.Notifier;
import com.finsent.analyse.pass.DeepAnalysisPass;
import com.finsent.collect.FSCollector;
import com.finsent.core.Config;
import com.finsent.core.Config.MacroThresholds;
import com.finsent.core.Times;
import com.finsent.util.GlobalSystem;

/**
 * Drives the standalone macro-alert path for a window (ports Python {@code check_macro_alert}): when
 * the window carries no resonant news, the tape is not weekend-stale, and {@link MacroAlert#detect}
 * fires, it builds the mechanical assessment as a prior, escalates it to an article-less Claude
 * judgment with the window's market context (#21), persists it, and notifies. The mechanical
 * {@link MacroAlert#detect} stays the cheap always-on gate; Claude adds the priced-in / conviction
 * read and can downgrade a noisy breach to {@code neutral} (so an already-absorbed sustained breach
 * self-suppresses, in place of a blunt time cooldown) -- falling back to the mechanical assessment
 * when the deep call is unavailable. The detect/assess decisions themselves are pure
 * ({@link MacroAlert}).
 */
public final class MacroAlertChecker
{
    private static final String NAME = "MacroAlertChecker";

    private final FSCollector collector_;
    private final AnalysisStore store_;
    private final Notifier notifier_;
    private final Config config_;
    private final DeepAnalysisPass deep_;
    private final File promptsDir_;

    public MacroAlertChecker(FSCollector collector, AnalysisStore store, Notifier notifier, Config config,
                             DeepAnalysisPass deep, File promptsDir)
    {
        collector_ = collector;
        store_ = store;
        notifier_ = notifier;
        config_ = config;
        deep_ = deep;
        promptsDir_ = promptsDir;
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
                fire(day, intervalKey, now, thresholds, current, optionsSignal, triggers);
            }
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
        WindowContext.MarketContext market = WindowContext.marketContext(collector_, day, intervalKey, config_.windowMinutes());
        ObjectNode macroTrend = WindowContext.macroTrend(collector_, day, intervalKey, config_.windowMinutes());
        ObjectNode mechanical = MacroAlert.assess(thresholds, current, triggers, optionsSignal, macroTrend, Times.formatUtcIso(now));
        ObjectNode alert = assessWithClaude(market.block(), mechanical);
        putAnchor(alert, market.anchor());
        store_.recordMacroAlert(day, intervalKey, alert);
        GlobalSystem.info().writes(NAME, "Macro alert " + day + " " + intervalKey
                + " -- direction=" + alert.path("direction").asText("?")
                + " impact=" + alert.path("impact_tier").asText("?")
                + " regime=" + alert.path("macro_regime").asText("?")
                + " triggers=" + alert.path("triggers").size()
                + (alert.path("claude_available").asBoolean() ? "" : " (mechanical-only)")
                + " -- " + alert.path("reasoning").asText());
        notifier_.notifyMacroAlert(alert, intervalKey);
    }

    /**
     * Escalate the mechanical assessment to an article-less Claude judgment with the window's market
     * context (#21): the breach is the catalyst, the mechanical read its prior. Claude can confirm,
     * downgrade to noise, or flip it. Falls back to the mechanical assessment when the prompt is missing
     * or the deep call fails -- so a Claude outage never silences a real macro alert.
     */
    private ObjectNode assessWithClaude(String marketBlock, ObjectNode mechanical)
    {
        ObjectNode alert = mechanical;
        try
        {
            String prompt = PromptTemplates.fillContext(PromptTemplates.load(promptsDir_, "macro_analysis"),
                    PromptBuilder.macroAlert(mechanical), marketBlock);
            ObjectNode claude = deep_.analyse(prompt).prediction();
            if (claude != null)
            {
                alert = withClaudeJudgment(mechanical, claude);
            }
        }
        catch (IOException promptMissing)
        {
            GlobalSystem.warning().writes(NAME, "macro_analysis prompt unavailable -- using mechanical assessment", promptMissing);
        }
        return alert;
    }

    /**
     * Overlay Claude's call (direction / impact_tier / confidence / reasoning / key_events) onto the
     * mechanical alert, keeping the mechanical fields (triggers, macro_regime, analyzed_at) and recording
     * the mechanical read as the prior. {@code direction} / {@code impact_tier} drive the notify gate.
     */
    private static ObjectNode withClaudeJudgment(ObjectNode mechanical, ObjectNode claude)
    {
        ObjectNode alert = mechanical.deepCopy();
        alert.put("mechanical_direction", mechanical.path("direction").asText("neutral"));
        alert.put("mechanical_tier", mechanical.path("impact_tier").asText("noise"));
        alert.put("claude_available", true);
        alert.put("direction", claude.path("direction").asText(mechanical.path("direction").asText("neutral")));
        alert.put("impact_tier", claude.path("impact_tier").asText(mechanical.path("impact_tier").asText("noise")));
        alert.put("confidence", claude.path("confidence").asText("low"));
        alert.put("reasoning", claude.path("reasoning").asText(mechanical.path("reasoning").asText("")));
        if (claude.has("key_events"))
        {
            alert.set("key_events", claude.get("key_events"));
        }
        return alert;
    }

    /** Set the {@code btc_at_prediction} price anchor (#6) on the alert, null-safe (explicit null when absent). */
    private static void putAnchor(ObjectNode alert, Double anchor)
    {
        if (anchor == null)
        {
            alert.putNull("btc_at_prediction");
        }
        else
        {
            alert.put("btc_at_prediction", anchor.doubleValue());
        }
    }
}
