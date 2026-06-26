package com.finsent.analyse.claude;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies {@link PromptTemplates#version} is a stable, fixed-width content hash: same text -> same
 * 12-char hex; a one-char change -> a different hash (so the feedback loop can tell prompt revisions apart).
 */
public class PromptTemplates_utest
{
    @Test
    public void versionIsTwelveHexCharsAndDeterministic()
    {
        String v = PromptTemplates.version("STEP 3 -- CHANNEL to crypto?\nRULES: ...");
        assertEquals(12, v.length());
        assertTrue("hex only", v.matches("[0-9a-f]{12}"));
        assertEquals(v, PromptTemplates.version("STEP 3 -- CHANNEL to crypto?\nRULES: ..."));
    }

    @Test
    public void versionChangesWhenPromptChanges()
    {
        assertNotEquals(PromptTemplates.version("rules v1"), PromptTemplates.version("rules v2"));
    }
}
