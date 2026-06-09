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

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author Andrey Aleshnikov
 */
public class AttrsIterator_utest extends Assert
{

    @Test
    public void testAttrsComparator() throws Exception
    {
        DocumentBuilderFactory dbf = XMLImplemenation.createDocumentBuilderFactory();
        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document doc = builder.newDocument();
        List<Attr> expected = new ArrayList<>(Arrays.asList(
                doc.createAttribute("masha"),
                doc.createAttribute("petya"),
                doc.createAttribute("vasya"),
                doc.createAttributeNS("http://notverycoolnamespace.com", "masha"),
                doc.createAttributeNS("http://notverycoolnamespace.com", "petya"),
                doc.createAttributeNS("http://notverycoolnamespace.com", "vasya"),
                doc.createAttributeNS("http://sosonamespace.com", "masha"),
                doc.createAttributeNS("http://sosonamespace.com", "petya"),
                doc.createAttributeNS("http://sosonamespace.com", "vasya"),
                doc.createAttributeNS("http://verycoolnamespace.com", "masha"),
                doc.createAttributeNS("http://verycoolnamespace.com", "petya"),
                doc.createAttributeNS("http://verycoolnamespace.com", "vasya")));
        for (Attr attr : expected)
            attr.setValue("ns: " + attr.getNamespaceURI() + ", ln: " + attr.getLocalName() + ", qn: " + attr.getName());
        List<Attr> actual = new ArrayList<>(expected);
        Collections.shuffle(actual);
        Collections.sort(actual,  new AttrsIterator.AttrsByNameComparator());
        assertEquals(expected, actual);
    }
}