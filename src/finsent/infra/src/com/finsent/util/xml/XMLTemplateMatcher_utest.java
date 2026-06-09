/*
 * Copyright (c) 1997-2018 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.xml;

import junit.framework.TestCase;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Test for {@link XMLTemplateMatcher}
 *
 * @author Eugeny.Schava
 */
public class XMLTemplateMatcher_utest extends TestCase
{
    public void testOK()
    {
        XMLData queryTemplate = XMLData.valueOf("<Query><Query rows=\"${Rows}\"/><Query expression=\"A gt; 10 AND ${Primary expression|EXPRESSION} AND Y &lt; 10\"/></Query>");
        XMLData query = XMLData.valueOf("<Query><Query rows=\"20\"/><Query expression=\"A gt; 10 AND 1=1 AND Y &lt; 10\"/></Query>");

        XMLTemplateMatcher matcher = new XMLTemplateMatcher(queryTemplate);
        matcher.match(query);
        assertTrue(matcher.isMatched());
        Map<String, String> match = matcher.getVariables();
        assertEquals("20", match.get("Rows"));
        assertEquals("1=1", match.get("Primary expression|EXPRESSION"));
    }

    public void testFail()
    {
        XMLData queryTemplate = XMLData.valueOf("<Query><Query rows=\"${Rows}\"/><Query expression=\"A gt; 10 AND ${Primary expression|EXPRESSION} AND Y &lt; 10\"/></Query>");
        XMLData query = XMLData.valueOf("<Query><Query rows=\"20\"/><Query2 expression=\"A gt; 10 AND 1=1 AND Y &lt; 10\"/></Query>");

        XMLTemplateMatcher matcher = new XMLTemplateMatcher(queryTemplate);
        matcher.match(query);
        assertFalse(matcher.isMatched());
    }

    public void testUnusedParameters()
    {
        XMLData queryTemplate = XMLData.valueOf("<Query rows=\"${Rows}\" columns=\"${Columns}\"/>");
        XMLData query = XMLData.valueOf("<Query rows=\"20\"/>");

        XMLTemplateMatcher matcher = new XMLTemplateMatcher(queryTemplate);
        matcher.match(query);
        assertTrue(matcher.isMatched());
        Map<String, String> match = matcher.getVariables();
        Set<String> unusedParameters = matcher.getUnusedParameters();
        assertEquals(1, match.size());
        assertEquals("20", match.get("Rows"));
        assertEquals(new HashSet<>(Collections.singletonList("Columns")), unusedParameters);
    }
}
