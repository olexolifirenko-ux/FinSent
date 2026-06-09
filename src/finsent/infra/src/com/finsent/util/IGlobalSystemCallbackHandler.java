/*
 * Copyright (c) 2003 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */


package com.finsent.util;

/**
 * This interface provides for callback mecanism for
 * interaction with calling application to display
 * certain information, such as error and warning messages.
 *
 * @author Vlad Yudkin
 *
 * @see com.finsent.util.GlobalSystem
 */
public interface IGlobalSystemCallbackHandler
{
    /**
     * Sends text message to calling application.
     *
     * @param text the informational message.
     */
    void setProgressInformation(String text);

    void setVersion(String ver);
}
