package com.finsent.analyse.signal;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;

/**
 * Pre-publication BTC price trend from a strip of OHLC bars, via simple linear regression on the
 * closes (ports Python {@code analyse._compute_pre_trend}). The {@code label} (the only part fed to
 * Claude's prompt) is {@code rising}/{@code falling}/{@code volatile}/{@code flat}, or {@code null}
 * with too few bars. Returns {@code {label, slope_pct, r_squared, range_pct}}.
 */
public final class PreTrend
{
    private static final int MIN_BARS = 3;
    private static final double SLOPE_THRESHOLD_PCT = 0.3;
    private static final double R2_THRESHOLD = 0.5;
    private static final double RANGE_THRESHOLD_PCT = 1.0;

    private PreTrend()
    {
    }

    public static ObjectNode of(ArrayNode bars)
    {
        String label = null;
        double slopePct = 0.0;
        double rSquared = 0.0;
        double rangePct = 0.0;

        int n = bars == null ? 0 : bars.size();
        if (n >= MIN_BARS)
        {
            double[] closes = new double[n];
            for (int i = 0; i < n; i++)
            {
                closes[i] = bars.get(i).path("c").asDouble();
            }
            double base = closes[0];
            if (base == 0.0)
            {
                label = "flat";
            }
            else
            {
                double xMean = (n - 1) / 2.0;
                double yMean = mean(closes);
                double ssxy = 0.0;
                double ssxx = 0.0;
                double ssyy = 0.0;
                for (int x = 0; x < n; x++)
                {
                    double dx = x - xMean;
                    double dy = closes[x] - yMean;
                    ssxy += dx * dy;
                    ssxx += dx * dx;
                    ssyy += dy * dy;
                }
                if (ssxx == 0.0)
                {
                    label = "flat";
                }
                else
                {
                    double totalSlopePct = (ssxy / ssxx) * (n - 1) / base * 100.0;
                    double r2 = ssyy > 0.0 ? (ssxy * ssxy) / (ssxx * ssyy) : 0.0;
                    double range = (max(closes) - min(closes)) / base * 100.0;
                    label = classify(r2, totalSlopePct, range);
                    slopePct = Num.round(totalSlopePct, 4);
                    rSquared = Num.round(r2, 4);
                    rangePct = Num.round(range, 4);
                }
            }
        }

        ObjectNode result = Json.newObject();
        if (label == null)
        {
            result.putNull("label");
        }
        else
        {
            result.put("label", label);
        }
        result.put("slope_pct", slopePct);
        result.put("r_squared", rSquared);
        result.put("range_pct", rangePct);
        return result;
    }

    private static String classify(double r2, double slopePct, double rangePct)
    {
        String label;
        if (r2 > R2_THRESHOLD && slopePct > SLOPE_THRESHOLD_PCT)
        {
            label = "rising";
        }
        else if (r2 > R2_THRESHOLD && slopePct < -SLOPE_THRESHOLD_PCT)
        {
            label = "falling";
        }
        else if (r2 <= R2_THRESHOLD && rangePct > RANGE_THRESHOLD_PCT)
        {
            label = "volatile";
        }
        else
        {
            label = "flat";
        }
        return label;
    }

    private static double mean(double[] values)
    {
        double sum = 0.0;
        for (double v : values)
        {
            sum += v;
        }
        return sum / values.length;
    }

    private static double max(double[] values)
    {
        double m = values[0];
        for (double v : values)
        {
            m = Math.max(m, v);
        }
        return m;
    }

    private static double min(double[] values)
    {
        double m = values[0];
        for (double v : values)
        {
            m = Math.min(m, v);
        }
        return m;
    }
}
