package com.finsent.collect;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * Fetches the <b>actual</b> value for a scheduled release from the BLS public API v2 (#21) and
 * derives it per {@code kind}. The series returns the latest data points newest-first; the derivation:
 * {@code level} = the latest value as-is (e.g. unemployment rate); {@code mom_change} = latest &minus;
 * prev in the series' own units (e.g. payroll level in thousands &rarr; the monthly change in K);
 * {@code mom_pct} = the percent change of an index series ({@code (latest-prev)/prev*100}, e.g. the CPI
 * index &rarr; the MoM %). Fetch-only &mdash; the surprise/direction live in {@code EconEventSignals}.
 */
public final class EconActuals
{
    private static final String NAME = "EconActuals";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl_;
    private final String apiKey_;

    public EconActuals(String blsBaseUrl, String blsApiKey)
    {
        baseUrl_ = blsBaseUrl;
        apiKey_ = blsApiKey;
    }

    /** One reading of a series: the derived {@code actual} and the latest data point's {@code period} as {@code YYYYMM}. */
    public record Reading(double actual, int period)
    {
    }

    /**
     * The latest {@link Reading} for a BLS {@code series} + {@code kind} (one HTTP call), or null on
     * failure / insufficient data. The caller compares {@link Reading#period()} against
     * {@link #reportedPeriod} to tell a fresh print from a stale (prior-month) one still on the wire.
     */
    public Reading fetch(String series, String kind)
    {
        Reading reading = null;
        try
        {
            Map<String, String> params = apiKey_.isBlank() ? Map.of() : Map.of("registrationkey", apiKey_);
            JsonNode response = Json.parse(Http.get(baseUrl_ + "/" + series, params, null, TIMEOUT));
            Double actual = actualFrom(response, kind);
            int period = latestPeriod(response);
            if (actual != null && period > 0)
            {
                reading = new Reading(actual, period);
            }
        }
        catch (IOException | RuntimeException fetchFailed)
        {
            GlobalSystem.warning().writes(NAME, "Failed to fetch BLS actual for " + series, fetchFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching BLS actual for " + series, interrupted);
        }
        return reading;
    }

    /** Derive the actual from a BLS v2 timeseries response per {@code kind}; null when data is missing. */
    static Double actualFrom(JsonNode response, String kind)
    {
        Double actual = null;
        JsonNode data = response.path("Results").path("series").path(0).path("data");
        if (data.isArray() && data.size() > 0)
        {
            double latest = value(data, 0);
            if ("level".equals(kind))
            {
                actual = latest;
            }
            else if (data.size() > 1)
            {
                double prev = value(data, 1);
                if ("mom_change".equals(kind))
                {
                    actual = latest - prev;
                }
                else if ("mom_pct".equals(kind) && prev != 0.0)
                {
                    actual = (latest - prev) / prev * 100.0;
                }
            }
        }
        return actual;
    }

    /** The latest data point's period as {@code year*100 + month} (e.g. 202605), or 0 when absent/non-monthly. */
    static int latestPeriod(JsonNode response)
    {
        JsonNode data = response.path("Results").path("series").path(0).path("data");
        int yyyymm = 0;
        if (data.isArray() && data.size() > 0)
        {
            int year = data.path(0).path("year").asInt(0);
            int month = monthOf(data.path(0).path("period").asText(""));
            if (year > 0 && month > 0)
            {
                yyyymm = year * 100 + month;
            }
        }
        return yyyymm;
    }

    /**
     * The reported period a release covers, as {@code YYYYMM}: the prior calendar month. Monthly
     * CPI/NFP/unemployment for month M publish in M+1, so a release dated in month C reports C&minus;1;
     * used to distinguish the awaited fresh print from the prior month's value still on the wire.
     */
    static int reportedPeriod(Instant release)
    {
        LocalDate reported = release.atZone(ZoneOffset.UTC).toLocalDate().minusMonths(1);
        return reported.getYear() * 100 + reported.getMonthValue();
    }

    /** BLS monthly period {@code "M01".."M12"} &rarr; 1..12; 0 for quarterly/annual/unparseable. */
    private static int monthOf(String period)
    {
        int month = 0;
        if (period.length() == 3 && period.charAt(0) == 'M')
        {
            try
            {
                month = Integer.parseInt(period.substring(1));
            }
            catch (NumberFormatException notMonth)
            {
                month = 0;
            }
        }
        return month >= 1 && month <= 12 ? month : 0;
    }

    private static double value(JsonNode data, int index)
    {
        return Double.parseDouble(data.path(index).path("value").asText("0"));
    }
}
