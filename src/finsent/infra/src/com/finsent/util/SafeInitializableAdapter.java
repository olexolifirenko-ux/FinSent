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

import com.finsent.util.xml.XMLData;

/**
 * @author Dmytro.Sheyko
 *
 */
public class SafeInitializableAdapter implements IInThreadInitializer
{
    private final IInitializable initializable_;
    private final XMLData config_;

    public SafeInitializableAdapter(IInitializable initializable, XMLData config)
    {
        Objects.requireNonNull(initializable, "initializable");
        Objects.requireNonNull(config, "config");
        initializable_ = initializable;
        config_ = config;
    }

    @Override
    public void initialize()
    {
        String configAsString = config_.stringValue(XMLData.FORMATTING_LEVEL_SMART, true, -1, true);
        String info = initializable_.getClass().getName() + " with configuration: " + configAsString;
        GlobalSystem.getLogFacility().debug().write("Calling initializer: " + info);
        try
        {
            initializable_.initialize(config_);
            GlobalSystem.getLogFacility().debug().write("Calling initializer: success");
        }
        catch (Throwable e)
        {
            GlobalSystem.getLogFacility().error().write("Calling initializer: failure");
            GlobalSystem.getLogFacility().error().write(e);
        }
    }

    @Override
    public boolean isInThread()
    {
        return false;
    }

    @Override
    public String toString()
    {
        return "SafeInitializableAdapter{" + initializable_.toString() + "}";
    }
}
