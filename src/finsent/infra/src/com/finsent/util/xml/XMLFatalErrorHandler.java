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
 */

package com.finsent.util.xml;

import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Ignore all, but fatal error(s).
 *
 * @author Andrey Aleshnikov
 */
class XMLFatalErrorHandler extends XMLDefaultErrorHandler
{
    @Override
    public void warning(SAXParseException exception) throws SAXException { }

    @Override
    public void error(SAXParseException exception) throws SAXException { }
}
