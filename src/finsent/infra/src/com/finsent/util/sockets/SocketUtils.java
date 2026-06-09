/*
 * Copyright (c) 1997-2018 InfoReach, Inc. All Rights Reserved.
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

package com.finsent.util.sockets;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Socket helpers. Only the {@code "host:port"} parser used by
 * {@code com.alex.properties.PropertyUtils} is carried over here; the broader
 * socket-configuration helpers of the original are omitted.
 */
public class SocketUtils
{
    public static SocketAddress socketAddressFromString(String socketAddress)
    {
        int pos = socketAddress.indexOf(":");
        String hostname = socketAddress.substring(0, pos);
        int port = Integer.parseInt(socketAddress.substring(pos + 1));

        return new InetSocketAddress(hostname, port);
    }

    private SocketUtils() {}
}
