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

import java.io.File;

/**
 * Entry point to the directory subsystem.
 *
 * <p>This is a minimal, file-system-backed stand-in for the full InfoReach
 * directory system (which is backed by a directory server, security, sockets
 * and Spring). It resolves resource names against an optional root folder so
 * the XML subsystem can load/store resources without that infrastructure.
 *
 * @author Konstantine Matokhin
 */
public class DirectorySystem
{
    private static final String PROTOCOL_DELIMITER = "://";

    private static volatile File Root_;

    private DirectorySystem() {}

    /**
     * Initializes the default directory with the given root folder.
     *
     * @param rootPath path to the folder that resource names are resolved against,
     *                 or {@code null} to clear it.
     */
    public static void initializeDefault(String rootPath)
    {
        Root_ = (rootPath == null) ? null : new File(rootPath);
    }

    /**
     * Sets (or, with {@code null}, clears) the directory root folder.
     */
    public static void setDirectory(File root)
    {
        Root_ = root;
    }

    public static boolean isInitialized()
    {
        return Root_ != null;
    }

    public static File getRoot()
    {
        return Root_;
    }

    /**
     * Resolves a resource name to a file. Any leading {@code protocol://} prefix
     * is stripped; a relative path is resolved against the configured root folder
     * if one is set.
     */
    public static File resolveToFile(String name)
    {
        String path = stripProtocol(name);
        File file = new File(path);
        if (!file.isAbsolute() && Root_ != null)
            file = new File(Root_, path);
        return file;
    }

    private static String stripProtocol(String name)
    {
        int idx = name.indexOf(PROTOCOL_DELIMITER);
        return (idx >= 0) ? name.substring(idx + PROTOCOL_DELIMITER.length()) : name;
    }
}
