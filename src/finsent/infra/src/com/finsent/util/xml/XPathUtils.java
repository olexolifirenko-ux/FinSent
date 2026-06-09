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

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;

/**
 * Utils for searching for element in XML by XPath expression
 *
 * @author Eugeny Schava
 */
public class XPathUtils
{
    private static final XPathFactory XPATH_FACTORY = XPathFactory.newInstance();
    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = DocumentBuilderFactory.newInstance();

    /**
     * Parses an XML file and returns the corresponding Document object.
     *
     * @param xmlFilePath the path to the XML file to be parsed.
     * @return the parsed Document object.
     * @throws ParserConfigurationException if a DocumentBuilder cannot be created.
     * @throws SAXException                 if any parse errors occur.
     * @throws IOException                  if any IO errors occur while reading the file.
     */
    public static Document parseXmlDocument(String xmlFilePath) throws ParserConfigurationException, SAXException, IOException
    {
        DocumentBuilder builder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
        return builder.parse(xmlFilePath);
    }

    public static NodeList findNodes(Node parentNode, String expression) throws XPathExpressionException
    {
        XPath xPath = XPATH_FACTORY.newXPath();
        return (NodeList) xPath.evaluate(expression, parentNode, XPathConstants.NODESET);
    }

    public static Node findNode(Node parentNode, String expression) throws XPathExpressionException
    {
        XPath xPath = XPATH_FACTORY.newXPath();
        return (Node) xPath.evaluate(expression, parentNode, XPathConstants.NODE);
    }
}
