package com.finsent.collect;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Http;
import com.finsent.core.Json;
import com.finsent.core.Num;
import com.finsent.core.Times;
import com.finsent.util.GlobalSystem;

/**
 * Deribit BTC options market-data fetcher (ports Python {@code options.py}). Reads public
 * (no-auth) Deribit endpoints and builds the per-interval snapshot stored by the collector:
 * aggregate and near-term put/call open interest, 24h volume, near-the-money implied volatility
 * and the DVOL index. This module only fetches and computes the snapshot &mdash; the mechanical
 * priced-in signal over a current/previous snapshot pair lives in
 * {@code com.finsent.analyse.signal.OptionsSignals}.
 */
public final class OptionsFetcher
{
    private static final String NAME = "OptionsFetcher";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** Near-term expiries (&le; this many days out) are most sensitive to imminent news. */
    private static final int NEAR_TERM_MAX_DAYS = 14;
    /** "Near the money": underlying within this fraction of the strike. */
    private static final double ATM_DISTANCE_PCT = 0.05;
    private static final int DVOL_MINUTES_BACK = 60;
    private static final long DAY_SECONDS = 86_400L;

    /** Deribit expiry token, e.g. {@code 28JUN25}. Case-insensitive month, 1-2 digit day. */
    private static final DateTimeFormatter EXPIRY_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dMMMyy").toFormatter(Locale.ENGLISH);

    private final String baseUrl_;

    public OptionsFetcher(String deribitBaseUrl)
    {
        baseUrl_ = deribitBaseUrl;
    }

    /**
     * Fetch the current options snapshot, or null on failure (no instruments / book-summary
     * error). DVOL is best-effort: its absence leaves the {@code dvol*} fields null but still
     * yields a snapshot. Ports {@code options.fetch_options_snapshot}.
     */
    public ObjectNode fetchSnapshot()
    {
        ObjectNode snapshot = null;
        try
        {
            List<JsonNode> instruments = fetchBookSummary();
            if (instruments.isEmpty())
            {
                GlobalSystem.warning().writes(NAME, "No instruments returned from Deribit");
            }
            else
            {
                snapshot = buildSnapshot(Instant.now(), instruments, fetchDvolQuietly());
            }
        }
        catch (IOException | RuntimeException bookFailed)
        {
            GlobalSystem.warning().writes(NAME, "Failed to fetch book summary", bookFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.warning().writes(NAME, "Interrupted fetching book summary", interrupted);
        }
        return snapshot;
    }

    private List<JsonNode> fetchBookSummary() throws IOException, InterruptedException, JsonProcessingException
    {
        String body = Http.get(baseUrl_ + "/public/get_book_summary_by_currency",
                Map.of("currency", "BTC", "kind", "option"), null, TIMEOUT);
        return elements(Json.parse(body).path("result"));
    }

    private List<JsonNode> fetchDvolQuietly()
    {
        List<JsonNode> candles = new ArrayList<>();
        try
        {
            long nowMs = System.currentTimeMillis();
            long startMs = nowMs - DVOL_MINUTES_BACK * 60L * 1000L;
            String body = Http.get(baseUrl_ + "/public/get_volatility_index_data",
                    Map.of("currency", "BTC", "start_timestamp", String.valueOf(startMs),
                            "end_timestamp", String.valueOf(nowMs), "resolution", "60"),
                    null, TIMEOUT);
            candles = elements(Json.parse(body).path("result").path("data"));
        }
        catch (IOException | RuntimeException dvolFailed)
        {
            GlobalSystem.debug().writes(NAME, "Failed to fetch DVOL", dvolFailed);
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            GlobalSystem.debug().writes(NAME, "Interrupted fetching DVOL", interrupted);
        }
        return candles;
    }

    /**
     * Build a structured snapshot from raw Deribit book-summary instruments and DVOL candles.
     * {@code now} is the reference instant for the snapshot timestamp and near-term expiry test
     * (injected so the computation is deterministic). Ports {@code options._build_snapshot}.
     */
    static ObjectNode buildSnapshot(Instant now, List<JsonNode> instruments, List<JsonNode> dvolCandles)
    {
        List<JsonNode> puts = new ArrayList<>();
        List<JsonNode> calls = new ArrayList<>();
        for (JsonNode instrument : instruments)
        {
            String instrumentName = instrument.path("instrument_name").asText("");
            if (instrumentName.endsWith("-P"))
            {
                puts.add(instrument);
            }
            else if (instrumentName.endsWith("-C"))
            {
                calls.add(instrument);
            }
        }

        double putOi = sum(puts, "open_interest");
        double callOi = sum(calls, "open_interest");
        double putVol = sum(puts, "volume");
        double callVol = sum(calls, "volume");

        List<JsonNode> nearPuts = nearTerm(puts, now);
        List<JsonNode> nearCalls = nearTerm(calls, now);
        double nearPutOi = sum(nearPuts, "open_interest");
        double nearCallOi = sum(nearCalls, "open_interest");

        ObjectNode snapshot = Json.newObject();
        snapshot.put("ts", Times.formatUtcIso(now));
        snapshot.put("put_oi", Num.round(putOi, 2));
        snapshot.put("call_oi", Num.round(callOi, 2));
        snapshot.put("total_oi", Num.round(putOi + callOi, 2));
        putNullable(snapshot, "pc_ratio", callOi > 0 ? Num.round(putOi / callOi, 4) : null);
        snapshot.put("put_vol_24h", Num.round(putVol, 2));
        snapshot.put("call_vol_24h", Num.round(callVol, 2));
        snapshot.put("total_vol_24h", Num.round(putVol + callVol, 2));
        snapshot.put("near_put_oi", Num.round(nearPutOi, 2));
        snapshot.put("near_call_oi", Num.round(nearCallOi, 2));
        putNullable(snapshot, "near_pc_ratio", nearCallOi > 0 ? Num.round(nearPutOi / nearCallOi, 4) : null);
        putNullable(snapshot, "near_atm_iv", nearAtmIv(nearPuts, nearCalls));
        putDvol(snapshot, dvolCandles);
        snapshot.put("instrument_count", instruments.size());
        return snapshot;
    }

    /** Average mark IV of near-the-money near-term instruments, or null when none qualify. */
    private static Double nearAtmIv(List<JsonNode> nearPuts, List<JsonNode> nearCalls)
    {
        double sum = 0.0;
        int count = 0;
        List<JsonNode> nearTerm = new ArrayList<>(nearPuts);
        nearTerm.addAll(nearCalls);
        for (JsonNode instrument : nearTerm)
        {
            Double strike = extractStrike(instrument.path("instrument_name").asText(""));
            double underlying = instrument.path("underlying_price").asDouble(0.0);
            Double markIv = optDouble(instrument, "mark_iv");
            if (strike != null && strike != 0.0 && underlying > 0.0 && markIv != null && markIv != 0.0
                    && Math.abs(strike - underlying) / underlying <= ATM_DISTANCE_PCT)
            {
                sum += markIv;
                count++;
            }
        }
        return count > 0 ? Num.round(sum / count, 2) : null;
    }

    /** DVOL is already a percentage; the candle close is index 4 of each {@code [ts,o,h,l,c]} row. */
    private static void putDvol(ObjectNode snapshot, List<JsonNode> dvolCandles)
    {
        Double current = null;
        Double oneHourAgo = null;
        int size = dvolCandles.size();
        if (size > 0)
        {
            current = Num.round(dvolCandles.get(size - 1).path(4).asDouble(), 2);
            if (size >= 60)
            {
                oneHourAgo = Num.round(dvolCandles.get(size - 60).path(4).asDouble(), 2);
            }
            else if (size >= 2)
            {
                oneHourAgo = Num.round(dvolCandles.get(0).path(4).asDouble(), 2);
            }
        }
        putNullable(snapshot, "dvol", current);
        putNullable(snapshot, "dvol_1h_ago", oneHourAgo);
    }

    private static List<JsonNode> nearTerm(List<JsonNode> instruments, Instant now)
    {
        List<JsonNode> near = new ArrayList<>();
        for (JsonNode instrument : instruments)
        {
            if (isNearTerm(parseExpiry(instrument.path("instrument_name").asText("")), now))
            {
                near.add(instrument);
            }
        }
        return near;
    }

    /** Expiry token from an instrument name like {@code BTC-28JUN25-100000-C}, or "" when malformed. */
    static String parseExpiry(String instrumentName)
    {
        String[] parts = instrumentName.split("-");
        return parts.length >= 4 ? parts[1] : "";
    }

    /** Strike from an instrument name like {@code BTC-28JUN25-100000-C}, or null when not numeric. */
    static Double extractStrike(String instrumentName)
    {
        String[] parts = instrumentName.split("-");
        Double strike = null;
        if (parts.length >= 4)
        {
            try
            {
                strike = Double.parseDouble(parts[2]);
            }
            catch (NumberFormatException notNumeric)
            {
                strike = null;
            }
        }
        return strike;
    }

    /** True when {@code expiry} (Deribit token) is between now and {@value #NEAR_TERM_MAX_DAYS} days out. */
    static boolean isNearTerm(String expiry, Instant now)
    {
        boolean near = false;
        try
        {
            Instant expiryInstant = LocalDate.parse(expiry, EXPIRY_FORMAT)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            long days = Math.floorDiv(expiryInstant.getEpochSecond() - now.getEpochSecond(), DAY_SECONDS);
            near = days >= 0 && days <= NEAR_TERM_MAX_DAYS;
        }
        catch (DateTimeParseException badExpiry)
        {
            near = false;
        }
        return near;
    }

    private static double sum(List<JsonNode> instruments, String field)
    {
        double total = 0.0;
        for (JsonNode instrument : instruments)
        {
            total += instrument.path(field).asDouble(0.0);
        }
        return total;
    }

    private static List<JsonNode> elements(JsonNode array)
    {
        List<JsonNode> result = new ArrayList<>();
        if (array.isArray())
        {
            array.forEach(result::add);
        }
        return result;
    }

    private static Double optDouble(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
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
