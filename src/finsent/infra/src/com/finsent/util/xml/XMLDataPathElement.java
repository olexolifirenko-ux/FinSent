/*
 * Copyright (c) 1999-2000 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 1.0
 * @author VS
 * @author Alexander Prozor
 *
 */


package com.finsent.util.xml;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents one element of the XMLDataPath -
 * /tag[attributeName=value]
 * @see com.finsent.util.xml.XMLDataPath
 * @author  VS
 * @author  Alexander Prozor
 * @version 1.0
 */
public class XMLDataPathElement
{
    // Constructors

    /**
     * @param tag - tag name
     */
    public XMLDataPathElement(String tag)
    {
        tagName_ = tag;
    }

    /**
     * @param tag - tag name
     * @param attribute - attribute name
     * @param value - attribute value
     */
    public
    XMLDataPathElement(String tag, String attribute, String value)
    {
        tagName_ = tag;
        attributeName_ = attribute;
        attributeValue_ = value;
    }

    /**
     *
    public
    XMLDataPathElement(String tag, BoolExpr attributeFilter)
    {
        this(tag);
        // NOT SUPPORTED
        // MIGHT BE SUPPORTED IN FUTURE
    }
     */

// Instance methods

    /**
     * Gets tag's name
     * @return tag name.
     * @author  Alexander Prozor
     * @version 1.0
     */
    public String getTagName()
    {
        return tagName_;
    }

    /**
     * Gets key attribute name
     * @return attribute name
     * @author  Alexander Prozor
     * @version 1.0
     */
    public String getAttributeName()
    {
        return attributeName_;
    }
    /**
     * Gets key attribute value
     * @return attribute value.
     * @author  Alexander Prozor
     * @version 1.0
     */
    public String getAttributeValue()
    {
        return attributeValue_;
    }

// Instance variables

    /**
     * Contains tag's name for finding element in the DOM tree
     * @author  Alexander Prozor
     * @version 1.0
     */
    private String tagName_;

    /**
     * Contains key attribute name
     * NOTE: attribute name and value are optional
     * if they are not specified - <tag> element
     * has no sibling of the same type
     * @author  Alexander Prozor
     * @version 1.0
     */
    private String attributeName_;

    /**
     * Contains key attribute value
     * NOTE: attribute name and value are optional
     * if they are not specified - <tag> element
     * has no sibling of the same type
     * @author  Alexander Prozor
     * @version 1.0
     */
    private String attributeValue_;

    public List<Element> getElements(Element parent, boolean searchRecursively)
    {
        List<Element> elements;

        if (tagName_.equals(parent.getNodeName()) &&
            (attributeName_ == null || attributeValue_.equals(parent.getAttribute(attributeName_))))
        {
            elements = Collections.singletonList(parent);
        }
        else
        {
            XMLData parentXMLData = new XMLData(parent);
            List<Element> satisfiedItems = parentXMLData.getElementsByTagName(tagName_, searchRecursively);
            int satisfiedItemCount = satisfiedItems.size();
            elements = new ArrayList<>();

            for(int i=0; i<satisfiedItemCount; i++)
            {
                Element currentElement = satisfiedItems.get(i);
                if(attributeName_ == null || attributeValue_.equals(currentElement.getAttribute(attributeName_)))
                    elements.add(currentElement);
            }
        }
        return elements;
    }

    @Override
    public String toString()
    {
        StringBuilder result = new StringBuilder(getClass().getSimpleName().toString());
        result.append('[');
        appendAsXPATHLikeString(result);
        result.append(']');
        return result.toString();
    }
    
    public String toXPATHLikeString()
    {
        StringBuilder result = new StringBuilder();
        appendAsXPATHLikeString(result);
        return result.toString();
    }

    /**
     * @author Andrey Aleshnikov
     */
    private void appendAsXPATHLikeString(StringBuilder buff)
    {
        buff.append('/');
        if (null == tagName_)
            buff.append('*');
        else
            buff.append(tagName_);
        if (null != attributeName_)
        {
            buff.append("[@").append(attributeName_).append("='").append(attributeValue_)
                    .append('\'').append(']');
        }
    }
}

