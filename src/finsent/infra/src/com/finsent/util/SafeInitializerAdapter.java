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

import java.util.Objects;

/**
 * @author Dmytro.Sheyko
 *
 */
public class SafeInitializerAdapter implements IInThreadInitializer
{
    private final IInitializer initializer_;

    public SafeInitializerAdapter(IInitializer initializer)
    {
        Objects.requireNonNull(initializer);
        initializer_ = initializer;
    }

    @Override
    public void initialize()
    {
        String info = initializer_.getInitializerName();
        GlobalSystem.getLogFacility().debug().write("Calling initializer: " + info);
        try
        {
            initializer_.initialize();
            GlobalSystem.getLogFacility().debug().write("Succeeded calling initializer: " + info);
        }
        catch (Throwable e)
        {
            GlobalSystem.getLogFacility().error().write("Error calling registered initializer: " + info);
            GlobalSystem.getLogFacility().error().write(e);
        }
    }

    @Override
    public boolean isInThread()
    {
        return IInThreadInitializer.isInThread(initializer_);
    }

    @Override
    public String getInitializerName()
    {
        return initializer_.getInitializerName();
    }

    @Override
    public String toString()
    {
        return "SafeInitializerAdapter{" +
            initializer_.toString() +
            '}';
    }
}
