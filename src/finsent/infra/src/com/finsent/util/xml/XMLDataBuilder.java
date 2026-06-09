/*
 * Copyright (c) 1997-2021 InfoReach, Inc. All Rights Reserved.
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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * @author Dmytro.Sheyko
 */
public class XMLDataBuilder
{
    private final Deque<XMLData> stack_ = new ArrayDeque<>();
    private XMLData root_;

    public static XMLDataBuilder start()
    {
        return new XMLDataBuilder();
    }

    public XMLData build()
    {
        if (root_ == null)
        {
            throw new IllegalStateException();
        }
        return root_;
    }

    public XMLDataBuilder element(String name)
    {
        XMLData current = XMLData.newInstance(name);
        stack_.push(current);
        return this;
    }

    public XMLDataBuilder end()
    {
        XMLData current = stack_.pop();
        if (stack_.isEmpty())
        {
            root_ = current;
        }
        else
        {
            stack_.element().addData(current);
        }
        return this;
    }

    public XMLDataBuilder text(Object text)
    {
        stack_.element().setNodeValue(text == null ? "" : text.toString());
        return this;
    }

    public XMLDataBuilder attr(String name, Object value)
    {
        if (value != null)
        {
            stack_.element().setAttributeValue(name, value.toString());
        }
        return this;
    }
}
