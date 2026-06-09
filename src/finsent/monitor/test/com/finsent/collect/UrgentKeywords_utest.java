package com.finsent.collect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies {@link UrgentKeywords} against Python {@code detect_risks}/{@code _is_urgent_worthy}/
 * {@code _kw_match}: risk-signal and bullish-keyword detection, and whole-word matching for short
 * (&le; 4-char) tokens vs substring matching for longer ones.
 */
public class UrgentKeywords_utest
{
    @Test
    public void riskSignalIsUrgent()
    {
        assertTrue(UrgentKeywords.isUrgentWorthy("SEC charges major exchange", ""));
        assertTrue(UrgentKeywords.isUrgentWorthy("Market crash wipes billions", ""));
    }

    @Test
    public void bullishKeywordIsUrgent()
    {
        assertTrue(UrgentKeywords.isUrgentWorthy("Bitcoin ETF inflow hits record", ""));
        assertTrue(UrgentKeywords.isUrgentWorthy("Fed signals a rate cut", ""));
    }

    @Test
    public void shortTokenMatchesWholeWordOnly()
    {
        // "war" (3 chars) is a whole-word risk token.
        assertTrue(UrgentKeywords.kwMatch("war", "a new war breaks out"));
        // "warfare"/"warranty" contain "war" but not as a whole word -> no match.
        assertFalse(UrgentKeywords.kwMatch("war", "extended warranty offer"));
    }

    @Test
    public void longTokenMatchesSubstring()
    {
        assertTrue(UrgentKeywords.kwMatch("crash", "a flash crash today"));
        assertFalse(UrgentKeywords.kwMatch("crash", "stable markets today"));
    }

    @Test
    public void neutralHeadlineIsNotUrgent()
    {
        assertFalse(UrgentKeywords.isUrgentWorthy("Bitcoin trades sideways in quiet session", ""));
        // contains "war" only inside a longer word -> not urgent
        assertFalse(UrgentKeywords.isUrgentWorthy("New warranty program announced", ""));
    }
}
