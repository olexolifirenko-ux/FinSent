/*
 * Copyright (c) 2014 InfoReach, Inc. All Rights Reserved.
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

import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

/**
 * @author Andrey Aleshnikov
 */
public class LexicalHandlerMultiplexor implements LexicalHandler
{
    private final LexicalHandler h1_;
    private final LexicalHandler h2_;
    
    public LexicalHandlerMultiplexor(LexicalHandler h1, LexicalHandler h2)
    {
        h1_ = h1;
        h2_ = h2;
    }
    
    public void startDTD(String name, String publicId, String systemId)
            throws SAXException
    {
        h1_.startDTD(name, publicId, systemId);
        h2_.startDTD(name, publicId, systemId);
    }
    public void endDTD() throws SAXException
    {
        h1_.endDTD();
        h2_.endDTD();
    }
    public void startEntity(String name) throws SAXException
    {
        h1_.startEntity(name);
        h2_.startEntity(name);
    }
    public void endEntity(String name) throws SAXException
    {
        h1_.endEntity(name);
        h2_.endEntity(name);
    }
    public void startCDATA() throws SAXException
    {
        h1_.startCDATA();
        h2_.startCDATA();
    }
    public void endCDATA() throws SAXException
    {
        h1_.endCDATA();
        h2_.endCDATA();
    }
    public void comment(char[] ch, int start, int length) throws SAXException
    {
        h1_.comment(ch, start, length);
        h2_.comment(ch, start, length);
    }
}