package com.finsent.analyse.signal;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Num;

/**
 * Mechanical perpetual-funding positioning signal (BL#2a). Pure: maps a funding-rate snapshot to a
 * crowding label. Positive funding = longs pay shorts (crowded long &mdash; bearish catalysts can
 * cascade via long liquidations); negative = shorts pay longs (crowded short &mdash; bullish
 * catalysts can squeeze). Mirrors {@link OptionsSignals}: static, {@code ObjectNode} in/out.
 */
public final class FundingSignals
{
    /** 8-hourly funding-rate bands (decimal): 0.0001 = 0.01%, 0.0005 = 0.05%. */
    private static final double CROWDED = 0.0001;
    private static final double EXTREME = 0.0005;

    private FundingSignals()
    {
    }

    /** Positioning signal for the snapshot, or {@code null} when no funding rate is present. */
    public static ObjectNode signal(ObjectNode snapshot)
    {
        ObjectNode signal = null;
        if (snapshot != null && snapshot.path("funding_rate").isNumber())
        {
            double rate = snapshot.path("funding_rate").asDouble();
            signal = Json.newObject();
            signal.put("positioning", positioning(rate));
            signal.put("funding_rate_pct", Num.round(rate * 100.0, 4));
        }
        return signal;
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
}
