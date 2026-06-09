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
 * Wrapper for {@link Node} interface
 *
 * @author Eugeny.Schava
 */
public interface NodeWrapper extends Node
{
    Node getDelegate();

    @Override default String getNodeName() { return getDelegate().getNodeName(); }
    @Override default String getNodeValue() throws DOMException { return getDelegate().getNodeValue(); }
    @Override default void setNodeValue(String nodeValue) throws DOMException { getDelegate().setNodeValue(nodeValue); }
    @Override default short getNodeType() { return getDelegate().getNodeType(); }
    @Override default Node getParentNode() { return getDelegate().getParentNode(); }
    @Override default NodeList getChildNodes() { return getDelegate().getChildNodes(); }
    @Override default Node getFirstChild() { return getDelegate().getFirstChild(); }
    @Override default Node getLastChild() { return getDelegate().getLastChild(); }
    @Override default Node getPreviousSibling() { return getDelegate().getPreviousSibling(); }
    @Override default Node getNextSibling() { return getDelegate().getNextSibling(); }
    @Override default NamedNodeMap getAttributes() { return getDelegate().getAttributes(); }
    @Override default Document getOwnerDocument() { return getDelegate().getOwnerDocument(); }
    @Override default Node insertBefore(Node newChild, Node refChild) throws DOMException { return getDelegate().insertBefore(newChild, refChild); }
    @Override default Node replaceChild(Node newChild, Node oldChild) throws DOMException { return getDelegate().replaceChild(newChild, oldChild); }
    @Override default Node removeChild(Node oldChild) throws DOMException { return getDelegate().removeChild(oldChild); }
    @Override default Node appendChild(Node newChild) throws DOMException { return getDelegate().appendChild(newChild); }
    @Override default boolean hasChildNodes() { return getDelegate().hasChildNodes(); }
    @Override default Node cloneNode(boolean deep) { return getDelegate().cloneNode(deep); }
    @Override default void normalize() { getDelegate().normalize(); }
    @Override default boolean isSupported(String feature, String version) { return getDelegate().isSupported(feature, version); }
    @Override default String getNamespaceURI() { return getDelegate().getNamespaceURI(); }
    @Override default String getPrefix() { return getDelegate().getPrefix(); }
    @Override default void setPrefix(String prefix) throws DOMException { getDelegate().setPrefix(prefix); }
    @Override default String getLocalName() { return getDelegate().getLocalName(); }
    @Override default boolean hasAttributes() { return getDelegate().hasAttributes(); }
    @Override default String getBaseURI() { return getDelegate().getBaseURI(); }
    @Override default short compareDocumentPosition(Node other) throws DOMException { return getDelegate().compareDocumentPosition(other); }
    @Override default String getTextContent() throws DOMException { return getDelegate().getTextContent(); }
    @Override default void setTextContent(String textContent) throws DOMException { getDelegate().setTextContent(textContent); }
    @Override default boolean isSameNode(Node other) { return getDelegate().isSameNode(other); }
    @Override default String lookupPrefix(String namespaceURI) { return getDelegate().lookupPrefix(namespaceURI); }
    @Override default boolean isDefaultNamespace(String namespaceURI) { return getDelegate().isDefaultNamespace(namespaceURI); }
    @Override default String lookupNamespaceURI(String prefix) { return getDelegate().lookupNamespaceURI(prefix); }
    @Override default boolean isEqualNode(Node arg) { return getDelegate().isEqualNode(arg); }
    @Override default Object getFeature(String feature, String version) { return getDelegate().getFeature(feature, version); }
    @Override default Object setUserData(String key, Object data, UserDataHandler handler) { return getDelegate().setUserData(key, data, handler); }
    @Override default Object getUserData(String key) { return getDelegate().getUserData(key); }
}
