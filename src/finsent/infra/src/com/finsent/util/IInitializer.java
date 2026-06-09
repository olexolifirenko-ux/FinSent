/*
 * Copyright (c) 1998 InfoReach, Inc. All Rights Reserved.
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

public interface IInitializer
{
    public void initialize();

    default String getInitializerName()
    {
        return getClass().getName();
    }
}
