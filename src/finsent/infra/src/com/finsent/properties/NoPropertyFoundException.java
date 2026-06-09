/*
 * Copyright (c) 1997-2019 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.properties;

public class NoPropertyFoundException extends PropertyException {

    private static final long serialVersionUID = 1L;

    public NoPropertyFoundException(String name) {
        super("Property not found: " + name);
    }
}
