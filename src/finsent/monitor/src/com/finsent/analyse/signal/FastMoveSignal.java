package com.finsent.analyse.signal;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;

import com.finsent.core.FastMoveWindow;
import com.finsent.core.Json;
import com.finsent.core.Num;

/**
 * The deterministic FastMove decision: given a strip of recent 1-minute closes and a set of
 * {@link FastMoveWindow}s, decide whether BTC's own tape has moved far and cleanly enough to fire a
 * directional momentum signal -- LLM-free, news-independent. For each window it slices the last
 * {@code spanMinutes} closes and asks two things via {@link PreTrend}: did it move enough (endpoint
 * magnitude {@code >= thresholdPct}) AND did it move cleanly (regression {@code r_squared >= r2Floor},
 * which rejects choppy drift that merely crossed the threshold). The strongest qualifying window
 * (largest |magnitude|) wins; direction is the sign of the move. Pure and side-effect-free so the
 * poller and the backtest share the exact same logic.
 */
public final class FastMoveSignal
{
    private static final int MIN_BARS = 3;

    private FastMoveSignal()
    {
    }

    /**
     * Evaluate every window against the close strip (chronological, oldest first, newest last; each bar
     * an object carrying a {@code c} close). Returns the strongest qualifying fire, or {@link Fire#NONE}
     * when no window both moved enough and moved cleanly.
     */
    public static Fire evaluate(ArrayNode bars, List<FastMoveWindow> windows)
    {
        Fire best = Fire.NONE;
        for (FastMoveWindow window : windows)
        {
            Fire candidate = evaluateWindow(bars, window);
            if (candidate.fired() && Math.abs(candidate.magnitudePct()) > Math.abs(best.magnitudePct()))
            {
                best = candidate;
            }
        }
        if (best.fired())
        {
            // Stamp the acceleration: the SHORTEST window's pace vs the LONGEST window's pace. >1 means the
            // recent tape is moving faster than the move's overall average -- a building cascade/squeeze
            // (forced), not a fade. ~1 is a steady move; <1 is decelerating.
            best = new Fire(true, best.direction(), best.spanMinutes(), best.magnitudePct(), best.r2(),
                    Num.round(velocityRatio(bars, windows), 2));
        }
        return best;
    }

    /** Acceleration ratio: the shortest window's %/min pace divided by the longest window's, or 1.0 when undefined. */
    private static double velocityRatio(ArrayNode bars, List<FastMoveWindow> windows)
    {
        FastMoveWindow shortest = windows.get(0);
        FastMoveWindow longest = windows.get(0);
        for (FastMoveWindow window : windows)
        {
            if (window.spanMinutes() < shortest.spanMinutes())
            {
                shortest = window;
            }
            if (window.spanMinutes() > longest.spanMinutes())
            {
                longest = window;
            }
        }
        double longPace = pacePerMinute(bars, longest.spanMinutes());
        return longPace > 1.0e-9 ? pacePerMinute(bars, shortest.spanMinutes()) / longPace : 1.0;
    }

    /** Absolute endpoint percent move over the last {@code spanMinutes} bars, divided by the span (%/min). */
    private static double pacePerMinute(ArrayNode bars, int spanMinutes)
    {
        ArrayNode slice = lastBars(bars, spanMinutes);
        double pace = 0.0;
        if (slice.size() >= MIN_BARS)
        {
            double first = slice.get(0).path("c").asDouble();
            double last = slice.get(slice.size() - 1).path("c").asDouble();
            if (first > 0.0)
            {
                pace = Math.abs((last - first) / first * 100.0) / spanMinutes;
            }
        }
        return pace;
    }

    private static Fire evaluateWindow(ArrayNode bars, FastMoveWindow window)
    {
        Fire fire = Fire.NONE;
        ArrayNode slice = lastBars(bars, window.spanMinutes());
        if (slice.size() >= MIN_BARS)
        {
            double first = slice.get(0).path("c").asDouble();
            double last = slice.get(slice.size() - 1).path("c").asDouble();
            if (first > 0.0)
            {
                double magnitudePct = (last - first) / first * 100.0;
                double r2 = PreTrend.of(slice).path("r_squared").asDouble();
                String direction = direction(magnitudePct, window.thresholdPct());
                if (direction != null && r2 >= window.r2Floor())
                {
                    // velocityRatio is stamped on the winning fire in evaluate(); 0.0 here is a placeholder.
                    fire = new Fire(true, direction, window.spanMinutes(), Num.round(magnitudePct, 4),
                            Num.round(r2, 4), 0.0);
                }
            }
        }
        return fire;
    }

    /** Bearish on a drop past the threshold, bullish on a rise past it, null when the move is too small. */
    private static String direction(double magnitudePct, double thresholdPct)
    {
        String direction = null;
        if (magnitudePct <= -thresholdPct)
        {
            direction = "bearish";
        }
        else if (magnitudePct >= thresholdPct)
        {
            direction = "bullish";
        }
        return direction;
    }

    /** The last {@code spanMinutes} bars of the strip (the whole strip when it holds fewer). */
    private static ArrayNode lastBars(ArrayNode bars, int spanMinutes)
    {
        ArrayNode slice = Json.newArray();
        int from = Math.max(0, bars.size() - spanMinutes);
        for (int i = from; i < bars.size(); i++)
        {
            slice.add(bars.get(i));
        }
        return slice;
    }

    /**
     * The detector outcome: whether a window fired, the {@code direction} (bullish/bearish), the
     * {@code spanMinutes} of the winning window, the endpoint {@code magnitudePct}, the fit {@code r2},
     * and the {@code velocityRatio} (shortest-window pace / longest-window pace; &gt;1 = accelerating).
     * {@link #NONE} is the no-fire sentinel (magnitude 0, so it never beats a real fire).
     */
    public record Fire(boolean fired, String direction, int spanMinutes, double magnitudePct, double r2,
                       double velocityRatio)
    {
        public static final Fire NONE = new Fire(false, null, 0, 0.0, 0.0, 0.0);
    }
}
