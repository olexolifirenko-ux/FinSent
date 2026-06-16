package com.finsent.collect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;

import org.junit.Test;

import com.finsent.core.Json;

/**
 * Verifies {@link EconActuals#actualFrom}: the three derivations off a BLS v2 timeseries response
 * (level / mom_change / mom_pct) and a null actual when the series has no data.
 */
public class EconActuals_utest
{
    /** A BLS v2 response carrying the given string values, newest-first. */
    private static String bls(String... values)
    {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < values.length; i++)
        {
            data.append(i == 0 ? "" : ",").append("{\"value\":\"").append(values[i]).append("\"}");
        }
        return "{\"status\":\"REQUEST_SUCCEEDED\",\"Results\":{\"series\":[{\"data\":[" + data + "]}]}}";
    }

    @Test
    public void levelTakesTheLatestValue() throws Exception
    {
        assertEquals(4.1, EconActuals.actualFrom(Json.parse(bls("4.1", "4.0")), "level"), 1e-9);
    }

    @Test
    public void momChangeIsLatestMinusPrevInSeriesUnits() throws Exception
    {
        // Payroll level in thousands: 159200 - 159000 = +200 (K)
        assertEquals(200.0, EconActuals.actualFrom(Json.parse(bls("159200", "159000")), "mom_change"), 1e-9);
    }

    @Test
    public void momPctIsTheIndexPercentChange() throws Exception
    {
        // CPI index: (333.020 - 330.213) / 330.213 * 100 = 0.85%
        assertEquals(0.85, EconActuals.actualFrom(Json.parse(bls("333.020", "330.213")), "mom_pct"), 0.01);
    }

    @Test
    public void nullWhenNoData() throws Exception
    {
        assertNull(EconActuals.actualFrom(Json.parse("{\"Results\":{\"series\":[{\"data\":[]}]}}"), "level"));
    }

    @Test
    public void latestPeriodReadsLatestYearAndMonth() throws Exception
    {
        assertEquals(202605, EconActuals.latestPeriod(Json.parse(blsPeriod(2026, "M05", "335.1"))));
    }

    @Test
    public void latestPeriodZeroWhenNonMonthlyOrEmpty() throws Exception
    {
        assertEquals(0, EconActuals.latestPeriod(Json.parse(blsPeriod(2026, "Q02", "1.0")))); // quarterly, not monthly
        assertEquals(0, EconActuals.latestPeriod(Json.parse("{\"Results\":{\"series\":[{\"data\":[]}]}}")));
    }

    @Test
    public void reportedPeriodIsPriorCalendarMonth()
    {
        assertEquals(202605, EconActuals.reportedPeriod(Instant.parse("2026-06-11T12:30:00Z")));
        assertEquals(202512, EconActuals.reportedPeriod(Instant.parse("2026-01-09T13:30:00Z"))); // Jan release -> prior Dec
    }

    /** A BLS v2 response carrying one data point with its year/period, newest-first. */
    private static String blsPeriod(int year, String period, String value)
    {
        return "{\"status\":\"REQUEST_SUCCEEDED\",\"Results\":{\"series\":[{\"data\":[{\"year\":" + year
                + ",\"period\":\"" + period + "\",\"value\":\"" + value + "\"}]}]}}";
    }
}
