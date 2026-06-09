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
 *
 */
package com.finsent.util.xml.parser;

import com.finsent.util.xml.*;
import org.w3c.dom.*;

import javax.annotation.concurrent.NotThreadSafe;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;

/**
 * Base abstract class for parsing any XML file.
 * Method createXMLDataBuilder() should be realized in the descendants.
 * Method parseData() is used for parsing.
 *
 * "Builder" design pattern
 * @author Konstantine Matokhin
**/
@NotThreadSafe
public class XMLParser
{
    /**
    *  Is informed about any new primary which is met in the *xml document.
    *  Methods of the builder are called while parsing.
    *  So that, by implementing IXMLDataBuilder, one can organize different
    *  reaction on the any xml primary (elements, attributes).
    *  Override createXMLDataBuilder() method to specify new behavior.
    *  @author Konstantine Matokhin
    */
    private IXMLDataBuilder builder_;
    
    private Transformer t_; // AA: cache, instantiation is pretty expensive

    protected XMLParser()
    {
    }

    public XMLParser(IXMLDataBuilder builder)
    {
        builder_ = builder;
    }

    /**
    *   Override this to provide your own treatement of the each xml element.
    *   @see IXMLDataBuilder
    */
    protected IXMLDataBuilder createXMLDataBuilder() {throw new UnsupportedOperationException("Should be overridden");}

    /**
    *  Performs parsing of the XML file.
    *  @author Konstantine Matokhin
    */
    public void parseData(XMLData data)
    {
        if (builder_ == null) builder_ = createXMLDataBuilder();
        parseDataWithChildren(data);
    }

    /**
    *   CAUTION!!! uses recursion.
    *   @author Konstantine Matokhin
    *
    * PG 05/04/2002 We needn't to create a new instance of variables
    * in each iteration of circles - it leads to memory leak
    */
    protected void parseDataWithChildren(XMLData data)
    {
        Node root = data.getDocumentRoot();
        // if it's a parent element in document - parse all document instead
        Node parentNode = root.getParentNode();
        Document ownerDocument = root.getOwnerDocument();
        if (parentNode == ownerDocument)
            root = ownerDocument;
        parseDataWithChildren(root);
    }

    protected void parseDataWithChildren(Node node)
    {
        short nodeType = node.getNodeType();

        String tagName = null;

        // element start
        if (nodeType == Node.ELEMENT_NODE)
        {
            Element element = (Element) node;
            tagName = element.getTagName();
            boolean multiline = XMLData.isMultilineElement(element);

            builder_.elementStartMet(tagName, multiline);
            for (AttrsIterator it = new AttrsIterator(element); it.hasNext(); )
            {
                Attr attr = it.next();
                builder_.attributeMet(tagName, attr.getNodeName(), attr.getNodeValue());
            }
        }
        else if (nodeType == Node.COMMENT_NODE)
        {
            builder_.commentMet(((Comment) node).getData());
        }
        else if (nodeType == Node.TEXT_NODE)
        {
            builder_.textValueMet(node.getNodeValue());
        }
        else if (nodeType == Node.DOCUMENT_NODE)
        {
            // nothing to do
        }
        else
        {
            String xmlMarkupString = nodeToXMLString(node);
            if (!xmlMarkupString.isEmpty())
                builder_.otherNodeMet(xmlMarkupString);
        }

        // recursive call for child elements
        {
            boolean newSubLevel = nodeType != Node.DOCUMENT_NODE && null != node.getFirstChild();

            if (newSubLevel)
                builder_.subLevel();

            for (Node child = node.getFirstChild(); null != child; child = child.getNextSibling())
                parseDataWithChildren(child);

            if (newSubLevel)
                builder_.superLevel();
        }

        // element end
        if (nodeType == Node.ELEMENT_NODE)
            builder_.elementEndMet(tagName);
    }
    
// Private methods
    
    /**
     * @author Andrey Aleshnikov
     */
    private String nodeToXMLString(Node node)
    {
        StringWriter result = new StringWriter();
        try
        {
            if (null == t_) t_ = createTransformer();
            t_.transform(new DOMSource(node), new StreamResult(result));
        }
        catch (TransformerException te)
        {
            throw new RuntimeException(te);
        }

        return result.toString();
    }
    
    /**
     * @author Andrey Aleshnikov
     */
    private Transformer createTransformer() throws TransformerConfigurationException
    {
        Transformer result = XMLImplemenation.createTransformerFactory().newTransformer();
        result.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        result.setOutputProperty(OutputKeys.INDENT, "yes");
        return result;
    }
}

