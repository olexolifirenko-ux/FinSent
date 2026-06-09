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

import com.finsent.util.xml.parser.XMLDataCopingBuilder;
import com.finsent.util.xml.parser.XMLParser;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.Map;

/**
 * Updates XML based on template and map of variables
 *
 * @author Eugeny.Schava
 */
public class XMLTemplateUpdater extends XMLDataCopingBuilder
{
    public static final String START = XMLTemplateMatcher.START;
    public static final String END = XMLTemplateMatcher.END;

    private Element currentElement_;
    private Map<String, String> variables_;

    public XMLTemplateUpdater(XMLData template, Map<String, String> variables)
    {
        currentElement_ = template.getDocumentRoot();
        variables_ = variables;
    }

    public XMLData update(XMLData xml)
    {
        new XMLParser(this).parseData(xml);
        return getCopy();
    }

    @Override
    public void elementStartMet(String name, boolean multiline)
    {
        super.elementStartMet(name, multiline);

        // apply all attributes from template at first
        for (AttrsIterator it = new AttrsIterator(currentElement_); it.hasNext(); )
        {
            Attr attribute = it.next();
            String templateValue = attribute.getValue();

            int startIndex = templateValue.indexOf(START);
            if (startIndex >= 0)
            {
                int endIndex = templateValue.indexOf(END, startIndex);
                if (endIndex >= 0)
                {
                    String variable = templateValue.substring(startIndex + START.length(), endIndex);
                    String variableValue = variables_.get(variable);
                    if (variableValue != null)
                    {
                        String value = templateValue.substring(0, startIndex) + variableValue + templateValue.substring(endIndex + END.length());
                        super.attributeMet(name, attribute.getName(), value);
                    }
                }
            }
            else
            {
                super.attributeMet(name, attribute.getName(), templateValue);
            }
        }
    }

    @Override
    public void elementEndMet(String name)
    {
        super.elementEndMet(name);

        Element nextElement = getNextElement(currentElement_.getNextSibling());
        if (nextElement != null)
            currentElement_ = nextElement;
    }

    @Override
    public void subLevel()
    {
        Element nextElement = getNextElement(currentElement_.getFirstChild());
        if (nextElement != null)
            currentElement_ = nextElement;
    }

    @Override
    public void superLevel()
    {
        currentElement_ = (Element) currentElement_.getParentNode();
    }

    @Override
    public void attributeMet(String element, String name, String value)
    {
        String templateValue = currentElement_.getAttribute(name);
        int startIndex = templateValue.indexOf(START);
        if (startIndex >= 0)
        {
            int endIndex = templateValue.indexOf(END, startIndex);
            if (endIndex >= 0)
            {
                String variable = templateValue.substring(startIndex + START.length(), endIndex);
                String variableValue = variables_.get(variable);
                if (variableValue != null)
                {
                    value = templateValue.substring(0, startIndex) + variableValue + templateValue.substring(endIndex + END.length());
                }
//                else
//                {
//                    GlobalSystem.getLogFacility().warning().write("Value for variable " + variable + " is not found");
//                }
            }
        }

        super.attributeMet(element, name, value);
    }

    private static Element getNextElement(Node node)
    {
        while (node != null && node.getNodeType() != Node.ELEMENT_NODE)
            node = node.getNextSibling();
        return (Element) node;
    }
}
