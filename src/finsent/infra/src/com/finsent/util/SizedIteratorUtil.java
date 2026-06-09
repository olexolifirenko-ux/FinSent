/*
 * Copyright (c) 2002 InfoReach, Inc. All Rights Reserved.
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
package com.finsent.util;

import java.util.Collection;
import java.util.Iterator;

/**
 * Utility methods for {@code ISizedIterator<T>}.
 * @author dmytros
 */
public class SizedIteratorUtil
{
    /** fills {@code collection} with content of {@code iterator}. */
    public static <T, C extends Collection<T>> C fill(C collection, ISizedIterator<T> iterator)
    {
        while (iterator.hasNext())
        {
            collection.add(iterator.next());
        }
        return collection;
    }

    /** creates new sized iterator for {@code collection}, which allows item deletion. */
    public static <T> ISizedIterator<T> newSizedIterator(final Collection<T> collection)
    {
        return newSizedIterator(collection, false);
    }

    /** creates new sized iterator for {@code collection}, either {@code readOnly} or not. */
    public static <T> ISizedIterator<T> newSizedIterator(final Collection<T> collection, final boolean readOnly)
    {
        return new ISizedIterator<T> ()
        {
            final Iterator<T> iterator_ = collection.iterator();

            public int size()
            {
                return collection.size();
            }

            public boolean hasNext()
            {
                return iterator_.hasNext();
            }

            public T next()
            {
                return iterator_.next();
            }

            public void remove()
            {
                if (readOnly)
                    throw new UnsupportedOperationException();
                else
                    iterator_.remove();
            }
        };
    }
}
