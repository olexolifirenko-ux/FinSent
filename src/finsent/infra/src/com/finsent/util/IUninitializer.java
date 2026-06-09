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

/**
   This interface is used to uninitialze the systems.
   The implementor of the interface puts uninitialization tasks into
   uninitialize() method and registers it in GlobalSystem
   calling registerUninitializator().
   uninitialize() method will be called on exiting
*/
public interface IUninitializer
{
    /**
       Performs unitialization tasks
    */
    public void uninitialize();
}
