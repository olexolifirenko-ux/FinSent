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


/**
   Is informed by the *xml file parser (XMLParser) about new elements.
   "Builder" design pattern.
   @see com.finsent.util.xml.parser.XMLParser
   @author Konstantine Matokhin
 */
public interface IXMLDataBuilder
{
    /**
     *  is called whenever new element is met in the xml file.
     *  @param name the name of the element
     *  @param multiline whether attributes are to be on separate lines
     */
    public void elementStartMet(String name, boolean multiline);

    /**
       Is called when all attributes has been read in the new element.
     *  @param name the name of the element
     */
    public void elementEndMet(String name);

    /**
    *  is called whenever parser moves on the sub-level (DOWN) in the xml tree.
    */
    public void subLevel();

    /**
    *  is called whenever parser moves back on the super-level (UP) in the xml tree.
    */
    public void superLevel();

    /**
       Is called when new attribute is met in the current field.
       @param element - the name of the element, attribute belongs to.
     */
    public void attributeMet(String element, String name, String value);

    /**
     *  is called when text value of node is met in the xml file.
     *  @param text the value of the element.
     */
    public void textValueMet(String text);

    void commentMet(String comment);
    
    /**
     *  is called when the met node is none of these: element, comment, text.
     *  @param markup text representation of the met node.
     *  @author Andrey Aleshnikov
     */
    public void otherNodeMet(String markupText);
}
