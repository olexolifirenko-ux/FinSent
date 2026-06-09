/*
 * Copyright (c) 1997-2000 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.directory;


/**
 * @author  Konstantine Matokhin
 * This interface needs to implemented
 * by any object which needs to be
 * stored in the directory.
 */
public interface IDirectoryResource
{
    /**
     * Should return array of bytes which will be stored in the Directory.
     */
    public byte[] getDirectoryValue();
}
