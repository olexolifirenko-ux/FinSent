/*
 * Copyright (c) 1997-2015 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.xml;

import javax.annotation.CheckForNull;

/**
 * @author Denis Lebedev
 */
public interface ILoadedFromFile
{
    /**
     * @return URL of the file this XML data was loaded from. Can be null or empty string.
     */
    @CheckForNull String getLoadedResourcePath();
}
