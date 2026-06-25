package com.finsent.analyse.signal;

import java.util.Locale;

/**
 * The mechanical conviction grade the FastMove funding/OI gate assigns a fire: how strongly open interest
 * backs the move, ordered {@code SKIP < REDUCED < FULL}. Unlike the LLM-sourced direction / impact_tier
 * (which arrive as JSON strings), conviction is produced entirely in-process, so it is a proper typed,
 * closed set rather than a bare string.
 * <ul>
 *   <li>{@code FULL}    -- OI building into the move: fresh positioning, a real trend.</li>
 *   <li>{@code REDUCED} -- OI flat or no funding read: unconfirmed (trade smaller, or below the gate).</li>
 *   <li>{@code SKIP}    -- OI unwinding: positions closing, a likely transient wick; never opens.</li>
 * </ul>
 */
public enum Conviction
{
    SKIP(0),
    REDUCED(1),
    FULL(2);

    private final int rank_;

    Conviction(int rank)
    {
        rank_ = rank;
    }

    /** Whether this grade is at least {@code minimum} -- the entry gate (e.g. {@code FULL.meets(REDUCED)}). */
    public boolean meets(Conviction minimum)
    {
        return rank_ >= minimum.rank_;
    }

    /** The lowercase config/log label, e.g. {@code "full"}. */
    public String label()
    {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a label ({@code full}/{@code reduced}/{@code skip}); {@code fallback} for anything unrecognized. */
    public static Conviction of(String label, Conviction fallback)
    {
        Conviction result = fallback;
        for (Conviction conviction : values())
        {
            if (conviction.label().equals(label))
            {
                result = conviction;
            }
        }
        return result;
    }
}
