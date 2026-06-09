/*
 * Copyright (c) 1997-2019 InfoReach, Inc. All Rights Reserved.
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

import org.w3c.dom.*;

/**
 * Wrapper for {@link Element} interface
 *
 * @author Eugeny.Schava
 */
public interface ElementWrapper extends NodeWrapper, Element
{
    @Override Element getDelegate();

    @Override default String getTagName() { return getDelegate().getTagName(); }
    @Override default String getAttribute(String name) { return getDelegate().getAttribute(name); }
    @Override default void setAttribute(String name, String value) throws DOMException { getDelegate().setAttribute(name, value); }
    @Override default void removeAttribute(String name) throws DOMException { getDelegate().removeAttribute(name); }
    @Override default Attr getAttributeNode(String name) { return getDelegate().getAttributeNode(name); }
    @Override default Attr setAttributeNode(Attr newAttr) throws DOMException { return getDelegate().setAttributeNode(newAttr); }
    @Override default Attr removeAttributeNode(Attr oldAttr) throws DOMException { return getDelegate().removeAttributeNode(oldAttr); }
    @Override default NodeList getElementsByTagName(String name) { return getDelegate().getElementsByTagName(name); }
    @Override default String getAttributeNS(String namespaceURI, String localName) throws DOMException { return getDelegate().getAttributeNS(namespaceURI, localName); }
    @Override default void setAttributeNS(String namespaceURI, String qualifiedName, String value) throws DOMException { getDelegate().setAttributeNS(namespaceURI, qualifiedName, value); }
    @Override default void removeAttributeNS(String namespaceURI, String localName) throws DOMException { getDelegate().removeAttributeNS(namespaceURI, localName); }
    @Override default Attr getAttributeNodeNS(String namespaceURI, String localName) throws DOMException { return getDelegate().getAttributeNodeNS(namespaceURI, localName); }
    @Override default Attr setAttributeNodeNS(Attr newAttr) throws DOMException { return getDelegate().setAttributeNodeNS(newAttr); }
    @Override default NodeList getElementsByTagNameNS(String namespaceURI, String localName) throws DOMException { return getDelegate().getElementsByTagNameNS(namespaceURI, localName); }
    @Override default boolean hasAttribute(String name) { return getDelegate().hasAttribute(name); }
    @Override default boolean hasAttributeNS(String namespaceURI, String localName) throws DOMException { return getDelegate().hasAttributeNS(namespaceURI, localName); }
    @Override default TypeInfo getSchemaTypeInfo() { return getDelegate().getSchemaTypeInfo(); }
    @Override default void setIdAttribute(String name, boolean isId) throws DOMException { getDelegate().setIdAttribute(name, isId); }
    @Override default void setIdAttributeNS(String namespaceURI, String localName, boolean isId) throws DOMException { getDelegate().setIdAttributeNS(namespaceURI, localName, isId); }
    @Override default void setIdAttributeNode(Attr idAttr, boolean isId) throws DOMException { getDelegate().setIdAttributeNode(idAttr, isId); }
}
