package com.finsent.analyse.signal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;

/**
 * Mechanical perpetual-positioning signal (BL#2a + open interest). Pure: fuses the funding rate, the
 * ~1h open-interest change, and the concurrent ~1h price move into a crowding + cascade/squeeze read.
 *
 * <p>Funding gives the crowded side (positive = longs pay shorts, crowded long; negative = crowded
 * short). Open interest gives whether leverage is <b>building</b> or <b>unwinding</b>, and the OI x
 * price matrix says <i>who</i> is positioning: OI building into rising price = new longs (crowded,
 * vulnerable to a bearish trigger -&gt; {@code down_cascade_fuel}); OI building into falling price =
 * new shorts ({@code up_squeeze_fuel}); OI unwinding = positions closing, the move is
 * {@code exhausting}. That setup tells the analyst how <b>violently</b> a catalyst will land, not its
 * direction. Mirrors {@link OptionsSignals}: static, {@code ObjectNode} in/out.
 */
public final class FundingSignals
{
    /** 8-hourly funding-rate bands (decimal): 0.0001 = 0.01%, 0.0005 = 0.05%. */
    private static final double CROWDED = 0.0001;
    private static final double EXTREME = 0.0005;
    /** OI change over the ~1h lookback that counts as leverage building / unwinding (percent). */
    private static final double OI_TREND_PCT = 1.0;
    /** Price move over the ~1h lookback that counts as a directional move for the OI x price matrix (percent). */
    private static final double PRICE_DIR_PCT = 0.3;

    private FundingSignals()
    {
    }

    /** Funding-only positioning (no OI/price fusion) -- the back-compatible single-snapshot form. */
    public static ObjectNode signal(ObjectNode snapshot)
    {
        return signal(snapshot, null, null);
    }

    /**
     * Positioning signal for the window: the funding crowding label always, plus the OI trend and the
     * fused cascade/squeeze {@code setup} when a prior snapshot's open interest is available.
     * {@code prior} is the funding snapshot ~1h back; {@code priceChangePct} the ~1h BTC move (either
     * may be null, e.g. early after startup). Returns {@code null} when no funding rate is present.
     */
    public static ObjectNode signal(ObjectNode current, ObjectNode prior, Double priceChangePct)
    {
        ObjectNode signal = null;
        if (current != null && current.path("funding_rate").isNumber())
        {
            double rate = current.path("funding_rate").asDouble();
            signal = Json.newObject();
            signal.put("positioning", positioning(rate));
            signal.put("funding_rate_pct", Num.round(rate * 100.0, 4));
            addOiFusion(signal, current, prior, priceChangePct);
        }
        return signal;
    }

    /** Add {@code oi_change_pct} / {@code oi_trend} / {@code setup} when both snapshots carry open interest. */
    private static void addOiFusion(ObjectNode signal, ObjectNode current, ObjectNode prior, Double priceChangePct)
    {
        double priorOi = prior == null ? 0.0 : prior.path("open_interest").asDouble(0.0);
        if (current.path("open_interest").isNumber() && priorOi > 0.0)
        {
            double oiPct = (current.path("open_interest").asDouble() - priorOi) / priorOi * 100.0;
            String trend = oiTrend(oiPct);
            signal.put("oi_change_pct", Num.round(oiPct, 3));
            signal.put("oi_trend", trend);
            signal.put("setup", setup(trend, priceChangePct));
        }
    }

    private static String positioning(double rate)
    {
        String label = "neutral";
        if (rate >= EXTREME)
        {
            label = "extreme_long";
        }
        else if (rate >= CROWDED)
        {
            label = "crowded_long";
        }
        else if (rate <= -EXTREME)
        {
            label = "extreme_short";
        }
        else if (rate <= -CROWDED)
        {
            label = "crowded_short";
        }
        return label;
    }

    private static String oiTrend(double oiPct)
    {
        String trend = "flat";
        if (oiPct >= OI_TREND_PCT)
        {
            trend = "building";
        }
        else if (oiPct <= -OI_TREND_PCT)
        {
            trend = "unwinding";
        }
        return trend;
    }

    /**
     * Fuse the OI trend with the concurrent price move into the cascade/squeeze setup. Building
     * leverage into a directional move is fresh crowding (cascade fuel against it); unwinding leverage
     * is positions closing (the move is exhausting); otherwise neutral.
     */
    private static String setup(String oiTrend, Double priceChangePct)
    {
        String setup = "neutral";
        if (oiTrend.equals("unwinding"))
        {
            setup = "exhausting";
        }
        else if (oiTrend.equals("building"))
        {
            setup = buildingSetup(priceChangePct);
        }
        return setup;
    }

    private static String buildingSetup(Double priceChangePct)
    {
        String setup = "building"; // OI building but price direction unknown (no price lookback yet)
        if (priceChangePct != null && priceChangePct > PRICE_DIR_PCT)
        {
            setup = "down_cascade_fuel"; // new longs into a rising price -> crowded longs vulnerable
        }
        else if (priceChangePct != null && priceChangePct < -PRICE_DIR_PCT)
        {
            setup = "up_squeeze_fuel"; // new shorts into a falling price -> crowded shorts vulnerable
        }
        return setup;
    }
}
