/*
 * Copyright (c) 1999-2000 InfoReach, Inc. All Rights Reserved.
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

import com.finsent.util.Pair;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default error handler.
 * @author Andrey Aleshnikov
 */
public class XMLDefaultErrorHandler implements ErrorHandler
{
    public static enum EventType {WARNING, ERROR, FATAL_ERROR};    
    private final List<Pair<EventType, SAXParseException>> receivedEvents_ = new ArrayList<>();
    
    @Override
    public void warning(SAXParseException ex) throws SAXException
    { receivedEvents_.add(new Pair<>(EventType.WARNING, ex)); }

    @Override
    public void error(SAXParseException ex) throws SAXException
    { receivedEvents_.add(new Pair<>(EventType.ERROR, ex)); }

    @Override
    public void fatalError(SAXParseException ex) throws SAXException
    { receivedEvents_.add(new Pair<>(EventType.FATAL_ERROR, ex)); }
    
    public List<Pair<EventType, SAXParseException>> getParsingIssues()
    { return Collections.unmodifiableList(receivedEvents_); }
    
    public boolean issuesFound()
    { return !receivedEvents_.isEmpty(); }
}
