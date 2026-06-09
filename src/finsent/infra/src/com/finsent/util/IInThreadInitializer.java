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
 *
 */
package com.finsent.util;

/**
 * Initializer which implements this interface can be run in separate thread.
 *
 * @author zakhar.chelbaevsky
 * @see GlobalSystem#invokeInitializersInThread()
 */
public interface IInThreadInitializer extends IInitializer
{
    default boolean isInThread()
    {
        return true;
    }

    static boolean isInThread(IInitializer initializer)
    {
        return initializer instanceof IInThreadInitializer && ((IInThreadInitializer) initializer).isInThread();
    }
}
