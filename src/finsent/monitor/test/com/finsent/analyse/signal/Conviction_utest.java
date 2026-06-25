package com.finsent.analyse.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Verifies {@link Conviction}: the SKIP &lt; REDUCED &lt; FULL ordering ({@code meets}), label, and parsing. */
public class Conviction_utest
{
    @Test
    public void meetsRespectsTheOrdering()
    {
        assertTrue(Conviction.FULL.meets(Conviction.FULL));
        assertTrue(Conviction.FULL.meets(Conviction.REDUCED));
        assertTrue(Conviction.REDUCED.meets(Conviction.REDUCED));
        assertTrue(Conviction.REDUCED.meets(Conviction.SKIP));

        assertFalse("reduced does not meet a full minimum", Conviction.REDUCED.meets(Conviction.FULL));
        assertFalse("skip never meets reduced", Conviction.SKIP.meets(Conviction.REDUCED));
        assertFalse(Conviction.SKIP.meets(Conviction.FULL));
    }

    @Test
    public void parsesLabelsAndFallsBack()
    {
        assertEquals(Conviction.FULL, Conviction.of("full", Conviction.SKIP));
        assertEquals(Conviction.REDUCED, Conviction.of("reduced", Conviction.SKIP));
        assertEquals(Conviction.SKIP, Conviction.of("skip", Conviction.FULL));
        assertEquals("unknown -> fallback", Conviction.FULL, Conviction.of("bogus", Conviction.FULL));
        assertEquals("null -> fallback", Conviction.REDUCED, Conviction.of(null, Conviction.REDUCED));
    }

    @Test
    public void labelIsLowercase()
    {
        assertEquals("full", Conviction.FULL.label());
        assertEquals("reduced", Conviction.REDUCED.label());
        assertEquals("skip", Conviction.SKIP.label());
    }
}
