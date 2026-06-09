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
 * Drives the BL#6 feedback loop: reads the stored {@code analysis_*.json} prediction records, scores
 * the matured ones against realized BTC moves (via the injected {@link PriceSource}), writes the
 * scored outcomes to {@code outcomes.jsonl}, and returns the {@link FeedbackReport} text. The
 * scoring/report logic is pure ({@link OutcomeScorer}/{@link FeedbackReport}); this class is just the
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
        writeOutcomes(dataDir, "outcomes.jsonl", windowOutcomes);
        writeOutcomes(dataDir, "article_outcomes.jsonl", articleOutcomes);
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
        File[] files = dataDir.listFiles((dir, name) -> name.startsWith("analysis_") && name.endsWith(".json"));
        if (files != null)
        {
            Arrays.sort(files);
            for (File file : files)
            {
                if (cutoffDay == null || dayOf(file).compareTo(cutoffDay) >= 0)
                {
                    loadFile(file, windowPreds, articlePreds);
                }
            }
        }
    }

    private static String dayOf(File file)
    {
        return file.getName().replace("analysis_", "").replace(".json", "");
    }

    private static void loadFile(File file, List<Prediction> windowPreds, List<ArticlePrediction> articlePreds)
    {
        String day = dayOf(file);
        try
        {
            JsonNode dayData = Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            Iterator<Map.Entry<String, JsonNode>> fields = dayData.fields();
            while (fields.hasNext())
            {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode record = entry.getValue().path("prediction_record");
                addWindowPrediction(windowPreds, day, entry.getKey(), record);
                addArticlePredictions(articlePreds, record);
            }
        }
        catch (IOException | RuntimeException badFile)
        {
            GlobalSystem.warning().writes(NAME, "Skipping unreadable " + file.getName(), badFile);
        }
    }

    private static void addWindowPrediction(List<Prediction> predictions, String day, String key, JsonNode record)
    {
        if (record.isObject() && record.path("btc_at_prediction").isNumber())
        {
            Instant windowTime = windowInstant(day, key);
            if (windowTime != null)
            {
                predictions.add(new Prediction(windowTime, record.path("btc_at_prediction").asDouble(),
                        record.path("direction").asText("neutral"), record.path("impact_tier").asText("noise"),
                        record.path("confidence").asText("low"), day, key));
            }
        }
    }

    private static void addArticlePredictions(List<ArticlePrediction> predictions, JsonNode record)
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
                            article.path("pre_trend").asText("")));
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

    private static void writeOutcomes(File dataDir, String fileName, List<ObjectNode> outcomes)
    {
        try
        {
            StringBuilder body = new StringBuilder();
            for (ObjectNode outcome : outcomes)
            {
                body.append(Json.toCompactString(outcome)).append('\n');
            }
            Files.writeString(new File(dataDir, fileName).toPath(), body.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException writeFailed)
        {
            GlobalSystem.warning().writes(NAME, "Failed to write " + fileName, writeFailed);
        }
    }
}
