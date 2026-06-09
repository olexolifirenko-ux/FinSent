/*
 * Copyright (c) 1997-2013 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.xml.parser;

import com.finsent.util.xml.XMLData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * {@link IXMLDataBuilder} impl to copy XML
 *
 * @author Eugene Schava
 */
public class XMLDataCopingBuilder implements IXMLDataBuilder
{
    private XMLData copy_;
    private Document document_;
    private Node currentElement_;

    public XMLDataCopingBuilder()
    {
        copy_ = new XMLData();
        document_ = copy_.getDocumentRoot().getOwnerDocument();
        currentElement_ = document_;
    }

    @Override
    public void elementStartMet(String name, boolean multiline)
    {
        Element newElement = document_.createElement(name);
        if (currentElement_ instanceof Document)
            currentElement_.replaceChild(newElement, currentElement_.getFirstChild());
        else
            currentElement_.appendChild(newElement);
        currentElement_ = newElement;
    }

    @Override
    public void elementEndMet(String name)
    {
        currentElement_ = currentElement_.getParentNode();
    }

    @Override
    public void subLevel()
    {
    }

    @Override
    public void superLevel()
    {
    }

    @Override
    public void attributeMet(String element, String name, String value)
    {
        XMLData.setAttributeValueImpl((Element)currentElement_, name, value);
    }

    @Override
    public void textValueMet(String text)
    {
        currentElement_.appendChild(document_.createTextNode(text));
    }
    
    @Override
    public void otherNodeMet(String text)
    {
        // AA: this is obviously wrong for CDATA etc., but
        // this is how it originally was written (textValueMet was called in
        // such cases)
        currentElement_.appendChild(document_.createTextNode(text));
    }
    
    @Override
    public void commentMet(String comment)
    {
        currentElement_.appendChild(document_.createComment(comment));
    }

    public XMLData getCopy()
    {
        copy_.setDocumentRoot((Element) document_.getFirstChild());
        return copy_;
    }

    public static void main(String[] args)
    {
        XMLData tmsXml = new XMLData("release/backend/Directory/ElTrader/TMS/Config/tms.xml");

        XMLDataCopingBuilder copingBuilder = new XMLDataCopingBuilder();
        new XMLParser(copingBuilder).parseData(tmsXml);
        XMLData tmsXml2 = copingBuilder.getCopy();

        System.out.println(tmsXml);
        System.out.println(tmsXml2);
    }
}
