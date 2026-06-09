/*
 * Copyright (c) 1997-2012 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 *
 */
package com.finsent.util.xml;

import junit.framework.TestCase;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Tests for XPathUtils class
 *
 * @author Eugeny Schava
 */
public class XPathUtils_utest extends TestCase
{
    public void test() throws Exception
    {
        XMLData data = XMLData.valueOf(
                "<A>" +
                "   <B id=\"1\"/>" +
                "   <B id=\"2\"/>" +
                "</A>");
        Node node = data.getDocumentRoot();

        // find node
        assertTrue(XPathUtils.findNode(node, "/A") instanceof Element && XPathUtils.findNode(node, "/A").getNodeName().equals("A"));
        assertTrue(XPathUtils.findNode(node, "//A") instanceof Element && XPathUtils.findNode(node, "//A").getNodeName().equals("A"));
        assertTrue(XPathUtils.findNode(node, "B") instanceof Element && XPathUtils.findNode(node, "B").getNodeName().equals("B"));
        assertNull(XPathUtils.findNode(node, "C"));

        assertTrue(XPathUtils.findNode(node, "B[@id='1']") instanceof Element && XPathUtils.findNode(node, "B[@id='1']").getNodeName().equals("B"));
        assertNull(XPathUtils.findNode(node, "B[@id='a']"));
        assertTrue(XPathUtils.findNode(node, "B/@id[.='1']") instanceof Attr && XPathUtils.findNode(node, "B/@id[.='1']").getNodeName().equals("id"));
        assertNull(XPathUtils.findNode(node, "A/@id[.='1']"));

        // find nodes
        assertEquals(1, XPathUtils.findNodes(node, "/A").getLength());
        assertEquals(2, XPathUtils.findNodes(node, "B").getLength());
        assertEquals(0, XPathUtils.findNodes(node, "C").getLength());
        assertEquals(0, XPathUtils.findNodes(node, "/B").getLength());

        assertEquals(1, XPathUtils.findNodes(node, "B[@id='1']").getLength());
        assertEquals(2, XPathUtils.findNodes(node, "B/@id").getLength());
        assertEquals(1, XPathUtils.findNodes(node, "B/@id[.='1']").getLength());
    }
}
