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
   Does nothing. Added for convenience.
   Using this class, not all methods should be overriden in the IXMLDataBuilder
   @see com.finsent.util.xml.parser.IXMLDataBuilder
   @author Konstantine Matokhin
 */
public class XMLDataBuilderAdapter implements IXMLDataBuilder
{
    @Override
    public void elementStartMet(String name, boolean multiline){}
    @Override
    public void elementEndMet(String name){}
    @Override
    public void subLevel(){}
    @Override
    public void superLevel(){}
    @Override
    public void attributeMet(String element, String name, String value){}
    @Override
    public void textValueMet(String text){}
    @Override
    public void commentMet(String comment){}
    @Override
    public void otherNodeMet(String text){}
}
