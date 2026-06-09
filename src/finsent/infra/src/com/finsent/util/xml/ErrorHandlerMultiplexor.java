/*
 * Copyright (c) 2015 InfoReach, Inc. All Rights Reserved.
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

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.annotation.Nonnull;

/**
 * Will first notify h1, then h2.
 * In case h1 throws during processing an event, the h2 is not notified.
 * 
 * @author Andrey Aleshnikov
 */
class ErrorHandlerMultiplexor implements ErrorHandler
{
    private final ErrorHandler h1_;
    private final ErrorHandler h2_;

    public ErrorHandlerMultiplexor(@Nonnull ErrorHandler h1, @Nonnull ErrorHandler h2)
    {
        h1_ = h1;
        h2_ = h2;
    }

    @Override
    public void warning(SAXParseException exception) throws SAXException
    {
        h1_.warning(exception);
        h2_.warning(exception);
    }

    @Override
    public void error(SAXParseException exception) throws SAXException
    {
        h1_.error(exception);
        h2_.error(exception);
    }

    @Override
    public void fatalError(SAXParseException exception) throws SAXException
    {
        h1_.fatalError(exception);
        h2_.fatalError(exception);
    }
}