/*
 * Copyright (c) 1997-2005 InfoReach, Inc. All Rights Reserved.
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

package com.finsent.util.xml;

import com.finsent.util.ResourceLoaderException;
import com.finsent.util.text.LinesIterator;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Command line utility: XML Beautify Tool
 * <p>
 * Ignores SVN directories while looks for files. Only files with specific extensions treats as xml files.
 * Not changes tags and attributes ordering in resulting xml files.
 * To avoid DTD files resolving comments DOCTYPE before file processing and uncomments after beautifing.
 * <p>
 * Note: deprecated xml parser was used here. Why? Unfortunately, standard JDK DOM parser changes order of
 * attributes. Unfortunately2, used parser changes EOLs in comments and CDATA sections from \r\n to \r, so
 * we need to fix this programmaticaly.       
 *
 * @author Taras Aladyin
 * @author Andrew Krivtsun
 * @author Ruslan Petrenko
 * @author Andrey Aleshnikov
 */

public class XMLBeautify
{
    static final char MAIN_OPTION_CHECK = 'c';
    static final String MAIN_OPTION_CHECK_LONG = "--check";

    static final char MAIN_OPTION_VERIFY_BEAUTY = 'v';
    static final String MAIN_OPTION_VERIFY_BEAUTY_LONG = "--verify";

    static final char MAIN_OPTION_BEAUTIFY = 'b';
    static final String MAIN_OPTION_BEAUTIFY_LONG = "--beautify";

    static final char MAIN_OPTION_QUIET = 'q';
    static final String MAIN_OPTION_QUIET_LONG = "--quiet";

    static final String MAIN_OPTION_PEDANTIC_LONG = "--pedantic";

    static final char MAIN_OPTION_BEAUTIFY_FROM_FILE = 'f';
    static final String MAIN_OPTION_BEAUTIFY_FROM_FILE_LONG = "--file";

    //  ignore subservient SVN directories
    final static String IGNORE_DIR = ".svn"; 
    // only files with this extensions will be beautifying 
    public final static String[] ALLOWED_EXTS = System.getProperty("xml.exts") != null
         ? System.getProperty("xml.exts").split(",")
         : new String[]{"xml", "report", "reportspec", "window", "workspace", "templates", "metadata", "levels", "jhm"};
    
    // these files could be XMLs, but could be not
    final static String[] QUESTIONABLE_EXTS = {"format", "required", "hs"};

    // right page margin in symbols 
    final static int SYMBOLS_IN_LINE = 120;
    // indent symbol
    final static String INDENT = "\t";
    // show many symbols has tab
    final static int SPACES_IN_TAB = 8;
    
    final static int RES_VERIFY_ERROR = 2;
    final static int RES_NOT_XML = 3;
    
    // 0 - all file successfully verified on Beautify, VERIFY_ERROR - one or more file not beautifying
    static int resultCode = 0;
    
    public enum Operation {
        XMLCheck,
        beautify,
        verifyBeauty,
    }
    
    /**
     * @deprecated original data bytes should be passed to XML parser, not characters
     */
    public static String FileToString(String fname) throws IOException
    {
        byte[] bytes = Files.readAllBytes(new File(fname).toPath());
        return new String(bytes, XMLData.DEFAULT_ENCODING);
    }
    
    public static void XMLByPathBeautify(Operation op, File f, boolean quiet, boolean pedantic)
    {
        String path = f.getAbsolutePath();        
        try
        {
            if (!quiet)
                System.out.println(path);
            
            ValidatingXMLData xml = null;
            try
            {
                xml = new ValidatingXMLData(path, null, false);
            }
            catch (Exception ex)
            {
                if  (!hasQuestionableExt(path))
                    throw ex;

                System.err.println("Got " + ex.getClass() + ": '"
                        + ex.getMessage() + "' when parsing '" + path
                        + "', so it could be either correct non-XML or broken XML.");
                return;
            }
              
            if (op == Operation.XMLCheck)
                return;
            
            String xmlPrettyStr = xml.stringValue(true);            
            // If XML file has comment <!-- NO FORMAT CHECK--> - skip beautify/beauty-check
            if ((op == Operation.verifyBeauty || op == Operation.beautify) && XMLBeautifyUtil.hasNoFormatCheck(xmlPrettyStr))
                return;
            
            if (!quiet)
                System.out.println("\tchecked successfully");
             
            if (op == Operation.beautify || op == Operation.verifyBeauty) 
            {
                List<String> lines = op == Operation.beautify ? new ArrayList<>() : null;
                if (!isBeautified(path, xmlPrettyStr, pedantic, lines))
                {
                    if (op == Operation.verifyBeauty)
                    {
                        System.out.println("File '"+path+"' is not beautified");
                        resultCode = RES_VERIFY_ERROR;
                    }
                    else // op == Operation.beautify
                    {
                        XMLBeautifyUtil.cutOffTrailingEmptyLines(lines);
                        XMLBeautifyUtil.writeLinesToFile(Paths.get(path), lines);
                        System.out.println("\tbeautified successfully");
                    }
                }
            }
        }
        catch (IOException ex)
        {
            // troubles with file reading/writing
            System.out.println(ex.getMessage());
            ex.printStackTrace();            
        }
        catch (Exception ex)
        {
            // troubles with xml parsing
            System.out.println(path + " can't be parsed as xml file");
            resultCode = RES_NOT_XML;
            ex.printStackTrace();
        }
    }

    private static boolean hasQuestionableExt(String path)
    {
        for (String ext: QUESTIONABLE_EXTS)
        {
            if (path.endsWith("." + ext))
            {
                return true;
            }    
        }
        return false;
    }
    
    private static boolean isBeautified(String path, 
            String beautifiedXMLStr, 
            boolean pedantic,
            Collection<String> lines) throws IOException
    {
        // compare ignoring EOLs
        String inputXMLStr = FileToString(path);
        LinesIterator inputLines = new LinesIterator(inputXMLStr);
        LinesIterator beautifiedXMLStrLines = new LinesIterator(beautifiedXMLStr);
        while (true)
        {
            if (inputLines.hasNext())
            {
                if (beautifiedXMLStrLines.hasNext())
                {
                    String beautifiedXMLStrLine = beautifiedXMLStrLines.next();
                    String inputLine = inputLines.next();
                    if (XMLBeautifyUtil.isAcceptable(pedantic, beautifiedXMLStrLine, inputLine))
                    {
                        if (lines != null)
                        {
                            lines.add(inputLine);
                        }
                    }
                    else
                    {
                        drainRestBeautifiedLines(beautifiedXMLStrLine, beautifiedXMLStrLines, lines);
                        return false;
                    }
                }
                else
                {
                    // beautified lines are exhausted, nothing to drain
                    return false;
                }
            }
            else
            {
                if (beautifiedXMLStrLines.hasNext())
                {
                    String beautifiedXMLStrLine = beautifiedXMLStrLines.next();
                    drainRestBeautifiedLines(beautifiedXMLStrLine, beautifiedXMLStrLines, lines);
                    return false;
                }
                else
                {
                    // ok
                    return true;
                }
            }
        }
    }

    private static void drainRestBeautifiedLines(String beautifiedXMLStrLine, Iterator<? extends String> beautifiedXMLStrLines, Collection<? super String> lines)
    {
        if (lines != null)
        {
            lines.add(beautifiedXMLStrLine);
            while (beautifiedXMLStrLines.hasNext())
            {
                lines.add(beautifiedXMLStrLines.next());
            }
        }
    }

    /**
     * Converts XML document to text. Produces beautified
     * text. Does not uncomment DOCTYPE section.
     * @param doc XML document
     * @return document string or null if XML cannot be converted because
     *         it contains text node. 
     * @throws IOException I/O exception
     */
    public static String XMLByDocBeautify(Document doc) throws IOException
    {
        return XMLByDocBeautifyImpl(doc);
    }    
    
    private static String XMLByDocBeautifyImpl(Document doc) throws IOException
    {
        return new XMLData(doc.getDocumentElement()).stringValue(true);
    }
    
    /**
     * Expand file tree
     * @param file tree for expanding
     * @return arraylist of inner files
     */
    public static List<File> expandFileTree(File file)
    {
        return expandFileTree(file, 0);
    }
    /**
     * Expand file tree
     * @param file tree for expanding
     * @return arraylist of inner files
     */
    private static List<File> expandFileTree(File file, int depth)
    {
        List<File> result = new ArrayList<>();
        if (file.exists())
        {
            if (file.isDirectory())
            {
                if (!file.getName().equals(IGNORE_DIR))
                {
                    File[] files = file.listFiles();
                    if (files != null)
                    {
                        for (File child : files)
                            result.addAll(expandFileTree(child, depth + 1));
                    }
                }
            }
            else if (0 == depth)
            {
                // files explicitly specified as XMLBeautify args should be processes regardless of their extension
                result.add(file);
            }
            else
            {
                boolean hasAllowedExt = false;
                String name = file.getAbsolutePath();
                for (String ext: ALLOWED_EXTS)
                {
                    if (name.endsWith("." + ext))
                    {
                        result.add(file);
                        hasAllowedExt = true;
                        break;
                    }    
                }
                if (!hasAllowedExt && hasQuestionableExt(name))
                    result.add(file);
            }
        }
        return result;
    }
    
/// Inner classes ///////////////////////////////////////////////////////////
    
    /**
     * XMLData child that is intended to not just log/print parse error, but
     * store it and provide external access to it. Hence it facilitates
     * custom processing of parse errors.
     * 
     * @author Andrey Aleshnikov
     */
    static final class ValidatingXMLData extends XMLData
    {
        private static final long serialVersionUID = 1L;
        
        ValidatingXMLData(String urlConfigInfoFile, 
                ErrorHandler errorHandler,
                boolean dummy)
        throws ResourceLoaderException, XMLBadDataException
        {
            super(urlConfigInfoFile, errorHandler, dummy);
        }

        ValidatingXMLData(String dataString, ErrorHandler errorHandler, boolean quiet, boolean dummy)
            throws XMLBadDataException
        {
            super(dataString, errorHandler, quiet, dummy);
        }

        @Override
        protected EntityResolver createEntityResolver()
        {
            // AA: we need to prevent our parser from downloading any external DTDs as:
            // - pretty often external DTDs are located in directory://, but XMLBeautify knows nothing about Directory.
            //   E.g. when used as svn commit hook on svn server, it will be invoked files belonging to various
            //   branches, so we'll need to invent some heuristic to properly init DirectorySystem.
            // - we use relative paths for external DTDs located EITHER in Directory OR in file system. 
            //   So, we'll have to address that too.
            // -  DTDs located on WEB often get moved/screwed. e.g. this one:
            //    <!DOCTYPE toc
            //        PUBLIC "-//Sun Microsystems Inc.//DTD JavaHelp TOC Version 1.0//EN"
            //        "http://java.sun.com/products/javahelp/toc_2_0.dtd">
            // - more caveats?
            // Given the above let's just live without external DTDs for now.
            
            // AA: according to XML 1.0 specification (http://www.w3.org/TR/2008/REC-xml-20081126/) 
            // specification "external entity" can only refer to DTD entities, so providing 
            // a dummy resolver should not break anything.
            return new DummyEntityResolver();
        }
    }
    
/////////////////////////////////////////////////////////////////

    static class TextNodeException extends Exception 
    {  
        /**
		 * 
		 */
		private static final long serialVersionUID = 1L;

		public TextNodeException(String msg) {
            super(msg);
        }
    }

    /**
     * XMLBeautify tool give arguments:
     * 
     * -b - to execute xml beautifing
     * -c - only check xml files structure
     * -v - verify beautifing
     * -q - quiet mode
     * file_name - xml file for beautifing 
     * directory_path - root directory. Directory will be expanded and all inner xml files will be beautified.
     *
     * @author Taras Aladyin
     */
    public static void main(String[] args)
    {
        boolean quiet = false;
        boolean pedantic = false;
        ArrayList<String> params = new ArrayList<>();
        Operation op = Operation.beautify;
        boolean fromFile = false;
        for (String arg: args) 
        {
            if (arg.startsWith("-"))
            {
                if (arg.startsWith("--"))
                {
                    switch (arg)
                    {
                        case MAIN_OPTION_BEAUTIFY_LONG:
                            op = Operation.beautify;
                            break;
                        case MAIN_OPTION_CHECK_LONG:
                            op = Operation.XMLCheck;
                            break;
                        case MAIN_OPTION_VERIFY_BEAUTY_LONG:
                            op = Operation.verifyBeauty;
                            break;
                        case MAIN_OPTION_BEAUTIFY_FROM_FILE_LONG:
                            fromFile = true;
                            break;
                        case MAIN_OPTION_QUIET_LONG:
                            quiet = true;
                            break;
                        case MAIN_OPTION_PEDANTIC_LONG:
                            pedantic = true;
                            break;
                        default:
                            System.out.println("- Unknown option: '" + arg + "'");
                            break;
                    }
                }
                else
                {
                    for (int i = 1, ii = arg.length(); i < ii; i += 1)
                    {
                        char opt = arg.charAt(i);
                        switch (opt)
                        {
                            case MAIN_OPTION_BEAUTIFY:
                                op = Operation.beautify;
                                break;
                            case MAIN_OPTION_CHECK:
                                op = Operation.XMLCheck;
                                break;
                            case MAIN_OPTION_VERIFY_BEAUTY:
                                op = Operation.verifyBeauty;
                                break;
                            case MAIN_OPTION_BEAUTIFY_FROM_FILE:
                                fromFile = true;
                                break;
                            case MAIN_OPTION_QUIET:
                                quiet = true;
                                break;
                            default:
                                System.out.println("- Unknown single char option: '"+opt+"' in '" + arg + "'");
                                break;
                        }
                    }
                }
            }
            else 
            {
                params.add(arg);
            }
        }
        if (params.size() == 0)
        {
            System.out.println("\n--- XML beautifier started ---\n");
            System.out.println("Usage: beautifyXML [-b|-c|-v|-q|-f] <space separated list of files or directories or filelists> ");
            System.out.println();
            System.out.println("-" + MAIN_OPTION_CHECK + " " + MAIN_OPTION_CHECK_LONG
                + "\tOnly checks xml files structure (well-formedness).");
            System.out.println("-" + MAIN_OPTION_BEAUTIFY + " " + MAIN_OPTION_BEAUTIFY_LONG
                + "\tExecutes xml beautifing. The default mode.");
            System.out.println("-" + MAIN_OPTION_VERIFY_BEAUTY + " " + MAIN_OPTION_VERIFY_BEAUTY_LONG
                + "\tCheck beautifing.");
            System.out.println("-" + MAIN_OPTION_QUIET + " " + MAIN_OPTION_QUIET_LONG
                + "\tQuiet mode.");
            System.out.println("   " + MAIN_OPTION_PEDANTIC_LONG
                + "\tPedantic mode.");
            System.out.println("-" + MAIN_OPTION_BEAUTIFY_FROM_FILE + " " + MAIN_OPTION_BEAUTIFY_FROM_FILE_LONG
                + "\tTake file names from a filelist.");
            System.out.println("file_name\tSets single xml file for beautifing. Note: use ONLY absolute path here.");
            System.out.println("directory_path\tSets root directory. Directory will be expanded and all inner xml files will be beautified.");
            System.out.println("\n--- XML beautifier finished ---\n");
            System.exit(1);
        }
        if (!quiet) 
        {
            System.out.println("\n--- XML beautifier started ---");
        }
        for(String param : params)
        {
            File file = new File(param);
            List<File> files = new ArrayList<>();
            if(fromFile)
            {
                try
                {
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
                        line = line.trim();
                        if (line.length() != 0)
                        {
                            files.add(new File(line));
                        }
                    }
                }
                catch (Exception ex)
                {
                    System.out.println("Can't process filelist " + param);
                }
            }
            else
            {
                files = expandFileTree(file);
            }
            if (files.isEmpty())
                System.out.println("No files for processing.");
            else
                for (File f : files)
                {
                    XMLByPathBeautify(op, f, quiet, pedantic);
                }
        }
        if (!quiet)
            System.out.println("\n--- XML beautifier finished ---\n");
        
        if (resultCode != 0) 
            System.exit(resultCode);
    }
}
