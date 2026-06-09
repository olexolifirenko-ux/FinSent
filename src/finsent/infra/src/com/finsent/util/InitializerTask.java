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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

class InitializerTask extends FutureTask<Void> implements IInitializer
{
    private final String name_;

    public InitializerTask(final IInitializer initializer)
    {
        super(new Runnable()
        {
            @Override
            public void run()
            {
                initializer.initialize();
            }
        }, null);
        name_ = initializer.getInitializerName();
    }

    @Override
    public void initialize()
    {
        try
        {
            get();
        }
        catch (InterruptedException e)
        {
            // do nothing
        }
        catch (ExecutionException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString()
    {
        return getInitializerName();
    }

    @Override
    public String getInitializerName()
    {
        return this.getClass().getName() + "(" + name_ + ")";
    }
}
