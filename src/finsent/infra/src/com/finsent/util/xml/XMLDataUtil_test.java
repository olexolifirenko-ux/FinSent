/*
 * Copyright (c) 2014 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 1.0
 *
 */

package com.finsent.util.xml;

import org.junit.Test;
import org.w3c.dom.Element;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * @author Andrey Aleshnikov
 */
public class XMLDataUtil_test
{
    @Test
    public void test_getXPATHLikeNodePath()
    {
        XMLData xml = XMLData.valueOf("<?xml version=\"1.0\"?>"
                + "<root><child1/><child2><grandchild/></child2></root>");
        
        Element el = xml.getDocumentRoot();
        assertEquals("/root", XMLDataUtil.getXPATHLikeNodePath(el));
        
        el = xml.getDocumentPart("child1", false).getDocumentRoot();        
        
        el = xml.getDocumentPart("grandchild", true).getDocumentRoot();        
        assertEquals("/root/child2/grandchild", XMLDataUtil.getXPATHLikeNodePath(el));
    }
    
    @Test
    public void test_findElement()
    {
        XMLData xml = XMLData.valueOf("<?xml version=\"1.0\"?>"
                + "<A desc='root'/>");
        assertNull(XMLDataUtil.findElement(xml.getDocumentRoot(), "A"));
        assertNull(XMLDataUtil.findElement(xml.getDocumentRoot(), "B"));
        
        xml = XMLData.valueOf("<?xml version=\"1.0\"?>"
                + "<A desc='root'>"
                + "   <Child0/>"
                + "   <Child1>"
                + "      <Grandchild1>"
                + "         <Grandgrandchild1/>"
                + "         <A desc='grandgrandchild2'/>"
                + "      </Grandchild1>"
                + "   </Child1>"
                + "   <A desc='child2'/>"
                + "   <B/>"
                + "</A>");
        assertNotNull(xml.stringValue(true), XMLDataUtil.findElement(xml.getDocumentRoot(), "B"));
        Element result = XMLDataUtil.findElement(xml.getDocumentRoot(), "A");
        assertNotNull(xml.stringValue(true), result);
        assertEquals("grandgrandchild2", new XMLData(result).getAttributeStringValue("desc"));        
    }
    
    @Test
    public void test_isChar()
    {
        assertTrue(XMLDataUtil.isChar(65));
        assertFalse(XMLDataUtil.isChar(0xD801));
    }

    @Test
    public void test_timeIntervalAttributes()
    {
        XMLData config1 = XMLData.valueOf("<X a=\"2min\" />");
        assertEquals(120_000_000_000L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.NANOSECONDS, 121));
        assertEquals(120_000L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.MILLISECONDS, 121));
        assertEquals(120L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.SECONDS, 121));
        assertEquals(120_000_000_000L, XMLDataUtil.getTimeIntervalNanos(config1, "a", 121));
        assertEquals(120_000L, XMLDataUtil.getTimeIntervalMillis(config1, "a", 121));
        assertEquals(120L, XMLDataUtil.getTimeIntervalSeconds(config1, "a", 121));

        assertEquals(120_000_000_000L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.NANOSECONDS, 121));
        assertEquals(120_000L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.MILLISECONDS, 121));
        assertEquals(120L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.SECONDS, 121));
        assertEquals(120_000_000_000L, XMLDataUtil.getTimeIntervalNanos(config1, "a", 121));
        assertEquals(120_000L, XMLDataUtil.getTimeIntervalMillis(config1, "a", 121));
        assertEquals(120L, XMLDataUtil.getTimeIntervalSeconds(config1, "a", 121));

        assertEquals(121L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.NANOSECONDS, 121));
        assertEquals(121L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.MILLISECONDS, 121));
        assertEquals(121L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.SECONDS, 121));
        assertEquals(121L, XMLDataUtil.getTimeIntervalNanos(config1, "b", 121));
        assertEquals(121L, XMLDataUtil.getTimeIntervalMillis(config1, "b", 121));
        assertEquals(121L, XMLDataUtil.getTimeIntervalSeconds(config1, "b", 121));

        assertEquals(121L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.NANOSECONDS, 121));
        assertEquals(121L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.MILLISECONDS, 121));
        assertEquals(121L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.SECONDS, 121));
        assertEquals(121L, XMLDataUtil.getTimeIntervalNanos(config1, "b", 121));
        assertEquals(121L, XMLDataUtil.getTimeIntervalMillis(config1, "b", 121));
        assertEquals(121L, XMLDataUtil.getTimeIntervalSeconds(config1, "b", 121));

        assertEquals(120_000_000_000L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.NANOSECONDS, "121s"));
        assertEquals(120_000L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.MILLISECONDS, "121s"));
        assertEquals(120L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.SECONDS, "121s"));
        assertEquals(120_000_000_000L, XMLDataUtil.getTimeIntervalNanos(config1, "a", "121s"));
        assertEquals(120_000L, XMLDataUtil.getTimeIntervalMillis(config1, "a", "121s"));
        assertEquals(120L, XMLDataUtil.getTimeIntervalSeconds(config1, "a", "121s"));

        assertEquals(120_000_000_000L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.NANOSECONDS, "121s"));
        assertEquals(120_000L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.MILLISECONDS, "121s"));
        assertEquals(120L, XMLDataUtil.getTimeInterval(config1, "a", TimeUnit.SECONDS, "121s"));
        assertEquals(120_000_000_000L, XMLDataUtil.getTimeIntervalNanos(config1, "a", "121s"));
        assertEquals(120_000L, XMLDataUtil.getTimeIntervalMillis(config1, "a", "121s"));
        assertEquals(120L, XMLDataUtil.getTimeIntervalSeconds(config1, "a", "121s"));

        assertEquals(121_000_000_000L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.NANOSECONDS, "121s"));
        assertEquals(121_000L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.MILLISECONDS, "121s"));
        assertEquals(121L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.SECONDS, "121s"));
        assertEquals(121_000_000_000L, XMLDataUtil.getTimeIntervalNanos(config1, "b", "121s"));
        assertEquals(121_000L, XMLDataUtil.getTimeIntervalMillis(config1, "b", "121s"));
        assertEquals(121L, XMLDataUtil.getTimeIntervalSeconds(config1, "b", "121s"));

        assertEquals(121_000_000_000L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.NANOSECONDS, "121s"));
        assertEquals(121_000L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.MILLISECONDS, "121s"));
        assertEquals(121L, XMLDataUtil.getTimeInterval(config1, "b", TimeUnit.SECONDS, "121s"));
        assertEquals(121_000_000_000L, XMLDataUtil.getTimeIntervalNanos(config1, "b", "121s"));
        assertEquals(121_000L, XMLDataUtil.getTimeIntervalMillis(config1, "b", "121s"));
        assertEquals(121L, XMLDataUtil.getTimeIntervalSeconds(config1, "b", "121s"));
    }

    @Test
    public void test_unusedAttributes()
    {
        XMLData xml = XMLData.valueOf("<XML a1=\"1\" a2=\"2\" a3=\"3\" a4=\"4\"/>");
        xml = XMLDataUtil.prepareForUnusedAttributes(xml);

        assertEquals(1, xml.getAttributeIntegerValue("a1"));
        assertEquals("2", xml.getAttributeStringValue("a2"));
        assertEquals("?", xml.getAttributeStringValue("b0", "?"));
        assertTrue(xml.isAttributeSet("a3"));
        assertFalse(xml.isAttributeSet("c0"));

        assertEquals(Collections.singleton("a4"), XMLDataUtil.getUnusedAttributes(xml));
    }

    @Test
    public void test_intArray()
    {
        XMLData xml = XMLData.valueOf("<XML a1=\"1, 2,3\" />");
        assertArrayEquals(new int[] {1, 2, 3}, XMLDataUtil.getIntArray(xml, "a1"));
        assertArrayEquals(new int[0], XMLDataUtil.getIntArray(xml, "a2"));
    }
}
