/*
 * Copyright (c) 2015 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 1.0
 *
 */

package com.finsent.util.xml;

import org.w3c.dom.Node;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Facilitates iteration over immediate node children using
 * {@link Node#getFirstChild()} and
 * {@link Node#getNextSibling()} methods.
 * 
 * The iterator is "lazy" and is backed by underlying DOM tree,
 * therefore its behavior is not specified when the tree is
 * being modified while iterating.
 * 
 * @author Andrey Aleshnikov
 */
public class ChildNodesIterator implements Iterator<Node>
{
    private Node current_;
    
    public ChildNodesIterator(Node parent)  { current_ = parent.getFirstChild(); }

    @Override
    public boolean hasNext()  { return null != current_; }

    @Override
    public void remove()  { throw new UnsupportedOperationException(); }
    
    @Override
    public Node next()
    {
        if (!hasNext())
            throw new NoSuchElementException();
        Node old = current_;
        current_ = current_.getNextSibling();
        return old;
    }
}
