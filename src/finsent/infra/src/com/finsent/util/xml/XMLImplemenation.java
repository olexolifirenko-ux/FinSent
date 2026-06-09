/*
 * Copyright (c) 1997-98 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 1.0
 */

package com.finsent.util.xml;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.TransformerFactory;

/**
 * Provides the XML factory implementations used throughout the XML subsystem.
 *
 * <p>The original pinned the JRE's built-in Xerces/Xalan implementation classes
 * directly (to defend against an old third-party Apache Xerces appearing on the
 * classpath). This minimal variant uses the standard JAXP {@code newInstance()}
 * factory lookup instead, which resolves to the JRE built-in implementation when
 * no third-party parser is on the classpath. This avoids depending on
 * JDK-internal {@code com.sun.org.apache.*} packages (not exported by the
 * {@code java.xml} module in JDK 17).
 *
 * @author Andrey Aleshnikov
 */
public class XMLImplemenation
{
    // Standard Xerces feature URI (XERCES_FEATURE_PREFIX + DEFER_NODE_EXPANSION_FEATURE).
    private static final String DEFER_NODE_EXPANSION_FEATURE = "http://apache.org/xml/features/dom/defer-node-expansion";

    /**
     * Replacement for {@link SAXParserFactory#newInstance()} to be used in our
     * code.
     */
    public static SAXParserFactory createSAXParserFactory()
    {
        return SAXParserFactory.newInstance();
    }

    /**
     * Replacement for {@link DocumentBuilderFactory#newInstance()} to be used
     * in our code.
     */
    public static DocumentBuilderFactory createDocumentBuilderFactory() throws ParserConfigurationException
    {
        DocumentBuilderFactory result = DocumentBuilderFactory.newInstance();
        result.setFeature(getDeferNodeExpansionFeature(), false);
        return result;
    }

    public static String getDeferNodeExpansionFeature()
    {
        return DEFER_NODE_EXPANSION_FEATURE;
    }

    /**
     * Replacement for {@link TransformerFactory#newInstance()} to be used
     * in our code.
     */
    public static TransformerFactory createTransformerFactory()
    {
        return TransformerFactory.newInstance();
    }
}
