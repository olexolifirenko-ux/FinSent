package com.finsent.feedback;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.feedback.OutcomeScorer.ArticlePrediction;
import com.finsent.feedback.OutcomeScorer.Prediction;
import com.finsent.feedback.OutcomeScorer.PriceSource;
import com.finsent.util.GlobalSystem;

/**
 * Drives the BL#6 feedback loop: reads the stored {@code <day>/analysis_<day>.json} records, scores the
 * matured ones against realized BTC moves (via the injected {@link PriceSource}), writes the scored
 * outcomes to {@code <day>/outcomes_<day>.jsonl} (+ {@code article_outcomes_<day>.jsonl}), and returns
 * the {@link FeedbackReport} text. It scores both Claude lanes -- the news {@code prediction_record}
 * and the {@code econ_alert} -- and, for the econ alert, also its mechanical prior (a
 * {@code *_mechanical} source) so the report can measure Claude's lift over the bare prior.
 * Scoring/report logic is pure ({@link OutcomeScorer}/{@link FeedbackReport}); this class is just the
 * file I/O and assembly.
 */
public final class FeedbackRunner
{
    private static final String NAME = "FeedbackRunner";

    private FeedbackRunner()
    {
    }

    /**
     * Score all matured window + article predictions in {@code dataDir}, persist outcomes, return the
     * report. {@code days} bounds the scan to the last N days of analysis files ({@code <= 0} = all).
     */
    public static String run(File dataDir, Instant now, PriceSource prices, int days)
    {
        List<Prediction> windowPreds = new ArrayList<>();
        List<ArticlePrediction> articlePreds = new ArrayList<>();
        load(dataDir, windowPreds, articlePreds, cutoffDay(now, days));
        List<ObjectNode> windowOutcomes = OutcomeScorer.score(windowPreds, now, prices);
        List<ObjectNode> articleOutcomes = OutcomeScorer.scoreArticles(articlePreds, now, prices);
        writeOutcomes(dataDir, "outcomes_", windowOutcomes);
        writeOutcomes(dataDir, "article_outcomes_", articleOutcomes);
        GlobalSystem.info().writes(NAME, "Scored " + windowOutcomes.size() + "/" + windowPreds.size()
                + " window(s), " + articleOutcomes.size() + "/" + articlePreds.size() + " article(s).");
        return FeedbackReport.generate(windowOutcomes, articleOutcomes);
    }

    /** The earliest YYYYMMDD day-file to include for the {@code --days} window, or null for all history. */
    private static String cutoffDay(Instant now, int days)
    {
        return days <= 0 ? null : Times.dayOf(Times.formatUtcIso(now.minus(Duration.ofDays(days))));
    }

    private static void load(File dataDir, List<Prediction> windowPreds, List<ArticlePrediction> articlePreds,
            String cutoffDay)
    {
        File[] dayDirs = dataDir.listFiles(File::isDirectory);
        if (dayDirs != null)
        {
            Arrays.sort(dayDirs);
            for (File dayDir : dayDirs)
            {
                String day = dayDir.getName();
                File analysis = new File(dayDir, "analysis_" + day + ".json");
                if (day.matches("\\d{8}") && analysis.isFile() && (cutoffDay == null || day.compareTo(cutoffDay) >= 0))
                {
                    loadFile(analysis, day, windowPreds, articlePreds);
                }
            }
        }
    }

    private static void loadFile(File file, String day, List<Prediction> windowPreds, List<ArticlePrediction> articlePreds)
    {
        try
        {
            JsonNode dayData = Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            Iterator<Map.Entry<String, JsonNode>> fields = dayData.fields();
            while (fields.hasNext())
            {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode interval = entry.getValue();
                addWindowPrediction(windowPreds, day, key, interval.path("prediction_record"), "news");
                addArticlePredictions(articlePreds, interval.path("prediction_record"), day);
                addAlertPrediction(windowPreds, day, key, interval.path("econ_alert"), "econ");
            }
        }
        catch (IOException | RuntimeException badFile)
        {
            GlobalSystem.warning().writes(NAME, "Skipping unreadable " + file.getName(), badFile);
        }
    }

    private static void addWindowPrediction(List<Prediction> predictions, String day, String key,
            JsonNode record, String source)
    {
        if (record.isObject() && record.path("btc_at_prediction").isNumber())
        {
            Instant windowTime = windowInstant(day, key);
            if (windowTime != null)
            {
                predictions.add(new Prediction(windowTime, record.path("btc_at_prediction").asDouble(),
                        record.path("direction").asText("neutral"), record.path("impact_tier").asText("noise"),
                        day, key, source));
            }
        }
    }

    /**
     * Add an econ/macro alert's <b>final</b> (Claude) call plus its <b>mechanical prior</b> (a
     * {@code <source>_mechanical} lane) so the report can compare them. The mechanical fields fall back
     * to the final ones when absent (a Claude-unavailable alert, where the two coincide).
     */
    private static void addAlertPrediction(List<Prediction> predictions, String day, String key,
            JsonNode alert, String source)
    {
        if (alert.isObject() && alert.path("btc_at_prediction").isNumber())
        {
            Instant windowTime = windowInstant(day, key);
            if (windowTime != null)
            {
                double base = alert.path("btc_at_prediction").asDouble();
                predictions.add(new Prediction(windowTime, base, alert.path("direction").asText("neutral"),
                        alert.path("impact_tier").asText("noise"), day, key, source));
                predictions.add(new Prediction(windowTime, base,
                        alert.path("mechanical_direction").asText(alert.path("direction").asText("neutral")),
                        alert.path("mechanical_tier").asText(alert.path("impact_tier").asText("noise")),
                        day, key, source + "_mechanical"));
            }
        }
    }

    private static void addArticlePredictions(List<ArticlePrediction> predictions, JsonNode record, String day)
    {
        if (record.isObject())
        {
            for (JsonNode article : record.path("articles"))
            {
                Instant publishedAt = parseInstant(article.path("published_at").asText(""));
                if (publishedAt != null && article.path("price_at_publish").isNumber())
                {
                    predictions.add(new ArticlePrediction(article.path("id").asInt(), publishedAt,
                            article.path("price_at_publish").asDouble(), article.path("scenario").asText(""),
                            article.path("pre_trend").asText(""), day));
                }
            }
        }
    }

    private static Instant parseInstant(String iso)
    {
        Instant instant = null;
        if (!iso.isEmpty())
        {
            try
            {
                instant = Times.parseIso(iso);
            }
            catch (RuntimeException malformed)
            {
                instant = null;
            }
        }
        return instant;
    }

    /** {@code day}=YYYYMMDD + {@code key}=HH:MM -&gt; the window's UTC instant, or null when malformed. */
    static Instant windowInstant(String day, String key)
    {
        Instant instant = null;
        if (day.length() == 8 && key.length() == 5)
        {
            try
            {
                instant = Times.parseIso(day.substring(0, 4) + "-" + day.substring(4, 6) + "-"
                        + day.substring(6, 8) + "T" + key + ":00Z");
            }
            catch (RuntimeException malformed)
            {
                instant = null;
            }
        }
        return instant;
    }

    /** Group outcomes by their {@code day} and write one {@code <day>/<prefix><day>.jsonl} per day. */
    private static void writeOutcomes(File dataDir, String prefix, List<ObjectNode> outcomes)
    {
        Map<String, List<ObjectNode>> byDay = new LinkedHashMap<>();
        for (ObjectNode outcome : outcomes)
        {
            byDay.computeIfAbsent(outcome.path("day").asText("unknown"), d -> new ArrayList<>()).add(outcome);
        }
        for (Map.Entry<String, List<ObjectNode>> entry : byDay.entrySet())
        {
            writeDayOutcomes(dataDir, prefix, entry.getKey(), entry.getValue());
        }
    }

    private static void writeDayOutcomes(File dataDir, String prefix, String day, List<ObjectNode> outcomes)
    {
        try
        {
            File dayDir = new File(dataDir, day);
            Files.createDirectories(dayDir.toPath());
            StringBuilder body = new StringBuilder();
            for (ObjectNode outcome : outcomes)
            {
                body.append(Json.toCompactString(outcome)).append('\n');
            }
            Files.writeString(new File(dayDir, prefix + day + ".jsonl").toPath(), body.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException writeFailed)
        {
            GlobalSystem.warning().writes(NAME, "Failed to write " + prefix + day, writeFailed);
        }
    }
}
