/*
 * Copyright (c) 1997-98 InfoReach, Inc. All Rights Reserved.
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

import com.finsent.util.GlobalDefs;
import com.finsent.util.xml.XMLData.Position;
import org.w3c.dom.Element;

/**
 * Thrown by the <code>getProperty*Value</code> methods of an
 * {@code XMLDataUtil}/{@code XMLData} to indicate that there is no such property
 * configuration file.
 *
 * @see     com.finsent.util.xml.XMLDataUtil#getAttributeStringValue(Node, String)
 * @see     com.finsent.util.xml.XMLDataUtil#getAttributeStringValue(Node, String, String)
 * @see     com.finsent.util.xml.XMLDataUtil#getAttributeIntValue(Node, String)
 * @see     com.finsent.util.xml.XMLDataUtil#getAttributeIntValue(Node, String, int)
 * @see     com.finsent.util.xml.XMLDataUtil#getAttributeDoubleValue(Node, String)
 * @see     com.finsent.util.xml.XMLDataUtil#getAttributeDoubleValue(Node, String, double)
 *
 * @author  AP
 * @version 1.0
 */
public
class XMLNoSuchAttributeException extends RuntimeException
{
// Required methods

	private static final long serialVersionUID = 1L;
	
	private String attrName_;
	private Element el_;
	private String resourcePath_;

	/**
     * Constructs a <code>NoSuchAttributeException</code> with <code>null</code>
     * as its error message string.
     */
    public
    XMLNoSuchAttributeException()
    {
        super();
    }

    /**
     * Constructs a <code>NoSuchAttributeException</code>,  with
     * the error message string <code>message</code>
     *
     * @param attrName the attribute name.
     */
    public XMLNoSuchAttributeException (String attrName)
    {
        super("No attribute was found for: " + attrName +'.');
    } 
    
    /**
     * @author Andrey Aleshnikov
     */
    public XMLNoSuchAttributeException(String attrName, Element el, String resourcePath, Position elemPosition)
    {
        super(composeMessage(attrName, el, resourcePath, elemPosition));
        attrName_ = attrName;
        el_ = el;
        resourcePath_ = resourcePath;
    }
    
    public String getAttrName()
    {
        return attrName_;
    }
    
    public Element getElement()
    {
        return el_;
    }
    
    public String getResourcePath()
    {
        return resourcePath_;
    }
    
// Private methods

    private static String composeMessage(String attrName, Element el, String resourcePath, Position elemPosition)
    {
        StringBuilder result = new StringBuilder();
        result.append("No '").append(attrName).append("' attribute was found in element ");
        result.append(XMLDataUtil.getXPATHLikeNodePath(el));
        if (elemPosition != null)
        {
            result.append(" at ").append(elemPosition);
        }
        if (null != resourcePath && !resourcePath.trim().isEmpty())
            result.append(" of XML loaded from '").append(resourcePath).append("' (or derived from it).");
        else
        {
            
            result.append(" of:").append(GlobalDefs.EOL);
            result.append("--- XML begin ---").append(GlobalDefs.EOL);
            result.append(new XMLData(el).stringValue(true));
            if (-1 == result.indexOf(GlobalDefs.EOL, result.length() - GlobalDefs.EOL.length()))
                result.append(GlobalDefs.EOL);
            result.append("--- XML end ---");
        }
        return result.toString();
    }
} 
