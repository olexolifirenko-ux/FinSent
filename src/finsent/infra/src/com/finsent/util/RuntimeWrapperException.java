/*
 * Copyright (c) 1997-2016 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runtime exception used only to wrap checked exception
 *
 * @author Eugeny.Schava
 */
public final class RuntimeWrapperException extends RuntimeException
{
    private static final long serialVersionUID = 3356285465035816213L;

    public static final Consumer<Throwable> CONSUMER = e -> {
        if (e instanceof RuntimeException)
            throw (RuntimeException)e;
        else
            throw new RuntimeWrapperException(e);
    };

    public static final Function<Throwable, ?> FUNCTION = e -> {
        if (e instanceof RuntimeException)
            throw (RuntimeException)e;
        else
            throw new RuntimeWrapperException(e);
    };

    public RuntimeWrapperException(Throwable cause)
    {
        super(cause);
    }

    public static <R, T> Function<T, R> function()
    {
        //noinspection unchecked
        return (Function<T, R>) FUNCTION;
    }

    public static <T> Consumer<T> consumer()
    {
        //noinspection unchecked
        return (Consumer<T>) CONSUMER;
    }
}
