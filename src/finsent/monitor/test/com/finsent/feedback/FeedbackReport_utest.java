package com.finsent.feedback;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link FeedbackReport}: the empty case, and that a synthetic outcome set yields the
 * directional accuracy, the naive baselines, and the impact_tier / confidence breakdowns.
 */
public class FeedbackReport_utest
{
    @Test
    public void emptyReportIsExplicit()
    {
        String report = FeedbackReport.generate(List.of());
        assertTrue(report.contains("0 scored window"));
        assertTrue(report.contains("No matured"));
    }

    @Test
    public void accuracyBaselinesAndBreakdowns()
    {
        List<ObjectNode> outcomes = new ArrayList<>();
        outcomes.add(outcome("bullish", "high", "high", 1.0, true));
        outcomes.add(outcome("bullish", "high", "low", 0.8, true));
        outcomes.add(outcome("bullish", "low", "low", -0.5, false));
        outcomes.add(outcome("bearish", "high", "high", -1.2, true));
        outcomes.add(outcome("neutral", "noise", "low", 0.01, true));

        String report = FeedbackReport.generate(outcomes);

        assertTrue("scored count", report.contains("5 scored window"));
        assertTrue("call counts", report.contains("bullish 3, bearish 1, neutral 1"));
        assertTrue("non-neutral accuracy 3/4", report.contains("3/4 = 75.0%"));
        assertTrue("baselines line", report.contains("always-up"));
        assertTrue("impact_tier breakdown", report.contains("By impact_tier"));
        assertTrue("confidence breakdown (BL#5)", report.contains("BL#5 validation"));
        assertTrue("mean move line", report.contains("Mean realized |1h move|"));
    }

    @Test
    public void scenarioSectionFromArticleOutcomes()
    {
        List<ObjectNode> articles = new ArrayList<>();
        articles.add(articleOutcome("fresh_bearish", true));
        articles.add(articleOutcome("fresh_bearish", false));
        articles.add(articleOutcome("noise", true));

        String report = FeedbackReport.generate(List.of(), articles);

        assertTrue("header counts both", report.contains("0 scored window(s), 3 scored article(s)"));
        assertTrue("scenario header", report.contains("Article scenarios validated"));
        assertTrue("fresh_bearish 1/2", report.contains("fresh_bearish: 1/2 = 50.0%"));
        assertTrue("noise 1/1", report.contains("noise: 1/1 = 100.0%"));
    }

    @Test
    public void sourceBreakdownAndClaudeVsMechanicalComparison()
    {
        List<ObjectNode> outcomes = new ArrayList<>();
        outcomes.add(sourced("macro", "bearish", -1.0, true));            // claude call: correct
        outcomes.add(sourced("macro_mechanical", "bearish", -1.0, true)); // its prior: also correct
        outcomes.add(sourced("econ", "neutral", -0.5, false));            // claude suppressed to neutral
        outcomes.add(sourced("econ_mechanical", "bullish", -0.5, false)); // its prior: wrong directional bet

        String report = FeedbackReport.generate(outcomes);

        assertTrue("header counts real lanes only", report.contains("2 scored window"));
        assertTrue("by source section", report.contains("By source (directional calls, 1h):"));
        assertTrue("comparison section", report.contains("Claude vs mechanical prior"));
        assertTrue("macro pair", report.contains("macro: claude 1/1 = 100.0% vs mechanical 1/1 = 100.0%"));
        assertTrue("econ pair (claude made no bet, mechanical bet wrong)",
                report.contains("econ: claude 0/0 = n/a vs mechanical 0/1 = 0.0%"));
    }

    private static ObjectNode sourced(String source, String direction, double pct1h, boolean correct)
    {
        ObjectNode outcome = Json.newObject();
        outcome.put("source", source);
        outcome.put("direction", direction);
        outcome.put("outcome_1h_pct", pct1h);
        outcome.put("direction_correct", correct);
        return outcome;
    }

    private static ObjectNode articleOutcome(String scenario, boolean validated)
    {
        ObjectNode outcome = Json.newObject();
        outcome.put("scenario", scenario);
        outcome.put("scenario_validated", validated);
        return outcome;
    }

    private static ObjectNode outcome(String dir, String tier, String conf, double pct1h, boolean correct)
    {
        ObjectNode outcome = Json.newObject();
        outcome.put("direction", dir);
        outcome.put("impact_tier", tier);
        outcome.put("confidence", conf);
        outcome.put("outcome_1h_pct", pct1h);
        outcome.put("direction_correct", correct);
        return outcome;
    }
}
