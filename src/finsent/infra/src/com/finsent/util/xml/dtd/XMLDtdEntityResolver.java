/*
 * Copyright (c) 1997-98 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 1.0
 *
 */

package com.finsent.util.xml.dtd;

import com.finsent.directory.DirectorySystem;
import com.finsent.directory.IDirectoryResource;
import com.finsent.directory.SimpleDirectoryAccess;
import com.finsent.util.GlobalSystem;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

/**
*  Without protocol prefix searches:
*  1) in the DIRECTORY
*  2) at the ClassPath
*  3) tries to find it as usual file
*
*   Can search only in the DIRECTORY system.
*   In this case such reference with first "directory://" should be used:
*   E.x. <!DOCTYPE Messages SYSTEM "directory://com/inforeach/util/messages.dtd">
*
*   Also only in the CLASSPATH
*   In this case such reference with first "classpath://" should be used:
*   E.x. <!DOCTYPE Messages SYSTEM "classpath://com/inforeach/util/messages.dtd">
*   @author Konstantine Matokhin
*/
public class XMLDtdEntityResolver implements EntityResolver
{
    private static final String CLASSPATH_PROTOCOL_STRING = "classpath";
    private static final String DIRECTORY_PROTOCOL_STRING = "directory";
    private static final String FILE_PROTOCOL_STRING = "file";
    private static final boolean DEBUG = false;

    public InputSource 	resolveEntity (String name, String uri) throws IOException, SAXException
    {
        if (DEBUG) System.out.println("XMLDtdEntityResolver uri=" + uri);
        InputSource toReturn = null;
        String uriAfterFirstColon = getRidOfFirstSlashes(uri.substring(uri.indexOf(':')+1));

//        URL classURL = ClassLoader.getSystemResource(uriAfterFirstColon);
        URL classURL = XMLDtdEntityResolver.class.getResource("/" + uriAfterFirstColon);

        if (uri.indexOf(FILE_PROTOCOL_STRING) == 0 )// standard should be called
        {
            toReturn = null;
        }
        else if (uri.indexOf(CLASSPATH_PROTOCOL_STRING) == 0 ) // search only in classpath //TODO utest
        {
            if (classURL == null) throw new FileNotFoundException("File " + uriAfterFirstColon + " not found in the CLASSPATH");
            toReturn = new InputSource(classURL.openStream());
            try
            {
                toReturn.setPublicId(classURL.toURI().toASCIIString());
            }
            catch (URISyntaxException e)
            {
                throw new IOException("failed to convert " + classURL.toExternalForm() + " to System ID");
            }            
        }
        else if (uri.indexOf(DIRECTORY_PROTOCOL_STRING) == 0 )// search only in directory
        {
            toReturn = resolveFromDirectory(uriAfterFirstColon, true);
            if (toReturn == null) throw new FileNotFoundException(
                    (DirectorySystem.isInitialized())
                    ? "File " + uriAfterFirstColon + " not found in the DIRECTORY"
                    : "DIRECTORY has not been initialized, file " + uri + " cannot be found");
        }
        else // search anywhere
        {
            //try to find in directory
            toReturn = resolveFromDirectory(uriAfterFirstColon, false);
            if (DEBUG) System.out.println("XMLDtdEntityResolver from DIRECTORY=" + toReturn);
            if ( toReturn == null )
            {
                if (classURL != null) // file is in the CLASSPATH
                {
                    try
                    {
                        toReturn = new InputSource(classURL.toURI().toASCIIString()); //TODO utest
                    }
                    catch (URISyntaxException e)
                    {
                        throw new SAXException(e);
                    }
                    if (DEBUG) System.out.println("XMLDtdEntityResolver classURL=" + classURL.toString());
                    if (DEBUG) System.out.println("XMLDtdEntityResolver from CLASSPATH=" + toReturn);
                }
                else
                {
                    if (DEBUG) System.out.println("XMLDtdEntityResolver cannot resolve");
                    toReturn = null; //cannot be resolved
                }
            }
        }
        return toReturn;
    }

    public InputSource resolveFromDirectory(String uri, boolean showErrors) throws FileNotFoundException
    {
        InputSource retval = null;
        if (DirectorySystem.isInitialized())
        {
            try
            {
                IDirectoryResource resource = SimpleDirectoryAccess.retrieveResource(uri);
                if (resource != null)
                {
                    byte[] value = resource.getDirectoryValue();
                    retval = new InputSource (new java.io.ByteArrayInputStream(value));
                    retval.setSystemId("");
                }
                else
                    if (showErrors)  throw new FileNotFoundException("File " + uri + " not found in the DIRECTORY");

            }
            catch (com.finsent.directory.DirectoryException ex)
            {
                    if (showErrors)
                    {
                        GlobalSystem.getLogFacility().error().write(ex);
                        throw new FileNotFoundException("File " + uri + " not found in the DIRECTORY");
                    }
            }
        }
        return retval;
    }

    protected String getRidOfFirstSlashes(String probablyWithFirstSlashString)
    {
        StringBuilder buffer = new StringBuilder(probablyWithFirstSlashString);
        while (buffer.charAt(0) == '/')
        {
            buffer.delete(0,1);
        }
        return buffer.toString();
    }
}
