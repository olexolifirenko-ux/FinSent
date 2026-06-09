/*
 * Copyright (c) 1997-2015 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.iterator;

import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reverse iterator for list
 *
 * @author Eugeny.Schava
 */
public class ReverseListIterator<I> implements Iterator<I>, Iterable<I>
{
    private ListIterator<I> listIterator_;

    public ReverseListIterator(List<I> list)
    {
        this(list.listIterator(list.size()));
    }

    public ReverseListIterator(ListIterator<I> listIterator)
    {
        listIterator_ = listIterator;
    }

    @Override
    public Iterator<I> iterator()
    {
        return this;
    }

    public Stream<I> stream()
    {
        return StreamSupport.stream(spliterator(), false);
    }

    @Override
    public boolean hasNext()
    {
        return listIterator_.hasPrevious();
    }

    @Override
    public I next()
    {
        return listIterator_.previous();
    }

    @Override
    public void remove()
    {
        listIterator_.remove();
    }
}
