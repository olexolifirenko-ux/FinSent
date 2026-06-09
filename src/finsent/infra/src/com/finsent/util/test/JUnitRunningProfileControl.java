/*
 * Copyright (c) 2015 InfoReach, Inc. All Rights Reserved.
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
package com.finsent.util.test;

import com.finsent.util.RunningProfile;

/**
 * This is intended to control how com.alex.util.RunningProfile is
 * initialized for JUnit tests. E.g. at some moment
 * {@link RunningProfile#isExtraCheckingOn()} started returning true when
 * running from JUnit. Sometimes it is undesirable, so this class can be used:
 * <pre>
 *    &#64;BeforeClass
 *    public static void beforeClass()
 *    {
 *       JUnitRunningProfileControl.initialize(false);
 *    }
 * </pre>
 * @author Andrey Aleshnikov
 */
public class JUnitRunningProfileControl
{
    private static JUnitRunningProfileControl Instance_;
    private final boolean shouldEnableExtraChecking_;

    private JUnitRunningProfileControl(boolean shouldEnableExtraChecking)
    {
        shouldEnableExtraChecking_ = shouldEnableExtraChecking;
    }

    public synchronized static void initialize(boolean shouldEnableExtraChecking)
    {
        Instance_ = new JUnitRunningProfileControl(shouldEnableExtraChecking);
    }

    public synchronized static boolean shouldEnableExtraChecking()
    {
        if (null == Instance_)
            Instance_ = new JUnitRunningProfileControl(true); // true - to preserve old RunningProfile behavior
        return Instance_.shouldEnableExtraChecking_;
    }
}
