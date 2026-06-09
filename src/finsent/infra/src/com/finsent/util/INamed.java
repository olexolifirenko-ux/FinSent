/*
 * Copyright (c) 2006 InfoReach, Inc. All Rights Reserved.
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
 * Named entity interface.
 * @author Andrey Aleshnikov
 */

public interface INamed
{
    /**
     * @return String - entity's name.
     */
    String getName();

    static String getNameOrClassName(Object obj)
    {
        return obj instanceof INamed ? ((INamed) obj).getName() : obj.getClass().getSimpleName();
    }

    static String getNameOrToString(Object obj)
    {
        return obj instanceof INamed ? ((INamed) obj).getName() : String.valueOf(obj);
    }
}
