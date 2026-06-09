package com.finsent.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeric helpers shared across the pipeline. Ports the rounding convention of the Python
 * modules ({@code round(value, places)}): half-to-even ("banker's") rounding, so the stored
 * macro/options figures match the Python data files digit-for-digit.
 */
public final class Num
{
    private Num()
    {
    }

    /**
     * Round to {@code places} decimal places using half-to-even, matching Python's built-in
     * {@code round()}. Values are taken via their canonical decimal representation (as Python
     * does), so e.g. {@code round(2.675, 2)} yields {@code 2.67}.
     */
    public static double round(double value, int places)
    {
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }
}
