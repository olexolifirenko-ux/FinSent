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

import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.Element;
import org.w3c.dom.TypeInfo;

public interface AttrWrapper extends Attr, NodeWrapper
{
    @Override Attr getDelegate();

    @Override default String getName() { return getDelegate().getName();}
    @Override default boolean getSpecified() { return getDelegate().getSpecified();}
    @Override default String getValue() { return getDelegate().getValue();}
    @Override default void setValue(String value) throws DOMException { getDelegate().setValue(value);}
    @Override default Element getOwnerElement() { return getDelegate().getOwnerElement();}
    @Override default TypeInfo getSchemaTypeInfo() { return getDelegate().getSchemaTypeInfo();}
    @Override default boolean isId() { return getDelegate().isId();}}
