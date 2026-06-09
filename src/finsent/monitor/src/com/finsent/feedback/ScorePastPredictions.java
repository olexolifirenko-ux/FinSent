package com.finsent.feedback;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.app.AbstractAppInitializer;
import com.finsent.core.Config;
import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.directory.DirectorySystem;
import com.finsent.util.GlobalSystem;

/**
 * Standalone batch entry point for the BL#6 feedback loop &mdash; the class the Perl {@code score.pl}
 * / {@code feedback_report.pl} wrappers launch. It runs the framework bootstrap (config + directory +
 * log facility, via {@link AbstractAppInitializer}) <em>without</em> the collection/analysis pipeline,
 * scores the stored predictions against realized BTC moves, writes {@code outcomes.jsonl} /
 * {@code article_outcomes.jsonl}, and prints the accuracy report to stdout. An optional trailing
 * {@code --days N} bounds the scan; the live {@code anal feedback} command is the in-process equivalent.
 */
public final class ScorePastPredictions extends AbstractAppInitializer
{
    private static final String NAME = "ScorePastPredictions";

    public ScorePastPredictions(String[] args) throws Exception
    {
        super(args);
    }

    /** Permit the trailing {@code --days N} past the framework's {@code -type/-name/-bootstrapDataFile}. */
    @Override
    protected boolean isExtraCmdArgsAllowed()
    {
        return true;
    }

    public static void main(String[] args) throws Exception
    {
        new ScorePastPredictions(args); // framework bootstrap only -- no collector/analyser pipeline
        Config config = Config.fromGlobalSystem();
        File dataDir = DirectorySystem.resolveToFile(config.dataDir());
        String klinesUrl = config.binanceBaseUrl();
        String report = FeedbackRunner.run(dataDir, Instant.now(), target -> priceAt(klinesUrl, target), days(args));
        System.out.println(report);
        GlobalSystem.terminate(0);
    }

    /** Realized BTC close at {@code target} (one 1-minute Binance kline), or null when unavailable. */
    private static Double priceAt(String klinesUrl, Instant target)
    {
        Double price = null;
        try
        {
            long startMs = target.toEpochMilli();
            String body = Http.get(klinesUrl, Map.of("symbol", "BTCUSDT", "interval", "1m",
                    "startTime", String.valueOf(startMs), "endTime", String.valueOf(startMs + 60_000L), "limit", "1"),
                    null, Duration.ofSeconds(10));
            JsonNode candles = Json.parse(body);
            if (candles.size() > 0)
            {
                price = candles.get(0).get(4).asDouble();
            }
        }
        catch (IOException | RuntimeException unavailable)
        {
            GlobalSystem.debug().writes(NAME, "Price fetch failed for " + target, unavailable);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
        return price;
    }

    private static int days(String[] args)
    {
        int days = 0;
        for (int i = 0; i + 1 < args.length; i++)
        {
            if (args[i].equals("--days") || args[i].equals("-days"))
            {
                days = parseIntOrZero(args[i + 1]);
            }
        }
        return days;
    }

    private static int parseIntOrZero(String value)
    {
        int parsed = 0;
        try
        {
            parsed = Integer.parseInt(value);
        }
        catch (NumberFormatException notANumber)
        {
            parsed = 0; // malformed --days -> all history
        }
        return parsed;
    }
}
