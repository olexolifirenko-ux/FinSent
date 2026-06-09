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

import com.finsent.util.GlobalSystem;
import com.finsent.util.xml.parser.IXMLDataBuilder;
import com.finsent.util.xml.parser.XMLParser;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks if given XML matches template XML
 *
 * @author Eugeny.Schava
 */
public class XMLTemplateMatcher implements IXMLDataBuilder
{
    public static final String START = "${";
    public static final String END = "}";

    private Element currentElement_;
    private boolean matched_ = true;
    private Map<String, String> variables_ = new HashMap<>();
    private Set<String> unusedParameters_ = new HashSet<>();

    public XMLTemplateMatcher(XMLData template)
    {
        currentElement_ = template.getDocumentRoot();
    }

    public void match(XMLData xml)
    {
        new XMLParser(this).parseData(xml);
    }

    public boolean isMatched()
    {
        return matched_;
    }

    public Map<String, String> getVariables()
    {
        return variables_;
    }

    public Set<String> getUnusedParameters()
    {
        return unusedParameters_;
    }

    @Override
    public void elementStartMet(String name, boolean multiline)
    {
        if (!currentElement_.getTagName().equals(name))
        {
            GlobalSystem.getLogFacility().error().write("Template element name " + currentElement_.getTagName() + " doesn't match " + name);
            matched_ = false;
        }
        else
        {
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
                        String parameter = templateValue.substring(startIndex + START.length(), endIndex);
                        unusedParameters_.add(parameter);
                    }
                }
            }
        }
    }

    @Override
    public void elementEndMet(String name)
    {
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
                if (startIndex <= value.length() &&
                    value.substring(0, startIndex).equals(templateValue.substring(0, startIndex)) &&
                    value.substring(value.length() - templateValue.length() + endIndex + END.length()).equals(templateValue.substring(endIndex + END.length())))
                {
                    String parameter = templateValue.substring(startIndex + START.length(), endIndex);
                    variables_.put(parameter, value.substring(startIndex, value.length() - templateValue.length() + endIndex + END.length()));
                    unusedParameters_.remove(parameter);
                }
                else
                {
                    GlobalSystem.getLogFacility().error().write("Value '" + value + "' of attribute " + name + " doesn't match template value of '" + templateValue + "'");
                    matched_ = false;
                }
            }
        }
    }

    @Override
    public void textValueMet(String text)
    {
    }

    @Override
    public void commentMet(String comment)
    {
    }

    @Override
    public void otherNodeMet(String markupText)
    {
    }

    private static Element getNextElement(Node node)
    {
        while (node != null && node.getNodeType() != Node.ELEMENT_NODE)
            node = node.getNextSibling();
        return (Element) node;
    }
}
