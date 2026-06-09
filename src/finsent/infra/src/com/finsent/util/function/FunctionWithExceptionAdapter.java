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

/**
 * Adapts {@link FunctionWithException} to {@link Function} and handles thrown exceptions
 *
 * @author Eugeny.Schava
 */
public class FunctionWithExceptionAdapter<T, R> implements Function<T, R>
{
    private final FunctionWithException<T, R> functionWithException_;
    private final Function<Throwable, R> exceptionFunction_;

    public FunctionWithExceptionAdapter(FunctionWithException<T, R> functionWithException, Function<Throwable, R> exceptionFunction)
    {
        functionWithException_ = functionWithException;
        exceptionFunction_ = exceptionFunction;
    }

    @Override
    public R apply(T t)
    {
        try
        {
            return functionWithException_.apply(t);
        }
        catch (Throwable e)
        {
            return exceptionFunction_.apply(e);
        }
    }
}
