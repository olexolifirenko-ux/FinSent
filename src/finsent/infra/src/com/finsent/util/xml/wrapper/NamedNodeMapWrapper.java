/*
 * Copyright (c) 1997-2022 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.xml.wrapper;

import org.w3c.dom.DOMException;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

public interface NamedNodeMapWrapper extends NamedNodeMap
{
    NamedNodeMap getDelegate();

    @Override default Node getNamedItem(String name) { return getDelegate().getNamedItem(name); }
    @Override default Node setNamedItem(Node arg) throws DOMException { return getDelegate().setNamedItem(arg); }
    @Override default Node removeNamedItem(String name) throws DOMException { return getDelegate().removeNamedItem(name); }
    @Override default Node item(int index) { return getDelegate().item(index); }
    @Override default int getLength() { return getDelegate().getLength(); }
    @Override default Node getNamedItemNS(String namespaceURI, String localName) throws DOMException { return getDelegate().getNamedItemNS(namespaceURI, localName); }
    @Override default Node setNamedItemNS(Node arg) throws DOMException { return getDelegate().setNamedItemNS(arg); }
    @Override default Node removeNamedItemNS(String namespaceURI, String localName) throws DOMException { return getDelegate().removeNamedItemNS(namespaceURI, localName); }}
