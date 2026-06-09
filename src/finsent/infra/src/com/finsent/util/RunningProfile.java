/*
 * Copyright (c) 1997-2010 InfoReach, Inc. All Rights Reserved.
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
 * Specifies level of checks in application
 *
 * @author Eugeny Schava
 */
public enum RunningProfile
{
    /** Mostly same as DEV, but with some extra (may be temporary) checks */
    CI,
    /** All code and configuration should be checked */
    DEV,
    /** Only configuration should be checked */
    UAT,
    /** Nothing should be checked */
    PROD;

    public static final String PROPERTY_NAME = "runningProfile";

    private static final String CurrentProfileName_ = System.getProperty(PROPERTY_NAME, "PROD");
    private static final RunningProfile CurrentProfile_ = RunningProfile.valueOf(CurrentProfileName_);
    private static final boolean IsInDevMode_ = (CurrentProfile_ == DEV);
    private static final boolean IsInProductionMode_ = (CurrentProfile_ == PROD);
    private static final boolean IsInProductionOrUATMode_ = (CurrentProfile_ == PROD) || (CurrentProfile_ == UAT);
    // Minimal port: the original also auto-enabled extra checking under JUnit
    // (JUnitRunningProfileControl / TestUtils); that test-only hook is dropped here.
    private static final boolean IsExtraCheckingOn_ = (CurrentProfile_ == CI);
    private static final boolean IsCodeChecked_ = (CurrentProfile_ == DEV) || IsExtraCheckingOn_;
    private static final boolean IsConfigurationChecked_ = (CurrentProfile_ == DEV) || (CurrentProfile_ == UAT) || IsExtraCheckingOn_;

    private RunningProfile()
    {
    }

    public static boolean isInDevMode()
    {
        return IsInDevMode_;
    }

    public static boolean isInProductionMode()
    {
        return IsInProductionMode_;
    }

    public static boolean isInProductionOrUATMode()
    {
        return IsInProductionOrUATMode_;
    }

    public static boolean isExtraCheckingOn()
    {
        return IsExtraCheckingOn_;
    }

    public static boolean isCodeChecked()
    {
        return IsCodeChecked_;
    }

    public static boolean isConfigurationChecked()
    {
        return IsConfigurationChecked_;
    }
}
