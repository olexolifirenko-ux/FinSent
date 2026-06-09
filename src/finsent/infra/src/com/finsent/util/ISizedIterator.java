/*
 * Copyright (c) 1997-98 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 1.0
 * @author Alexander Prozor
 *
 */


package com.finsent.util;

import com.finsent.util.function.ConsumerWithCheckedException;

import java.util.Iterator;
import java.util.Objects;

/**
 * Iterator that allows to user get the number of
 * possible iteration
 * @author Alexander Prozor
 */
public interface ISizedIterator<E> extends Iterator<E>
{
    /**
     * Returns the number of iteration in the collection
     * @author Alexander Prozor
     */
    public int size();

    default <ExceptionType extends Exception> void forEachRemainingWithException(final ConsumerWithCheckedException<E, ExceptionType> action)
        throws ExceptionType
    {
        Objects.requireNonNull(action);
        while (hasNext())
            action.accept(next());
    }
}
