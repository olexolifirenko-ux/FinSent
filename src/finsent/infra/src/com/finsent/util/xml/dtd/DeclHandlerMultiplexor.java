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


package com.finsent.util.xml.dtd;

import org.xml.sax.SAXException;
import org.xml.sax.ext.DeclHandler;

/**
 * @author Andrey Aleshnikov
 */
public class DeclHandlerMultiplexor implements DeclHandler
{
    private final DeclHandler h1_;
    private final DeclHandler h2_;

    public DeclHandlerMultiplexor(DeclHandler h1, DeclHandler h2)
    {
        h1_ = h1;
        h2_ = h2;
    }

    @Override
    public void elementDecl(String name, String model) throws SAXException
    {
        h1_.elementDecl(name, model);
        h2_.elementDecl(name, model);
    }

    @Override
    public void attributeDecl(String eName, String aName, String type, String mode,
            String value) throws SAXException
    {
        h1_.attributeDecl(eName, aName, type, mode, value);
        h2_.attributeDecl(eName, aName, type, mode, value);
    }

    @Override
    public void internalEntityDecl(String name, String value) throws SAXException
    {
        h1_.internalEntityDecl(name, value);
        h2_.internalEntityDecl(name, value);
    }

    @Override
    public void externalEntityDecl(String name, String publicId, String systemId)
            throws SAXException
    {
        h1_.externalEntityDecl(name, publicId, systemId);
        h2_.externalEntityDecl(name, publicId, systemId);
    }
}
