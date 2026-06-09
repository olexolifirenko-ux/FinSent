/*
 * Copyright (c) 1997-2017 InfoReach, Inc. All Rights Reserved.
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

import java.util.function.Consumer;

/**
 * Extension of the standard Java interface to allow throw checked Exception
 * @param <T> - type of the input argument
 * @param <E> - type of the exception
 *
 * @author Andrey Slyusarenko
 */
@FunctionalInterface
public interface ConsumerWithCheckedException<V, E extends Exception>
{
    void accept(V value) throws E;

    static <V, E extends Exception> Consumer<V> wrapConsumer(ConsumerWithCheckedException<V, E> consumer)
    {
        return value ->
        {
            try
            {
                consumer.accept(value);
            }
            catch (Exception exception)
            {
                uncheck(exception);
            }
        };
    }

    @SuppressWarnings ("unchecked")
    static <T extends Throwable> void uncheck(Exception exception) throws T { throw (T)exception; }
}
