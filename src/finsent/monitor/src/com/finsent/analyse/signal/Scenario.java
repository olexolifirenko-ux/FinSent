package com.finsent.analyse.signal;

/**
 * Mechanically classifies an article's scenario from its pre-publication price trend and Claude's
 * directional call (ports Python {@code analyse._compute_scenario}). {@code front_run} = price was
 * already moving the predicted way; {@code reversal} = price was moving the opposite way;
 * {@code fresh_<dir>} = flat/volatile/no-data with a directional call; {@code noise} = neutral/none.
 */
public final class Scenario
{
    private Scenario()
    {
    }

    public static String of(String preTrendLabel, String direction)
    {
        String scenario;
        if (direction == null || direction.equals("neutral"))
        {
            scenario = "noise";
        }
        else if (preTrendLabel == null)
        {
            scenario = "fresh_" + direction; // no OHLC data -- treat as fresh
        }
        else if (aligned(preTrendLabel, direction))
        {
            scenario = "front_run";
        }
        else if (opposed(preTrendLabel, direction))
        {
            scenario = "reversal";
        }
        else
        {
            scenario = "fresh_" + direction; // flat or volatile pre_trend
        }
        return scenario;
    }

    private static boolean aligned(String preTrend, String direction)
    {
        return (preTrend.equals("rising") && direction.equals("bullish"))
                || (preTrend.equals("falling") && direction.equals("bearish"));
    }

    private static boolean opposed(String preTrend, String direction)
    {
        return (preTrend.equals("rising") && direction.equals("bearish"))
                || (preTrend.equals("falling") && direction.equals("bullish"));
    }
}
