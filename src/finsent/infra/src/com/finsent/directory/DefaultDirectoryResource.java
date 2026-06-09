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
 * Trivial {@link IDirectoryResource} that wraps a byte array.
 *
 * @author Konstantine Matokhin
 */
public class DefaultDirectoryResource implements IDirectoryResource
{
    private final byte[] value_;

    public DefaultDirectoryResource(byte[] value)
    {
        value_ = value;
    }

    @Override
    public byte[] getDirectoryValue()
    {
        return value_;
    }
}
