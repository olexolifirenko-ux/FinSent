package com.finsent.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Aggregates scored outcomes into a directional-accuracy report (BL#6, the core of Python
 * {@code feedback_report.py}). Pure: outcomes in, report text out. Always prints the naive baselines
 * (always-up / always-down / random) the model must be read against &mdash; on a near-coin-flip
 * target a raw accuracy number is meaningless without them. Breaks accuracy down by {@code impact_tier}
 * and by {@code source}.
 */
public final class FeedbackReport
{
    private FeedbackReport()
    {
    }

    /** Window-level report only (no article outcomes). */
    public static String generate(List<ObjectNode> windowOutcomes)
    {
        return generate(windowOutcomes, List.of());
    }

    /** Render the report: window-level accuracy/baselines plus the per-scenario article-validation section. */
    public static String generate(List<ObjectNode> windowOutcomes, List<ObjectNode> articleOutcomes)
    {
        // Overall/baselines run on the real decision lanes (news/econ/macro); the *_mechanical priors are
        // duplicates of the econ/macro alerts kept only for the Claude-vs-mechanical comparison below.
        List<ObjectNode> real = nonMechanical(windowOutcomes);
        List<String> lines = new ArrayList<>();
        lines.add("=== FEEDBACK REPORT (" + real.size() + " scored window(s), "
                + articleOutcomes.size() + " scored article(s)) ===");
        if (real.isEmpty())
        {
            lines.add("No matured, scored window predictions yet.");
        }
        else
        {
            appendOverall(lines, real);
            appendBaselines(lines, directional(real));
            appendBreakdown(lines, real, "impact_tier", "By impact_tier (directional calls, 1h):");
            appendBreakdown(lines, real, "source", "By source (directional calls, 1h):");
            appendMoveMagnitude(lines, real);
            appendMechanicalComparison(lines, windowOutcomes);
        }
        appendScenarioSection(lines, articleOutcomes);
        return String.join("\n", lines);
    }

    /** Outcomes from the real decision lanes (news/econ/macro), excluding the {@code *_mechanical} priors. */
    private static List<ObjectNode> nonMechanical(List<ObjectNode> outcomes)
    {
        List<ObjectNode> real = new ArrayList<>();
        for (ObjectNode outcome : outcomes)
        {
            if (!outcome.path("source").asText("").endsWith("_mechanical"))
            {
                real.add(outcome);
            }
        }
        return real;
    }

    /**
     * For each of econ/macro that has a mechanical prior, compare the Claude call's directional accuracy
     * against the bare mechanical read's -- the empirical test of whether gating those alerts through
     * Claude (#21) beats the threshold prior. Omitted entirely when no {@code *_mechanical} outcomes exist.
     */
    private static void appendMechanicalComparison(List<String> lines, List<ObjectNode> outcomes)
    {
        boolean anyMechanical = false;
        for (ObjectNode outcome : outcomes)
        {
            anyMechanical = anyMechanical || outcome.path("source").asText("").endsWith("_mechanical");
        }
        if (anyMechanical)
        {
            lines.add("Claude vs mechanical prior (directional accuracy, 1h):");
            for (String lane : List.of("econ", "macro"))
            {
                List<ObjectNode> claude = bySource(outcomes, lane);
                List<ObjectNode> mechanical = bySource(outcomes, lane + "_mechanical");
                if (!claude.isEmpty() || !mechanical.isEmpty())
                {
                    lines.add("  " + lane + ": claude " + dirAccuracy(claude) + " vs mechanical " + dirAccuracy(mechanical));
                }
            }
        }
    }

    private static List<ObjectNode> bySource(List<ObjectNode> outcomes, String source)
    {
        List<ObjectNode> matching = new ArrayList<>();
        for (ObjectNode outcome : outcomes)
        {
            if (outcome.path("source").asText("").equals(source))
            {
                matching.add(outcome);
            }
        }
        return matching;
    }

    private static String dirAccuracy(List<ObjectNode> outcomes)
    {
        List<ObjectNode> directional = directional(outcomes);
        return ratio(countCorrect(directional), directional.size());
    }

    private static void appendScenarioSection(List<String> lines, List<ObjectNode> articleOutcomes)
    {
        Map<String, int[]> byScenario = new LinkedHashMap<>();
        for (ObjectNode outcome : articleOutcomes)
        {
            if (outcome.path("scenario_validated").isBoolean())
            {
                int[] tally = byScenario.computeIfAbsent(outcome.path("scenario").asText("?"), key -> new int[2]);
                tally[1]++;
                if (outcome.path("scenario_validated").asBoolean())
                {
                    tally[0]++;
                }
            }
        }
        if (!byScenario.isEmpty())
        {
            lines.add("Article scenarios validated (1h):");
            for (Map.Entry<String, int[]> entry : byScenario.entrySet())
            {
                lines.add("  " + entry.getKey() + ": " + ratio(entry.getValue()[0], entry.getValue()[1]));
            }
        }
    }

    private static void appendOverall(List<String> lines, List<ObjectNode> outcomes)
    {
        Map<String, Integer> byDir = new LinkedHashMap<>();
        for (ObjectNode outcome : outcomes)
        {
            byDir.merge(outcome.path("direction").asText("?"), 1, Integer::sum);
        }
        lines.add("Calls: bullish " + byDir.getOrDefault("bullish", 0)
                + ", bearish " + byDir.getOrDefault("bearish", 0)
                + ", neutral " + byDir.getOrDefault("neutral", 0));
        List<ObjectNode> directional = directional(outcomes);
        lines.add("1h directional accuracy (non-neutral calls): " + ratio(countCorrect(directional), directional.size()));
    }

    private static void appendBaselines(List<String> lines, List<ObjectNode> directional)
    {
        int n = directional.size();
        int up = 0;
        int down = 0;
        for (ObjectNode outcome : directional)
        {
            double pct = outcome.path("outcome_1h_pct").asDouble();
            if (pct > 0)
            {
                up++;
            }
            else if (pct < 0)
            {
                down++;
            }
        }
        lines.add("Baselines (same windows): always-up " + ratio(up, n) + ", always-down " + ratio(down, n)
                + ", random ~50.0%");
        lines.add("Model edge vs best naive baseline: " + signedPp(countCorrect(directional), Math.max(up, down), n));
    }

    private static void appendBreakdown(List<String> lines, List<ObjectNode> outcomes, String field, String header)
    {
        lines.add(header);
        Map<String, int[]> byKey = new LinkedHashMap<>();
        for (ObjectNode outcome : directional(outcomes))
        {
            int[] tally = byKey.computeIfAbsent(outcome.path(field).asText("?"), key -> new int[2]);
            tally[1]++;
            if (outcome.path("direction_correct").asBoolean())
            {
                tally[0]++;
            }
        }
        if (byKey.isEmpty())
        {
            lines.add("  (no directional calls)");
        }
        else
        {
            for (Map.Entry<String, int[]> entry : byKey.entrySet())
            {
                lines.add("  " + entry.getKey() + ": " + ratio(entry.getValue()[0], entry.getValue()[1]));
            }
        }
    }

    private static void appendMoveMagnitude(List<String> lines, List<ObjectNode> outcomes)
    {
        double sum = 0.0;
        int count = 0;
        for (ObjectNode outcome : outcomes)
        {
            if (outcome.path("outcome_1h_pct").isNumber())
            {
                sum += Math.abs(outcome.path("outcome_1h_pct").asDouble());
                count++;
            }
        }
        String mean = count == 0 ? "n/a" : String.format(Locale.ROOT, "%.3f%%", sum / count);
        lines.add("Mean realized |1h move|: " + mean + " (the magnitude any call had to get right)");
    }

    private static List<ObjectNode> directional(List<ObjectNode> outcomes)
    {
        List<ObjectNode> directional = new ArrayList<>();
        for (ObjectNode outcome : outcomes)
        {
            if (!outcome.path("direction").asText("neutral").equals("neutral"))
            {
                directional.add(outcome);
            }
        }
        return directional;
    }

    private static int countCorrect(List<ObjectNode> outcomes)
    {
        int correct = 0;
        for (ObjectNode outcome : outcomes)
        {
            if (outcome.path("direction_correct").asBoolean())
            {
                correct++;
            }
        }
        return correct;
    }

    /** {@code "c/t = p.p%"} (or {@code "0/0 = n/a"} when there is nothing to divide). */
    private static String ratio(int correct, int total)
    {
        String pct = total == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * correct / total);
        return correct + "/" + total + " = " + pct;
    }

    /** Signed percentage-point gap between the model's accuracy and a baseline count, over {@code total}. */
    private static String signedPp(int modelCorrect, int baselineCorrect, int total)
    {
        String gap;
        if (total == 0)
        {
            gap = "n/a";
        }
        else
        {
            gap = String.format(Locale.ROOT, "%+.1f pp", 100.0 * (modelCorrect - baselineCorrect) / total);
        }
        return gap;
    }
}
