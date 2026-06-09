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

package com.finsent.util;

import com.finsent.directory.DirectorySystem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Loads/saves resources by path.
 *
 * <p>This is a minimal, file-system-backed stand-in for the full InfoReach
 * {@code ResourceLoader} (which is backed by Spring, commons-vfs2 and the
 * directory subsystem). Resource names are resolved through
 * {@link DirectorySystem#resolveToFile(String)}.
 *
 * @author Alexey Getmanchuk
 */
public class ResourceLoader
{
    public static final String PROTOCOL_PATH_DELIMITER = "://";

    private static final ResourceLoader Instance_ = new ResourceLoader();

    private ResourceLoader() {}

    public static ResourceLoader getInstance()
    {
        return Instance_;
    }

    /**
     * Loads the bytes of the resource at the given path.
     *
     * @throws ResourceLoaderException if the resource cannot be found or read.
     */
    public static byte[] load(String resourcePath) throws ResourceLoaderException
    {
        File file = DirectorySystem.resolveToFile(resourcePath);
        if (!file.isFile())
            throw new ResourceLoaderException("Resource not found: " + resourcePath);
        try
        {
            return Files.readAllBytes(file.toPath());
        }
        catch (IOException ex)
        {
            throw new ResourceLoaderException("Could not load resource: " + resourcePath, ex);
        }
    }

    /**
     * Saves the given bytes to the resource at the given path.
     *
     * @throws ResourceLoaderException if the resource cannot be written.
     */
    public static void save(String resourcePath, byte[] data) throws ResourceLoaderException
    {
        File file = DirectorySystem.resolveToFile(resourcePath);
        try
        {
            File parent = file.getParentFile();
            if (parent != null)
                parent.mkdirs();
            try (OutputStream os = new FileOutputStream(file))
            {
                os.write(data);
            }
        }
        catch (IOException ex)
        {
            throw new ResourceLoaderException("Could not save resource: " + resourcePath, ex);
        }
    }

    /**
     * Returns the matching resource paths. This minimal variant resolves a single
     * resource (the full loader supports wildcard patterns for {@code <Include>}).
     *
     * @throws ResourceLoaderException reserved for resolution errors.
     */
    public String[] findResources(String resourcePattern) throws ResourceLoaderException
    {
        File file = DirectorySystem.resolveToFile(resourcePattern);
        return file.isFile() ? new String[] { resourcePattern } : new String[0];
    }
}
