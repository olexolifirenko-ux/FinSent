package com.finsent.feedback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;
import com.finsent.core.Times;

/**
 * Scores past window-level predictions against the realized BTC move (BL#6, the window-level subset of
 * Python {@code score_past_predictions.py}). Pure: the caller supplies the matured predictions, the
 * reference {@code now}, and a {@link PriceSource} (so the realized-price lookup is injectable for
 * tests). A prediction is scored once it is at least one hour old and a 1h price is available; the
 * 24h move is added when that horizon has also matured. Direction correctness uses the 1h move with
 * the Python rule: bullish &rarr; up, bearish &rarr; down, neutral &rarr; |move| &lt; 0.05%.
 *
 * <p>Article-level scoring ({@link #scoreArticles}) validates each resonant article's scenario label
 * against its realized 1h move, now that the analysis record carries the per-article
 * {@code published_at}/{@code price_at_publish}/{@code pre_trend} those checks need.
 */
public final class OutcomeScorer
{
    /** A neutral call is "correct" when the realized 1h move stays within this band (percent). */
    static final double NEUTRAL_BAND_PCT = 0.05;
    /** A front-run call ("already priced in") is validated when the post-publication 1h move stays small. */
    static final double FRONT_RUN_ABSORBED_PCT = 0.5;

    private static final Duration H1 = Duration.ofHours(1);
    private static final Duration H24 = Duration.ofHours(24);

    /** Realized BTC price at an instant, or {@code null} when unavailable. */
    public interface PriceSource
    {
        Double priceAt(Instant target);
    }

    /**
     * One matured prediction to score: the window time, the price anchor, the call's labels, and the
     * {@code source} lane ({@code news} / {@code econ} / {@code macro}, or {@code *_mechanical} for the
     * mechanical prior of an econ/macro alert -- so the report can compare Claude vs the bare prior).
     */
    public record Prediction(Instant windowTime, double btcBase, String direction, String impactTier,
                             String day, String key, String source)
    {
    }

    /** One resonant article to score: its publish time, the price at publication, its scenario/pre-trend, and day. */
    public record ArticlePrediction(int articleId, Instant publishedAt, double priceAtPublish,
                                    String scenario, String preTrend, String day)
    {
    }

    private OutcomeScorer()
    {
    }

    /** Score every prediction that has matured to the 1h horizon; immature ones are skipped. */
    public static List<ObjectNode> score(List<Prediction> predictions, Instant now, PriceSource prices)
    {
        List<ObjectNode> outcomes = new ArrayList<>();
        for (Prediction prediction : predictions)
        {
            ObjectNode outcome = scoreOne(prediction, now, prices);
            if (outcome != null)
            {
                outcomes.add(outcome);
            }
        }
        return outcomes;
    }

    private static ObjectNode scoreOne(Prediction prediction, Instant now, PriceSource prices)
    {
        ObjectNode outcome = null;
        Double pct1h = realizedPct(prediction, H1, now, prices);
        if (pct1h != null)
        {
            outcome = Json.newObject();
            outcome.put("window", Times.formatUtcIso(prediction.windowTime()));
            outcome.put("day", prediction.day());
            outcome.put("key", prediction.key());
            outcome.put("source", prediction.source());
            outcome.put("direction", prediction.direction());
            outcome.put("impact_tier", prediction.impactTier());
            outcome.put("btc_at_prediction", prediction.btcBase());
            outcome.put("outcome_1h_pct", pct1h);
            outcome.put("direction_correct", directionCorrect(prediction.direction(), pct1h));
            putNullable(outcome, "outcome_24h_pct", realizedPct(prediction, H24, now, prices));
        }
        return outcome;
    }

    /** Score every article whose 1h horizon has matured (validating its scenario); others are skipped. */
    public static List<ObjectNode> scoreArticles(List<ArticlePrediction> articles, Instant now, PriceSource prices)
    {
        List<ObjectNode> outcomes = new ArrayList<>();
        for (ArticlePrediction article : articles)
        {
            ObjectNode outcome = scoreArticle(article, now, prices);
            if (outcome != null)
            {
                outcomes.add(outcome);
            }
        }
        return outcomes;
    }

    private static ObjectNode scoreArticle(ArticlePrediction article, Instant now, PriceSource prices)
    {
        ObjectNode outcome = null;
        Double actual1h = realizedPctAt(article.publishedAt(), article.priceAtPublish(), H1, now, prices);
        if (actual1h != null)
        {
            outcome = Json.newObject();
            outcome.put("article_id", article.articleId());
            outcome.put("day", article.day());
            outcome.put("scenario", article.scenario());
            outcome.put("pre_trend", article.preTrend());
            outcome.put("price_at_publish", article.priceAtPublish());
            outcome.put("actual_1h_pct", actual1h);
            Boolean validated = scenarioValidated(article.scenario(), article.preTrend(), actual1h);
            if (validated == null)
            {
                outcome.putNull("scenario_validated");
            }
            else
            {
                outcome.put("scenario_validated", validated.booleanValue());
            }
        }
        return outcome;
    }

    /** Whether the realized 1h move bore out the scenario label (Python's per-article scenario check). */
    static Boolean scenarioValidated(String scenario, String preTrend, double actual1h)
    {
        Boolean validated;
        switch (scenario == null ? "" : scenario)
        {
            case "front_run":
                validated = Math.abs(actual1h) < FRONT_RUN_ABSORBED_PCT;
                break;
            case "fresh_bullish":
                validated = actual1h > 0;
                break;
            case "fresh_bearish":
                validated = actual1h < 0;
                break;
            case "noise":
                validated = Boolean.TRUE;
                break;
            case "reversal":
                validated = reversalValidated(preTrend, actual1h);
                break;
            default:
                validated = null;
        }
        return validated;
    }

    /** A reversal is validated when price turns against the pre-trend (rising&rarr;down, falling&rarr;up). */
    private static Boolean reversalValidated(String preTrend, double actual1h)
    {
        Boolean validated = null;
        if ("rising".equals(preTrend))
        {
            validated = actual1h < 0;
        }
        else if ("falling".equals(preTrend))
        {
            validated = actual1h > 0;
        }
        return validated;
    }

    /** Realized percent move at {@code horizon}, or null when not matured / price unavailable / base 0. */
    private static Double realizedPct(Prediction prediction, Duration horizon, Instant now, PriceSource prices)
    {
        return realizedPctAt(prediction.windowTime(), prediction.btcBase(), horizon, now, prices);
    }

    /** Realized percent move from {@code base} at {@code from + horizon}, or null when not matured / no price. */
    private static Double realizedPctAt(Instant from, double base, Duration horizon, Instant now, PriceSource prices)
    {
        Double pct = null;
        Instant target = from.plus(horizon);
        if (!now.isBefore(target) && base != 0.0)
        {
            Double price = prices.priceAt(target);
            if (price != null)
            {
                pct = Num.round((price - base) / base * 100.0, 4);
            }
        }
        return pct;
    }

    /** Whether the call's direction matched the realized 1h move (Python {@code claude_direction_correct}). */
    static boolean directionCorrect(String direction, double pct1h)
    {
        boolean correct;
        switch (direction == null ? "" : direction)
        {
            case "bullish":
                correct = pct1h > 0;
                break;
            case "bearish":
                correct = pct1h < 0;
                break;
            case "neutral":
                correct = Math.abs(pct1h) < NEUTRAL_BAND_PCT;
                break;
            default:
                correct = false;
        }
        return correct;
    }

    private static void putNullable(ObjectNode node, String field, Double value)
    {
        if (value == null)
        {
            node.putNull(field);
        }
        else
        {
            node.put(field, value.doubleValue());
        }
    }
}
