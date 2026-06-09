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

import com.finsent.util.xml.XMLData;

/**
 * Common interface for all objects which are initialized from XML configuration
 *
 * @author Eugeny Schava
 */
public interface IInitializable
{
    default void initialize(XMLData configData) {}
}
