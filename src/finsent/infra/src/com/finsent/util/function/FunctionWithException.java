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

package com.finsent.util.function;

import java.util.function.Function;

import com.finsent.util.RuntimeWrapperException;

/**
 * Custom version of {@link Function} that can throw an exception during execution
 *
 * @author Eugeny.Schava
 */
@FunctionalInterface
public interface FunctionWithException<T, R>
{
    R apply(T t) throws Throwable;

    static <T, R> Function<T, R> function(FunctionWithException<T, R> functionWithException)
    {
        return function(functionWithException, RuntimeWrapperException.function());
    }

    static <T, R> Function<T, R> function(FunctionWithException<T, R> functionWithException, Function<Throwable, R> exceptionFunction)
    {
        return new FunctionWithExceptionAdapter<>(functionWithException, exceptionFunction);
    }
}
