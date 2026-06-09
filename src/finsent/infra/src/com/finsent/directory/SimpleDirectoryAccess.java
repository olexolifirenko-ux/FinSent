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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * Simple static access to directory resources.
 *
 * <p>Minimal, file-system-backed stand-in for the full InfoReach directory
 * access (see {@link DirectorySystem}).
 *
 * @author Sergey Bulakh
 */
public class SimpleDirectoryAccess
{
    private SimpleDirectoryAccess() {}

    /**
     * Retrieves a resource by name, or {@code null} if it does not exist.
     *
     * @throws DirectoryException on any access error.
     */
    public static IDirectoryResource retrieveResource(String name) throws DirectoryException
    {
        File file = DirectorySystem.resolveToFile(name);
        IDirectoryResource result = null;
        if (file.isFile())
        {
            try
            {
                result = new DefaultDirectoryResource(Files.readAllBytes(file.toPath()));
            }
            catch (IOException ex)
            {
                throw new DirectoryException(DirectoryException.FILE_ACCESS_ERROR, ex);
            }
        }
        return result;
    }

    /**
     * Stores a resource under the given name.
     *
     * @throws DirectoryException on any storing error.
     */
    public static boolean storeResource(String name, IDirectoryResource value) throws DirectoryException
    {
        File file = DirectorySystem.resolveToFile(name);
        try
        {
            File parent = file.getParentFile();
            if (parent != null)
                parent.mkdirs();
            try (OutputStream os = new FileOutputStream(file))
            {
                os.write(value.getDirectoryValue());
            }
            return true;
        }
        catch (IOException ex)
        {
            throw new DirectoryException(DirectoryException.FILE_ACCESS_ERROR, ex);
        }
    }
}
