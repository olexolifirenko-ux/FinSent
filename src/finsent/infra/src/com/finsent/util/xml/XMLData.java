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
 */


package com.finsent.util.xml;

import com.finsent.directory.DirectoryException;
import com.finsent.directory.DirectorySystem;
import com.finsent.directory.IDirectoryResource;
import com.finsent.directory.SimpleDirectoryAccess;
import com.finsent.properties.PropertyUtils;
import com.finsent.util.*;
import com.finsent.util.diagnostics.ThreadUtils;
import com.finsent.util.xml.XMLDefaultErrorHandler.EventType;
import com.finsent.util.xml.dtd.XMLDtdEntityResolver;
import com.finsent.util.xml.parser.XMLParser;
import org.w3c.dom.*;
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.TreeWalker;
import org.xml.sax.*;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.DefaultHandler2;
import org.xml.sax.ext.LexicalHandler;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.parsers.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.finsent.util.GlobalSystem.error;
import static com.finsent.util.GlobalSystem.warning;
import static com.finsent.util.UtilityFunctions.isEmpty;
import static com.finsent.util.function.FunctionWithException.function;
import static java.lang.String.format;

/**
 * This class is used for straightforward processing
 * of XML structures that could be read from XML files, directory
 * service resource or created on the fly
 * <p>
 *  NOTE: If you need to call <code>getAttributeXXXValue()</code>,
 *  don't call <code>isAttributeDefined()</code> before.
 *  It leads to twice as many hash table lookups. instead, do:
 *
 *          value = getAttributeXXXValue(PROP, null);
 *          if (value != null)
 *              ...
 *  With primitive types one could do:
 *  boolean value = config.getAttributeBooleanValue(key, false);
 *          if (false == value)
 *              ...
 *
 * @author  Alexander Prozor
 * @author Andrey Aleshnikov
 */
public class XMLData implements Cloneable, IDirectoryResource, ILoadedFromFile, Serializable
{

// Class constants

    private static final long serialVersionUID = 1L;

    private static final boolean LogAccessForAllAttributes_;
    private static final String[] DumpStackForAttributes_;

    // AA: these are supposed to be immutable
    private static final SAXParserFactory NonValidatingSAXParserFactory_;
    private static final DocumentBuilderFactory ValidatingBuilderFactory_;
    private static final DocumentBuilderFactory NonValidatingBuilderFactory_;
    private static final DOMImplementation DOMImplementation_;
    private static final ThreadLocal<ArrayList<SAXParser>> NonValidatingSAXParserThreadLocal_ = ThreadLocal.withInitial(() -> new ArrayList<>(2));
    private static final ThreadLocal<ArrayList<DocumentBuilder>> ValidatingBuilderThreadLocal_ = ThreadLocal.withInitial(() -> new ArrayList<>(1));
    private static final ThreadLocal<ArrayList<DocumentBuilder>> NonValidatingBuilderThreadLocal_ = ThreadLocal.withInitial(() -> new ArrayList<>(1));

    public static final String DEFAULT_DELIMETERS = PropertyUtils.DEFAULT_DELIMETERS;

    /**
    * Empty XML document
    */
    public static final XMLData EMPTY_DOCUMENT;
    public static final String DOCUMENT_HEADER;

    // EZ080400: Let's have constant for this
    public static final String EMPTY_DOCUMENT_TAG = "_";

    // Formatting levels for pretty-output parser
    public final static int FORMATTING_LEVEL_NONE = 0;
    public final static int FORMATTING_LEVEL_ONE_LINE = 1;
    public final static int FORMATTING_LEVEL_MULTI_LINE = 2;
    public final static int FORMATTING_LEVEL_SMART = 3;

    // Environment variables prefix and suffix for resolving "on the fly"
    // for example abcd${ENV_VARIABLE}efg
    public final static String ENV_VARIABLE_PREFIX = "${";
    public final static String ENV_VARIABLE_SUFFIX = "}";

    public static final String ELEMENT_INCLUDE = "Include";
    public static final String ATTRIBUTE_RESOURCE = "resource";
    public static final String ATTRIBUTE_DATAPATH = "datapath";

    public static final String ELEMENT_PARAMETER    = "Parameter";
    public static final String ATTRIBUTE_KEY        = "key";
    public static final String ATTRIBUTE_VALUE      = "value";

    // AA: We have a convention of storing XMLs without encoding declaration.
    // XML spec requires such XMLs to use UTF-8 or BOM-prefixed UTF-16 encoding:
    // "...parsed entities which are stored in an encoding other than
    // UTF-8 or UTF-16 must begin with a text declaration ... containing an
    // encoding declaration"
    // "it is a fatal error ... for an entity which begins with neither a Byte
    // Order Mark nor an encoding declaration to use an encoding other than
    // UTF-8"
    // (https://www.w3.org/TR/2008/REC-xml-20081126/, 4.3.3 Character Encoding
    // in Entities)

    public static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;

    public static final String FULL_PATH_DELIMITER = "//";

    private static final IResourceResolver DEFAULT_RESOLVER = new DefaultResolver();

    private static final String EOL = System.getProperty("line.separator");
    private static final String NO_SYSTEM_ID = ""; // AA: new instance is required to guarantee uniqueness

    private static final Node[] EMPTY_NODE_ARRAY = new Node[0];

    static
    {
        LogAccessForAllAttributes_ = Boolean.getBoolean("XMLData.logAccess");

        String dumpStackForAttributes = System.getProperty("XMLData.dumpStackForAttributes");
        if(dumpStackForAttributes == null)
        {
            DumpStackForAttributes_ = null;
        }
        else
        {
            String[] names = dumpStackForAttributes.split(",");
            Arrays.sort(names);
            if (names.length > 0)
                DumpStackForAttributes_ = names;
            else
                DumpStackForAttributes_ = null;
        }
        try
        {
            NonValidatingSAXParserFactory_ = XMLImplemenation.createSAXParserFactory();
            NonValidatingSAXParserFactory_.setValidating(false);
            ValidatingBuilderFactory_ = XMLImplemenation.createDocumentBuilderFactory();
            ValidatingBuilderFactory_.setValidating(true);
            NonValidatingBuilderFactory_ = XMLImplemenation.createDocumentBuilderFactory();
            NonValidatingBuilderFactory_.setValidating(false);
            DOMImplementation_ = ValidatingBuilderFactory_.newDocumentBuilder().getDOMImplementation();
        }
        catch (ParserConfigurationException e)
        {
            throw new RuntimeException(e);
        }
        EMPTY_DOCUMENT = new XMLData();
        DOCUMENT_HEADER = appendDocHeader(new StringBuilder(), EMPTY_DOCUMENT).toString();
    }

    private static SAXParser newInitialSaxParser()
    {
        try
        {
            return NonValidatingSAXParserFactory_.newSAXParser();
        }
        catch (ParserConfigurationException | SAXException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static DocumentBuilder newInitialNonValidatingDocumentBuilder()
    {
        try
        {
            return NonValidatingBuilderFactory_.newDocumentBuilder();
        }
        catch (ParserConfigurationException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static DocumentBuilder newInitialValidatingDocumentBuilder()
    {
        try
        {
            return ValidatingBuilderFactory_.newDocumentBuilder();
        }
        catch (ParserConfigurationException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static <T> T acquare(ThreadLocal<? extends List<T>> tl, Supplier<T> factory)
    {
        List<T> list = tl.get();
        int size = list.size();
        if (size > 0)
        {
            return list.remove(size - 1);
        }
        return factory.get();
    }

    private static <T> void release(ThreadLocal<? extends List<T>> tl, T value)
    {
        tl.get().add(value);
    }

//  Instance variables

    /**
      * Url of xml file
      * @author  Alexander Prozor
      */
     protected String urlConfigInfoFile_ = NO_SYSTEM_ID;

     /**
      * save() method uses this to know how to store XMLData
      * @author Alexander Dolgin
      */
     protected boolean wasReadByResourceLoader_ = false;

     /**
      * The root element of this XMLData.
      * Can contains Element or Document class
      * @author  Alexander Prozor
      * @version 1.0
      */
     protected Element xmlNode_;

     /**
      * Optional source for default arguments values.
      * @author VS
      */
     protected XMLData defaultData_;

     /**
      * Allows to enable/disable using of TABs
      * while print indents in xml. By default - true.
      * @author Max Bezsaly
      */
     protected boolean useTabs_ = true; // TODO AA: this seems to be dead, remove

     /**
      * Allows to enable/disable resolving env and system variables "on fly".
      * By default - true.
      * @author Vladimir Gadyatskiy
      */
     protected boolean resolveVariables_ = true;

    /**
     * DOM API provides no guarantees about attributes order, so we preserve it
     * in user data attached to attribute nodes during loading. When
     * saving/parsing we enforce that order. Note that some attributes may have
     * been deleted/added in runtime, we handle it this way: all attributes
     * having attached order are reported/written first (relative order is
     * according to the load order), then other attributes are reported/written
     * in the same order as they were added.
     *
     * In case an attribute was loaded, removed and then re-added
     * programmatically it is considered to be added programmatically, not
     * loaded.
     *
     * Attributes added directly via DOM, bypassing {@code setAttribute(...)}
     * methods, have no such info, so we report them sorted by their names.
     *
     * The values are 0-based.
     *
     * @see com.finsent.util.xml.AttrsIterator
     * @author Andrey Aleshnikov
     */
    private static final String ATTR_INDEX = "com.alex.util.xml.attrIndex";
    private static final String ATTR_INDEX_MAX = "com.alex.util.xml.attrIndexMax";
    private static final String ELEM_POSITION = "com.alex.util.xml.elemPosition";

    /**
     * To store resource path that this document was loaded from. It's needed for
     * at least verbose exception messages.
     *
     * We can't rely on {@link #urlConfigInfoFile_} field since a lot of code
     * created derived XMLData instances by getting underlying {@code org.w3c.dom.Element}
     * and then just calling {@link #XMLData(Element)}
     *
     * We also can't rely on {@link Document#getDocumentURI()} at least
     * because it is a URI representation of the original resource path, which can
     * look quite confusing for users/support/etc when used in error messages.
     *
     * @author Andrey Aleshnikov
     */
    private static final String RESOURCE_PATH = "com.alex.util.xml.resourcePath";

// Constructors

    /**
     * Creates empty object
     * NOTE if you use this constructor you will have to
     * use setDocumentRoot for this document later
     * @author  Alexander Prozor
     * @version 1.0
     */
    public XMLData()
    {
        setDocumentRoot(createEmptyRootElement());
    }

    /**
     *  Parses xml reading it from specified reader
     *  @param reader - access to character stream with xml information.
     *  @author Andrei Petrov
     *  @author Max Bezsaly
     */
    public XMLData(Reader reader)
    {
        this(reader, false, null);
    }

    protected XMLData(Reader reader, boolean validate, ErrorHandler errorHandler)
    {
        // pending ap - check validation needed
        try
        {
            xmlNode_ = parseDocument(UtilityFunctions.stringFromReader(reader), null, validate, errorHandler, false);
        }
        // A XMLBadDataException means that some parse
        // error occurred. Just create an empty document.
        catch (XMLBadDataException ex)
        {
            logParseError(ex);
            xmlNode_ = createEmptyRootElement();
        }
        catch (IOException e)
        {
            error().write(new Object[]
                    {
                    "Couldn't parse document from reader; ",
                    e.getMessage()
                    });
            xmlNode_ = createEmptyRootElement();
        }
    }

    /**
     *  Parses xml reading it from specified reader.
     *  @throws XMLBadDataException if reader contains wrong xml information.
     *  @param dummy  - just for distinguish constructors XMLData(Reader) - one
     *                  of them throws exception, other no. Can be true or false
     *                  - method does not read it.
     *  @param quiet  - whether it is acceptable to log errors/warnings.
     *
     *  @author Konstantin Matokhin.
     *  @author Andrey Aleshnikov
     */
    protected XMLData(String dataString, ErrorHandler errorHandler, boolean quiet, boolean dummy) throws XMLBadDataException
    {
        // pending ap - check validation needed
        xmlNode_ = parseDocument(dataString, null, false, errorHandler, quiet);
    }


    /**
     * Creates configurator with information from configuration file
     * and parses this file
     * @param urlConfigInfoFile - Url of the xml file.
     * Examples: absolute path "c:/temp/bootstrap.xml",  relative path "../cfg/BackendBootstrap.xml",  directory path "/ElTrader/TMS/Config/processes.xml"
     *
     * @author  Alexander Prozor
     * @version 1.0
     */
    public XMLData(String urlConfigInfoFile)
    {
        this(urlConfigInfoFile, null);
    }

    /**
     * Creates XMLData with information from XML element
     * @param branchRoot - element, which will be the root of
     *        new XMLData.
     * @author  Alexander Prozor
     */
    public XMLData(@Nonnull Element branchRoot)
    {
        if (null == branchRoot)
            throw new NullPointerException("document root cannot be null");
        // AA: the fact that the node implements org.w3c.dom.Element
        // does not necessary mean it is an Element node
        if (Node.ELEMENT_NODE != branchRoot.getNodeType())
            throw new IllegalArgumentException("document root must be of org.w3c.dom.Node.ELEMENT_NODE"
                    + " (" + Node.ELEMENT_NODE + ") type"
                    + ", but is has type = " + branchRoot.getNodeType());
        xmlNode_ = branchRoot;
    }

    /**
     * Shallow copy.
     * @author  Alexander Prozor
     */
    public XMLData(XMLData data)
    {
        this(data.getDocumentRoot());
    }

    /**
     * @param urlConfigInfoFile - url of the xml file.
     * @param errorHandler - error handler, see ErrorHandler interface.
     * @see ErrorHandler
     * @author  Alexander Prozor
     */
    public XMLData(String urlConfigInfoFile, ErrorHandler errorHandler)
    {
        if (urlConfigInfoFile == null)
        {
            urlConfigInfoFile = UtilityFunctions.EMPTY_STRING;
        }
        urlConfigInfoFile_ = urlConfigInfoFile;

        byte[] dataBytes = null;
        String resourceLoaderErrorMessage = null;
        //AD 09/2003 - first try to load by ResourceLoader...
        try
        {
            dataBytes = ResourceLoader.load(adjustForIRURISyntax(urlConfigInfoFile_));
            wasReadByResourceLoader_ = true;
        }
        catch (ResourceLoaderException ex)
        {
            resourceLoaderErrorMessage = ex.getMessage();
        }

        if (!wasReadByResourceLoader_) //AA: get rid of this
        {
            // directory is not initialized or resource not found
            File dataFile = new File(urlConfigInfoFile);
            if (!(dataFile).exists())
            {
                // AA: should we just throw FileNotFoundException ?
                error().write("Failed to load '" +
                    urlConfigInfoFile + "' with ResourceLoader (error message: '" + resourceLoaderErrorMessage + "').");
                xmlNode_ = createEmptyRootElement();
                return;
            }
            try
            {
                dataBytes = Files.readAllBytes(dataFile.toPath());
            }
            catch (IOException e)
            {
                if (error().isEnabled())
                {
                    StringBuilder error = new StringBuilder("Failed to load '");
                    error.append(urlConfigInfoFile_);
                    error.append("' file (error message: '");
                    error.append(e.getMessage()).append('\'');
                    Throwable cause = e.getCause();
                    if (null != cause && null != cause.getMessage() && !cause.getMessage().isEmpty())
                        error.append(" caused by '").append(cause.getMessage()).append('\'');
                    error.append(").");
                    error().write(error);
                }
                xmlNode_ = createEmptyRootElement();
            }
        }

        try
        {
            // AA: historically our XMLData would end up forcing validate = false
            // when wasReadByResourceLoader_ is true.
            // We have to keep this behavior for backward compatibility as many of
            // our config files (e.g. PortfolioLoaderConfig.xml) have references to non-existent DTD
            // which causes exceptions if validation is enabled.
            boolean validate = !wasReadByResourceLoader_;
            xmlNode_ = parseDocument(null, dataBytes, validate, errorHandler, false);
        }
        // Any SAXExceptions means that some parse
        // error occurred. Just creates an empty document.
        catch (XMLBadDataException ex)
        {
            logParseError(ex);
            xmlNode_ = createEmptyRootElement();
        }
    }

    /**
     * Derived from {@link #XMLData(String, ErrorHandler)}, throws exceptions.
     *
     * @param urlConfigInfoFile - url of the xml file.
     * @param errorHandler - error handler, see ErrorHandler interface.
     * @see ErrorHandler
     * @author Andrey Aleshnikov
     */
    public XMLData(String urlConfigInfoFile,
            ErrorHandler errorHandler,
            boolean dummy)
    throws ResourceLoaderException, XMLBadDataException
    {
        if (urlConfigInfoFile == null)
        {
            urlConfigInfoFile = UtilityFunctions.EMPTY_STRING;
        }
        urlConfigInfoFile_ = urlConfigInfoFile;

        //AD 09/2003 - first try to load by ResourceLoader...
        byte[] dataBytes = ResourceLoader.load(adjustForIRURISyntax(urlConfigInfoFile_));
        wasReadByResourceLoader_ = true;

        // AA: historically our XMLData would end up forcing validate = false
        // when wasReadByResourceLoader_ is true.
        // We have to keep this behavior for backward compatibility as many of
        // our config files (e.g. PortfolioLoaderConfig.xml) have references to non-existent DTD
        // which causes exceptions if validation is enabled.
        boolean validate = !wasReadByResourceLoader_;
        xmlNode_ = parseDocument(null, dataBytes, validate, errorHandler, false);
    }

    /**
     * Creates XMLData with information from specified
     * input stream.
     * @param inputStream - stream that contains xml information.
     * @author  Alexander Prozor
     * @author Andrey Aleshnikov
     */
    public XMLData(InputStream inputStream) throws IOException, XMLBadDataException
    {
        byte[] dataBytes = UtilityFunctions.bytesFromStream(inputStream);
        xmlNode_ = parseDocument(null, dataBytes, false, null, false);
    }


    // Class mutators

    /**
     * Allows to enable/disable using of TABs while printing
     * indents in xml file.
     *
     * @param useTabs boolean - new mode.
     * @author Max Bezsaly
     */
    public void setUseTabs(boolean useTabs)
    {
        useTabs_ = useTabs;
    }

    /**
     * Allows to enable/disable resolving env and system variables "on fly".
     * @author Vladimir Gadyatskiy
     */
    public void setResolveVariables(boolean resolveVariables)
    {
        resolveVariables_ = resolveVariables;
    }


    // Class accessors

    /**
     * Restore element's content from string
     * NOTE if one can't use DTD, I have to use non validating parser
     * @param buffer the String from which XMLData should be created.
     * @return XMLData created from the String.
     * @author Alexander Prozor
     */
    public static XMLData valueOf(String buffer)
    {
        Reader reader = new StringReader(buffer);
        return new XMLData(reader);
    }

    /**
     * The same as usual valueOf - but with exception.
     * @author Konstantin Matokhin
     */
    public static XMLData valueOfWithException(String buffer) throws XMLBadDataException
    {
        return valueOfWithException(buffer, false);
    }

    public static XMLData valueOfWithException(String buffer, boolean quiet) throws XMLBadDataException
    {
        return new XMLData(buffer, null, quiet, false);
    }

    public static XMLData valueOfUrlWithException(String urlConfigInfoFile)
            throws ResourceLoaderException, XMLBadDataException
    {
        return new XMLData(urlConfigInfoFile, null, false);
    }

    /**
     * Creates empty XML data object which has a root node with given name.
     * @author Vadim Hmelik
     * @author Andrey Aleshnikov
     */
    public static XMLData newInstance(String rootElementName)
    {
        return new XMLData(createDocument(rootElementName).getDocumentElement());
    }

    /**
     * Loads XML from full path represented as
     * PATH_TO_XML//DATA_PATH
     *
     * @param fullPath full path to XML section
     * @return XML
     */
    public static XMLData fromFullPath(String fullPath)
    {
        int protocolDelimiterIndex = fullPath.indexOf(ResourceLoader.PROTOCOL_PATH_DELIMITER);
        int index = fullPath.indexOf(FULL_PATH_DELIMITER, protocolDelimiterIndex > -1? protocolDelimiterIndex + ResourceLoader.PROTOCOL_PATH_DELIMITER.length() : 0);
        String[] tokens = index > 0 ? new String[]{fullPath.substring(0, index), fullPath.substring(index + FULL_PATH_DELIMITER.length())} : new String[]{fullPath};

        XMLData data = new XMLData(tokens[0]);
        if (tokens.length > 1)
        {
            data = data.getDocumentPart(new XMLDataPath(tokens[1]), false);
        }

        return data;
    }

    /**
     * Allows to know if using of TABs while printing
     * indents in xml is enabled.
     *
     * @return boolean - true if using of TABs while printing
     *                   indents in xml is enabled, else - false.
     * @author Max Bezsaly
     */
    public boolean isUseTabs()
    {
        return useTabs_;
    }

    /**
     * Allows to enable/disable resolving env and system variables "on fly".
     * @author Vladimir Gadyatskiy
     */
    public boolean isResolveVariables()
    {
        return resolveVariables_;
    }

    /**
     * Converts XMLData to the single xml string
     * @return String - single line (unformatted) xml string.
     * @author Alexander Prozor
     */
    public String stringValue()
    {
        return stringValue(false);
    }

    /**
     * Converts XMLData to the xml string
     * @param prettyOutput - if true, output string will be
     *                               formatted (multiline), else
     *                               will be single line
     * @return String - single line (unformatted) xml string.
     * @author Alexander Prozor
     * @author MB
     */
    public String stringValue(boolean prettyOutput)
    {
        return stringValue(prettyOutput, true); //true - by default with <?xml version='1.0'?>
    }

   // Instance mutators

    /**
     * Sets value of node.
     * @author Sergey Bulakh
     */
    public void setNodeValue(String value)
    {
        XMLDataUtil.setNodeValue(xmlNode_, value);
    }

    /**
     * Create a clone using new systemID.
     *
     * @see #getSystemDtdID()
     * @author Andrey Aleshnikov
     */
    public XMLData cloneUsingCustomSystemDtdID(String systemID)
    {
        XMLData result = clone();
        setSystemDtdID(systemID);
        return result;
    }

    private final void writeObject(ObjectOutputStream out) throws IOException
    { writeExternal(out); }

    public final void writeExternal(ObjectOutput out) throws IOException
    {
        UtilityFunctions.writeString(out, urlConfigInfoFile_);
        // AA: XMLData used to have wasReadFromDirectory_ field,
        // so for inter-operability with older versions we still have to write it
        out.writeBoolean(false);
        out.writeBoolean(wasReadByResourceLoader_);
        // AA: XMLData used to have wasReadEncrypted_ and shouldReturnEncryptedData_ fields,
        // so for inter-operability with older versions we still have to write them
        out.writeBoolean(false);
        out.writeBoolean(false);
        //protected transient ErrorHandler errorHandler_;
        out.writeObject(defaultData_);
        // AA: XMLData used to have systemID, publicID and internalSubset fields,
        // so for inter-operability with older versions we still have to write them
        UtilityFunctions.writeString(out, "");
        UtilityFunctions.writeString(out, "");
        UtilityFunctions.writeString(out, "");
        out.writeBoolean(useTabs_);
        out.writeBoolean(resolveVariables_);
        UtilityFunctions.writeString(out, stringValue());
    }

    private final void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException
    { readExternal(in); }

    public final void readExternal(ObjectInput in) throws IOException, ClassNotFoundException
    {
        StringBuilder buf = new StringBuilder();
        urlConfigInfoFile_ = UtilityFunctions.readString(in, buf);
        // AA: XMLData used to have wasReadFromDirectory_ field,
        // so for inter-operability with older versions we still have to read it
        in.readBoolean();
        wasReadByResourceLoader_ = in.readBoolean();
        // AA: XMLData used to have wasReadEncrypted_ and shouldReturnEncryptedData_ fields,
        // so for inter-operability with older versions we still have to read them
        in.readBoolean();
        in.readBoolean();
        //protected transient ErrorHandler errorHandler_;
        defaultData_ = (XMLData)in.readObject();
        // AA: XMLData used to have systemID, publicID and internalSubset fields,
        // so for inter-operability with older versions we still have to read them
        UtilityFunctions.readString(in, buf);
        UtilityFunctions.readString(in, buf);
        UtilityFunctions.readString(in, buf);
        useTabs_ = in.readBoolean();
        resolveVariables_ = in.readBoolean();
        String nodeStringData = UtilityFunctions.readString(in, buf);
        try
        {
            xmlNode_ = parseDocument(nodeStringData, null, true, null, false);
        }
        // An XMLBadDataException means that some parse
        // error occurred. Just create an empty document.
        catch (XMLBadDataException ex)
        {
            logParseError(ex);
            xmlNode_ = createEmptyRootElement();
        }
    }

    /**
     * @author Andrey Aleshnikov
     */
    protected static Document createDocument(String rootElementName)
    {
        return DOMImplementation_.createDocument(null, rootElementName, null);
    }

    /*
     * Creates parser and proceeds document
     * @param validate perform validation or not
     * @exception SAXException if wrong data.
     * @return document root element.
     */
    protected Element parseDocument(String dataString,
            byte[] dataBytes,
            boolean validate,
            ErrorHandler errorHandler,
            boolean quiet)
            throws XMLBadDataException
    {
        if (null == dataString && null == dataBytes)
        {
            throw new NullPointerException("either dataString or dataBytes should be not null");
        }

        InputSource input = createInputSource(dataString, dataBytes, urlConfigInfoFile_);
        if (!validateIfNoSystemId()) // AA: this should probably be removed
        {
//            boolean logValidationWarning = false;
            try
            {
                String inputSystemID = input.getSystemId();
                if (inputSystemID != null && NO_SYSTEM_ID != inputSystemID)
                {
                    new java.net.URL(inputSystemID);
                }
//                else
//                {
//                    // AA: we know for sure that NO_SYSTEM_ID == systemID will cause MalformedURLException
//                    // on attempt to create new java.net.URL(systemID), so let's not waste resiurces doing that
//                    validate = false;
//                    logValidationWarning = true;
//                }
            }
            catch(IOException ex)
            {
                // SB:03112001
                validate = false;
//                logValidationWarning = true;
            }
//            if (logValidationWarning)
//            {
//                /* SB:03202001 according to DK1233_1
//            warning().write(new Object[]
//            {
//                "Validation will not be peformed for this document ",
//                urlConfigInfoFile_
//            });*/
//            }
        }

        Element documentElement;
        XMLDefaultErrorHandler defaultSAXPreParseErrorHandler = null;
        XMLDefaultErrorHandler defaultErrorHandler = null;
        try
        {
            final List<AttrInfo[]> attrsOrderInfo = new ArrayList<>();
            final List<Position> elemPositions = new ArrayList<>();
            EntityResolver entityResolver = validate ? new EntityResolverCachingProxy(createEntityResolver()) : new DummyEntityResolver();
            DTDDetector dtdDetector = validate ? new DTDDetector() : null;
            defaultSAXPreParseErrorHandler = new XMLFatalErrorHandler();
            ErrorHandler saxPreParseErrorHandler = errorHandler == null
                    ? defaultSAXPreParseErrorHandler
                    : new ErrorHandlerMultiplexor(defaultSAXPreParseErrorHandler, errorHandler);
            preParseUsingSAX(input, entityResolver, null, dtdDetector, saxPreParseErrorHandler, attrsOrderInfo, elemPositions);
            validate = validate && dtdDetector.foundDTD_;

            DocumentBuilder builder =
                validate ?
                    acquare(ValidatingBuilderThreadLocal_, XMLData::newInitialValidatingDocumentBuilder) :
                    acquare(NonValidatingBuilderThreadLocal_, XMLData::newInitialNonValidatingDocumentBuilder);
            try
            {
                defaultErrorHandler = new XMLDefaultErrorHandler();
                builder.setErrorHandler(errorHandler == null
                                        ? defaultErrorHandler
                                        : new ErrorHandlerMultiplexor(defaultErrorHandler, errorHandler));
                builder.setEntityResolver(entityResolver);

                Document doc = builder.parse(createInputSource(dataString, dataBytes, urlConfigInfoFile_));
                doc.normalize();
                documentElement = doc.getDocumentElement();
//                internalizeStrings(documentElement);
                saveAttributesOrder(doc, attrsOrderInfo, elemPositions);
                ResourcePathUserData.Instance_.set(doc, urlConfigInfoFile_);
            }
            finally
            {
                release(validate ? ValidatingBuilderThreadLocal_ : NonValidatingBuilderThreadLocal_, builder);
            }
        }
        catch (Exception ex)
        {
            if (null == defaultErrorHandler) // SAX pre-parsing failed
                throw new XMLBadDataException(
                        composeParseIssueDescription(dataString, dataBytes, true, defaultSAXPreParseErrorHandler.getParsingIssues()), ex);
            else
                throw new XMLBadDataException(
                        composeParseIssueDescription(dataString, dataBytes, true, defaultErrorHandler.getParsingIssues()), ex);
        }
        if (!quiet && !defaultErrorHandler.getParsingIssues().isEmpty())
            warning().write(
                    composeParseIssueDescription(dataString, dataBytes, false, defaultErrorHandler.getParsingIssues()));

        return documentElement;
    }

    private String composeParseIssueDescription(String dataString,
            byte[] dataBytes,
            boolean fatal,
            List<Pair<EventType, SAXParseException>> issues) //TODO move?
    {
        // Non-fatal issues occurred when parsing "directory://file.xml" XML resource: warning at line 1, column 3: "...".
        //    OR
        // Cannot parse "directory://file.xml" XML resource: fatal error at line 1, column 3: "...".
        ///   OR
        // Cannot parse "directory://file.xml" XML resource:
        //    OR
        // Cannot parse XML string/bytes data:
        //   - warning at line 1, column 3: "...";
        //   - error at column 5: "...";
        //   - warning: "...";
        //   - fatal error at line 3, column 6: "...".
        //           FOR string/bytes data
        // ---malformed XML document contents begin---
        // ...
        // ---malformed XML document contents end---
        StringBuilder message = new StringBuilder();
        message.append(fatal
                ? "Cannot parse "
                : "Non-fatal issues found when parsing ");
        if (null == urlConfigInfoFile_ || urlConfigInfoFile_.isEmpty())
            message.append("XML string/bytes data");
        else
            message.append('"').append(urlConfigInfoFile_).append("\" XML resource");
        if (!issues.isEmpty()) // non-SAXParseException occurred
        {
            message.append(':').append(1 == issues.size() ? " " : GlobalDefs.EOL);
            boolean first = true;
            for (Pair<EventType, SAXParseException> issue : issues)
            {
                if (!first)
                    message.append(";").append(GlobalDefs.EOL);
                else
                    first = false;
                if (1 != issues.size())
                    message.append("\t- ");
                SAXParseException parseException = issue.getSecond();
                switch (issue.getFirst())
                {
                    case WARNING: message.append("warning "); break;
                    case ERROR: message.append("error "); break;
                    case FATAL_ERROR: message.append("fatal error ");
                }
                if (-1 != parseException.getLineNumber() || -1 != parseException.getColumnNumber())
                {
                    message.append("at ");
                    if (-1 != parseException.getLineNumber())
                        message.append("line ").append(parseException.getLineNumber());
                    if (-1 != parseException.getColumnNumber())
                    {
                        if (-1 != parseException.getLineNumber()) message.append(", ");
                        message.append("column ").append(parseException.getColumnNumber());
                    }
                }
                message.append(": \"").append(parseException.getMessage()).append('"');
            }
        }
        message.append('.');
        if (isEmpty(urlConfigInfoFile_))
        {
            String debugSource = dataString;
            if (null == debugSource) debugSource = UtilityFunctions.bytesToString(dataBytes, StandardCharsets.ISO_8859_1);
            message.append(GlobalDefs.EOL).append("---malformed XML document contents begin---").append(GlobalDefs.EOL);
            message.append(debugSource);
            message.append(GlobalDefs.EOL).append("---malformed XML document contents end---");
        }
        return message.toString();
    }

    protected boolean validateIfNoSystemId()
    {
        return false;
    }

    /**
     * @author Andrey Aleshnikov
     */
    protected void preParseUsingSAX(InputSource input,
            EntityResolver entityResolver,
            final DeclHandler declHandler,
            final LexicalHandler lexicalHandler,
            ErrorHandler saxPreParseErrorHandler,
            final List<AttrInfo[]> attrsOrderInfoOUT,
            final List<Position> elemPositionsOUT)
                    throws ParserConfigurationException, SAXException, IOException
    {
        SAXParser saxParser = acquare(NonValidatingSAXParserThreadLocal_, XMLData::newInitialSaxParser);
        try
        {
            XMLReader xmlReader = saxParser.getXMLReader();

            xmlReader.setErrorHandler(saxPreParseErrorHandler);
            ContentHandler contentHandler = new DefaultHandler2()
            {
                private Locator locator_;
                private int line_;
                private int column_;
                private boolean root_;

                @Override
                public void setDocumentLocator(Locator locator)
                {
                    locator_ = locator;
                    storeLastPosition();
                }

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attrs) throws SAXException
                {
                    AttrInfo[] attrsInfo = new AttrInfo[attrs.getLength()];
                    for (int i = 0; i < attrs.getLength(); i++)
                    {
                        attrsInfo[i] = new AttrInfo(attrs.getURI(i), attrs.getLocalName(i), attrs.getQName(i));
                    }
                    attrsOrderInfoOUT.add(attrsInfo);
                    int line = line_;
                    int column = column_;
                    storeLastPosition();
                    // we can't definitely know whether root element is multiline
                    // because SAX parser does not call any callback when it encounters
                    // the opening angle bracket of the root element. that's why we pessimistically
                    // assume that root element is never multiline.
                    // as for non-root element we compare the line of the opening angle bracket
                    // (which was encountered when previous node is processed)
                    // with the line of the closing angle bracket.
                    boolean multiline = !root_ && line != line_;
                    elemPositionsOUT.add(new Position(line, column, multiline));
                    root_ = false;
                }

                void storeLastPosition()
                {
                    if (locator_ != null)
                    {
                        line_ = locator_.getLineNumber();
                        column_ = locator_.getColumnNumber();
                    }
                }

                @Override
                public void notationDecl(String name, String publicId, String systemId) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void unparsedEntityDecl(String name, String publicId, String systemId, String notationName) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void startDocument() throws SAXException
                {
                    storeLastPosition();
                    root_ = true;
                }

                @Override
                public void endDocument() throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void startPrefixMapping(String prefix, String uri) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void endPrefixMapping(String prefix) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void endElement(String uri, String localName, String qName) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void characters(char[] ch, int start, int length) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void processingInstruction(String target, String data) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void skippedEntity(String name) throws SAXException
                {
                    storeLastPosition();
                }

                @Override
                public void startCDATA() throws SAXException
                {
                    if (lexicalHandler != null)
                    {
                        lexicalHandler.startCDATA();
                    }
                    storeLastPosition();
                }

                @Override
                public void endCDATA() throws SAXException
                {
                    if (lexicalHandler != null)
                    {
                        lexicalHandler.endCDATA();
                    }
                    storeLastPosition();
                }

                @Override
                public void startDTD(String name, String publicId, String systemId) throws SAXException
                {
                    if (lexicalHandler != null)
                    {
                        lexicalHandler.startDTD(name, publicId, systemId);
                    }
                    storeLastPosition();
                }

                @Override
                public void endDTD() throws SAXException
                {
                    if (lexicalHandler != null)
                    {
                        lexicalHandler.endDTD();
                    }
                    storeLastPosition();
                }

                @Override
                public void startEntity(String name) throws SAXException
                {
                    if (lexicalHandler != null)
                    {
                        lexicalHandler.startEntity(name);
                    }
                    storeLastPosition();
                }

                @Override
                public void endEntity(String name) throws SAXException
                {
                    if (lexicalHandler != null)
                    {
                        lexicalHandler.endEntity(name);
                    }
                    storeLastPosition();
                }

                @Override
                public void comment(char[] ch, int start, int length) throws SAXException
                {
                    if (lexicalHandler != null)
                    {
                        lexicalHandler.comment(ch, start, length);
                    }
                    storeLastPosition();
                }

                @Override
                public void attributeDecl(String eName, String aName, String type, String mode, String value) throws SAXException
                {
                    if (declHandler != null)
                    {
                        declHandler.attributeDecl(eName, aName, type, mode, value);
                    }
                    storeLastPosition();
                }

                @Override
                public void elementDecl(String name, String model) throws SAXException
                {
                    if (declHandler != null)
                    {
                        declHandler.elementDecl(name, model);
                    }
                    storeLastPosition();
                }

                @Override
                public void externalEntityDecl(String name, String publicId, String systemId) throws SAXException
                {
                    if (declHandler != null)
                    {
                        declHandler.externalEntityDecl(name, publicId, systemId);
                    }
                    storeLastPosition();
                }

                @Override
                public void internalEntityDecl(String name, String value) throws SAXException
                {
                    if (declHandler != null)
                    {
                        declHandler.internalEntityDecl(name, value);
                    }
                    storeLastPosition();
                }
            };
            xmlReader.setProperty(XMLDataUtil.SAX_PROPERTY_DECLARATION_HANDLER, contentHandler);
            xmlReader.setProperty(XMLDataUtil.SAX_PROPERTY_LEXICAL_HANDLER, contentHandler);
            xmlReader.setContentHandler(contentHandler);
            xmlReader.setEntityResolver(entityResolver);
            xmlReader.parse(input);
        }
        finally
        {
            release(NonValidatingSAXParserThreadLocal_, saxParser);
        }
    }

    /**
     * @see #ATTR_INDEX
     * @author Andrey Aleshnikov
     */
    protected void setAttributeValueImpl(String attributeName, String value)
    {
        setAttributeValueImpl(xmlNode_, attributeName, value);
    }

    public static void setAttributeValueImpl(Element el, String attributeName, String value)
    {
        Attr attr = el.getAttributeNode(attributeName);
        if (null != attr)
        {
            attr.setValue(value);
        }
        else // new attribute - need to assign order
        {
            try
            {
                el.setAttribute(attributeName, value);
            }
            catch (DOMException ex)
            {
                // Currently for invalid attr name the exception will have this msg:
                //   INVALID_CHARACTER_ERR: An invalid or illegal XML character is specified.
                // I.e. neither attr name, nor attr value is metioned.
                StringBuilder buf = new StringBuilder();
                buf.append("Could not set attribute \"").append(attributeName);
                buf.append("\" to value \"").append(value);
                buf.append("\". Either name or value is not allowed by XML spec.");
                throw new IllegalArgumentException(buf.toString(), ex);
            }
            Integer maxAttrIndex = MaxAttrIndexUserData.Instance_.get(el);
            if (null == maxAttrIndex) maxAttrIndex = -1;
            int attrIndex = Math.max(maxAttrIndex + 1, el.getAttributes().getLength() - 1);
            attr = el.getAttributeNode(attributeName);
            AttrIndexUserData.Instance_.set(attr, attrIndex);
            MaxAttrIndexUserData.Instance_.set(el, attrIndex);
        }
    }

    protected EntityResolver createEntityResolver()
    {
        return new XMLDtdEntityResolver();
    }

    /**
     * Creates new element in the current document
     * @param elementName - name of element
     * @return created element.
     * @author  Alexander Prozor
     * @version 1.0
     */
    public
    Element createNewElement(String elementName)
    {
        Document ownerDoc = xmlNode_.getOwnerDocument();
        return ownerDoc.createElement(elementName);
    }

    public CDATASection createCDataSection(String value)
    {
        Document ownerDocument = xmlNode_.getOwnerDocument();
        return ownerDocument.createCDATASection(value);
    }

    /**
     * Saves document
     * @author  Sergey Bulakh
     */
    public void save()
    {
        save(urlConfigInfoFile_);
    }

    /**
     * Saves document with specify resource name
     *
     * @param url a new resource name
     * @author Sergey Bulakh
     * @author Alexander Dolgin
     */
    public void save(String url)
    {
        try
        {
            if (wasReadByResourceLoader_)
            {
                ResourceLoader.save(url, getDirectoryValue());
            }
            else
            {
                saveToFile(url);
            }
        }
        catch (IOException ex)
        {
            error().write(new Object[]
            {
                "Cannot write XMLData to File System with name ",
                url,
                ", ",
                ex.getMessage() != null ? ex.getMessage() : ""
            });
        }
        catch (ResourceLoaderException ex)
        {
            error().write(new Object[]
            {
                "Cannot write XMLData with name ",
                url,
                ", ",
                ex.getMessage() != null ? ex.getMessage() : ""
            });
        }
    }

    /**
     * Saves XMLData to Directory System
     *
     * @param resourceName a name of resource
     * @throws DirectoryException will be thrown in case of any storing error
     * @author Sergey Bulakh
     */
     public void saveToDirectory (String resourceName) throws DirectoryException
     {
         SimpleDirectoryAccess.storeResource(resourceName, this);
     }

    /**
     * Saves XMLData to File System
     *
     * @param url a name of resource
     * @throws IOException will be thrown in case of any storing error
     * @author  Sergey Bulakh
     */
    public void saveToFile (String url) throws IOException
    {
        try (FileOutputStream fos = new FileOutputStream(url))
        {
            fos.write(getDirectoryValue());
        }
    }

    /**
    * Removes child elements with specified name recursively.
    */
    public void removeChildNode(String name)
    {
        removeChildNodeImpl(xmlNode_, name, null);
    }

    public Node removeChild(final @Nonnull XMLData child)
    {
        return xmlNode_.removeChild(child.xmlNode_);
    }

    /**
    * Removes child elements with specified name and specified values of specified attrs recursively.
    * @param name name of node
    * @param attributes pairs of attribute names and values,
    *                            key - attribute name and value - attr value
    */
    public void removeChildNode(String name, Map<String,String> attributes)
    {
        removeChildNodeImpl(xmlNode_, name, attributes);
    }

    /**
     * Removes child elements with specified name and specified values of specified attrs recursively.
    * @author Andrey Aleshnikov
    */
    private static void removeChildNodeImpl(Node root, String name, Map<String,String> attributes)
    {
        Node nextChild = null;
        for (Node child = root.getFirstChild(); null != child; child = nextChild)
        {
            nextChild = child.getNextSibling();
            boolean needRemove = false;
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && ("*".equals(name) || child.getNodeName().equals(name)))
            {
                needRemove = true;
                if (attributes!=null)
                {
                    Iterator<Entry<String,String>> entries = attributes.entrySet().iterator();
                    while (entries.hasNext() && needRemove)
                    {
                        Entry<String,String> entry = entries.next();
                        String currKey = entry.getKey();
                        String value = entry.getValue();
                        Node attr = child.getAttributes().getNamedItem(currKey);
                        if (attr==null || !attr.getNodeValue().equals(value))
                            needRemove = false;
                    }
                }
            }

            if (needRemove)
            {
                root.removeChild(child);
//                if (child instanceof Element)
//                {
//                    try
//                    {
//                        correctUserData(getUserData(root.getOwnerDocument()), getUserData(child.getOwnerDocument()), (Element) child, RunningProfile.isCodeChecked());
//                    }
//                    catch (NoSuchFieldException | IllegalAccessException e)
//                    {
//                        // should be caught by utest that accesses this field
//                        // com.alex.util.xml.XMLData_utest.testUserDataAfterClone
//                    }
//                }
            }
            else // do not recurse into deleted node
                removeChildNodeImpl(child, name, attributes);
        }
    }

    /**
     *  Removes named attribute from XMLData.
     *  @param attributeName - name of attribute
     *  @author Sergey Bulakh.
     *  @see XMLData_utest#attrsOrderShouldBePreservedOnStringRoundtrip_AfterProgrammaticModifications()
     *  @see XMLData_utest#testUserDataAfterRemoveAttribute()
     */
    public void removeAttribute(String attributeName)
    {
        try
        {
            final Attr attributeNode = xmlNode_.getAttributeNode(attributeName);

            xmlNode_.removeAttribute(attributeName);

            // current implementation of XML parser doesn't remove user data when attribute is removed
            if (attributeNode != null && xmlNode_.getOwnerDocument() != null)
            {
                final Map userData = getUserData(xmlNode_.getOwnerDocument());
                if (userData != null && userData.remove(attributeNode) == null && RunningProfile.isCodeChecked())
                {
                    GlobalSystem.getLogFacility().error().write(new IllegalStateException("userData.remove() call is redundant"));
                }
            }
        }
        catch (DOMException ignored)
        {
        }
    }

    /**
     * Sets optional source for default arguments values.
     * @param value - XMLData with default argument values.
     * @author VS
     */
    public final void setDefaultData(XMLData value)
    {
        if (this == value)
            throw new IllegalArgumentException("XMLData cannot be default data of itself");
        if (null != value.defaultData_)
        {
            // check for cycles
            IdentityHashMap<XMLData, Object> allXMLDatas = new IdentityHashMap<>();
            Object dummyValue = new Object();
            allXMLDatas.put(this, dummyValue);
            for (XMLData current = value; null != current; current = current.defaultData_)
                if (null != allXMLDatas.put(current, dummyValue))
                    throw new IllegalArgumentException("cannot use "
                            + UtilityFunctions.defaultToString(value) + " as default data of "
                            + UtilityFunctions.defaultToString(this) + ": cycle found");
        }

        defaultData_ = value;
    }

    static void checkForCycles(XMLData thiz, XMLData other)
    {
        for (XMLData value = other; value != null; value = value.defaultData_)
        {
            if (value == thiz)
            {
                throw new IllegalArgumentException("cannot use "
                        + UtilityFunctions.defaultToString(other) + " as default data of "
                        + UtilityFunctions.defaultToString(thiz) + ": cycle found");
            }
        }
    }

    /**
     * @param newDefaultData new default data
     * @return previous default data
     */
    public final XMLData replaceDefaultData(XMLData newDefaultData)
    {
        XMLData previous = defaultData_;
        checkForCycles(this, newDefaultData);
        defaultData_ = newDefaultData;
        return previous;
    }

    /**
     * @return default data
     */
    public final XMLData getDefaultData()
    {
        return defaultData_;
    }

// sets values

    /**
     * Sets boolean value of attribute
     * @param attributeName - the name of the attribute
     * @param value - new value of attribute.
     * @return boolean - previous state ( if exists)
     * @author Alexander Prozor
     */
    public boolean setAttributeValue(String attributeName, boolean value)
    {
        boolean retValue = getAttributeBooleanValue(attributeName, false);
        setAttributeValueImpl(attributeName, String.valueOf(value));
        return retValue;
    }

    /**
     * Sets int value of attribute
     * @param attributeName - the name of the attribute
     * @param value - new value of attribute.
     * @return previous state ( if exists)
     * @author Alexander Prozor
     */
    public int setAttributeValue(String attributeName, int value)
    {
        int retValue = getAttributeIntegerValue(attributeName, value);
        setAttributeValueImpl(attributeName, String.valueOf(value));
        return retValue;
    }

    /**
     * Sets long value of attribute
     * @param attributeName - the name of the attribute
     * @param value - new value of attribute.
     * @return previous state ( if exists)
     * @author Alexander Prozor
     */
    public long setAttributeValue(String attributeName, long value)
    {
        long retValue = getAttributeLongValue(attributeName, value);
        setAttributeValueImpl(attributeName, String.valueOf(value));
        return retValue;
    }

    /**
     * Sets double value of attribute
     * @param attributeName - the name of the attribute
     * @param value - new value of attribute.
     * @return previous state ( if exists)
     * @author Alexander Prozor
     */
    public double setAttributeValue(String attributeName, double value)
    {
        double retValue = getAttributeDoubleValue(attributeName, value);
        setAttributeValueImpl(attributeName, String.valueOf(value));
        return retValue;
    }

    /**
     * Sets String value of attribute
     * @param attributeName - the name of the attribute
     * @param value - new value of attribute.
     * @return previous state ( if exists)
     * @author Alexander Prozor
     */
    public String setAttributeValue(String attributeName, String value)
    {
        //KM02Jan2001 added this check 'cause if we set null attribute value,
        //it cored deep inside while "save" operation.
        if ( value == null )
            throw new IllegalArgumentException("Attribute \"" + attributeName + "\" should not be null");

        String retValue = getAttributeStringValue(attributeName, value);
        setAttributeValueImpl(attributeName, value);

        return retValue;
    }

    /**
     * Sets Object[] value of attribute
     * @param attributeName - the name of the attribute
     * @param value - new value of attribute.
     * @return previous state ( if exists)
     * @author Alexander Prozor
     */
    public
    String[] setAttributeValue(String attributeName, Object[] value)
    {
        return setAttributeValue(attributeName, value, DEFAULT_DELIMETERS);
    }

    /**
     * Sets Object[] value of attribute
     * @param attributeName - the name of the attribute
     * @param value - new value of attribute.
     * @param delimiter - delimiter for values.
     * @return previous state ( if exists)
     * @author Dmitry Moskalets
     */
    public
    String[] setAttributeValue(String attributeName, Object[] value, String delimiter)
    {
        String[] ret = getAttributeArrayValue(attributeName, EmptyArrays.STRING_ARRAY);
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < value.length; i++)
        {
            if (i != 0)
            {
                buffer.append(delimiter.charAt(0));
            }
            buffer.append(value[i]);
        }
        setAttributeValueImpl(attributeName, buffer.toString());
        return ret;
    }

    public <E extends Enum<E>> E setAttributeValue(String attributeName, E value)
    {
        final String oldValue = setAttributeValue(attributeName, value.name());
        return Enum.valueOf(value.getDeclaringClass(), oldValue);
    }

    //// validating

    /**
     * Change XML document
     * @param changeRequest change request.
     * @see com.finsent.util.xml.XMLData#changeData(Element changeRequest)
     * @author Alexander Prozor
     */
    public void changeData(String changeRequest)
    {
        XMLData request = valueOf(changeRequest);
        changeData(request);
    }

    /**
     * Change XML document
     * @param changeRequest change request.
     * @see com.finsent.util.xml.XMLData#changeData(Element changeRequest)
     * @author Alexander Prozor
     */
    public void changeData(Document changeRequest)
    {
        throw new UnsupportedOperationException("this operation not supported ( with Document parameter type)");
    }

    /**
     * Change XML document
     * @param changeRequest change request.
     * @see com.finsent.util.xml.XMLData#changeData(Element changeRequest)
     * @author Alexander Prozor
     */
    public void changeData(XMLData changeRequest)
    {
        changeData(changeRequest.getDocumentRoot());
    }
    /**
     * Change XML document.
     * @param changeRequest Element with specified changes to the XMLData.
     * @see com.finsent.util.xml.ElementChanger
     * @author Alexander Prozor
     */
    public void changeData(Element changeRequest)
    {
        xmlNode_ = ElementChanger.proceedChangeInstruction(xmlNode_, changeRequest);
    }


    // Instance accessors

    /**
     * Gets system ID of DTD (if present)
     * @return system DTD ID
     * @author Andrey Aleshnikov
     */
    public String getSystemDtdID()
    {
        DocumentType doctype = xmlNode_.getOwnerDocument().getDoctype();
        return null == doctype ? null : doctype.getSystemId();
    }

    /**
     * @return URL of the file this XML data was loaded from. Can be null or empty string.
     */
    @Override
    public @CheckForNull String getLoadedResourcePath()
    {
        return urlConfigInfoFile_;
    }


    /**
     * Please only use this for messages, not logic/behavior.
     *
     * @return either of the two:
     * <ul>
     *  <li> {@link #urlConfigInfoFile_} of this {@code XMLData} instance; </li>
     *  <li> value returned by this method of an {@code XMLData} instance
     *       this document was derived from
     *       (e.g. by {@code getDocumentPart(...)} method).</li>
     * </ul>
     */
    public String getUrlConfigInfoFileHint()
    {
        return ResourcePathUserData.Instance_.get(xmlNode_.getOwnerDocument());
    }

    public String getElementPositionHint()
    {
        Position position = ElemPositionUserData.Instance_.get(xmlNode_);
        return position != null ? position.toString() : null;
    }

    public String getElementUrlWithPosHint()
    {
        String filename = getUrlConfigInfoFileHint();
        String position = getElementPositionHint();
        return or(filename, "<unknown>") + ":" + or(position, "<unknown>");
    }

    static String or(String a, String b)
    {
        return a != null ? a : b;
    }

    /**
     * Makes copy of XMLData. <br>
     *
     * @return Object - instance of XMLData.
     */
    @Override
    public XMLData clone()
    {
        try
        {
            XMLData clone = (XMLData) super.clone();
            Document origDoc = xmlNode_.getOwnerDocument();
            if (xmlNode_.getParentNode() == origDoc)
            {
                Document clonedDoc = (Document) origDoc.cloneNode(true);
                clonedDoc.setXmlVersion(origDoc.getXmlVersion());
                clone.xmlNode_ = clonedDoc.getDocumentElement();
            }
            else // it is a derived XMLData (e.g. obtained from getDocumentPart() method)
            {
                Document clonedDoc = (Document) origDoc.cloneNode(false);
                clone.xmlNode_ = (Element) clonedDoc.importNode(xmlNode_, true);
                clonedDoc.appendChild(clone.xmlNode_);
            }
            return clone;
        }
        catch (CloneNotSupportedException e)
        {
            throw new AssertionError(e);
        }
    }

    static Map getUserData(Document origDoc)
    {
        try
        {
            return (Map) ReflectionUtils.getFieldValue(origDoc, "nodeUserData"); // com.sun.org.apache.xerces.internal.dom.CoreDocumentImpl.nodeUserData
        }
        catch (NoSuchFieldException | IllegalAccessException | ClassCastException e)
        {
            if (RunningProfile.isCodeChecked())
            {
                throw new AssertionError(e);
            }
        }
        return null;
    }

    /**
     * Converts XMLData instance to string in XML format
     * @return String - string in XML format
     */
    @Override
    public String toString()
    {
        return stringValue();
    }


    /**
     * Returns an array of all sub-elements of this data
     *
     * @return qn array of all sub-elements of this data
     * @author Sergey Bulakh
     */
    public ArrayList<XMLData> getChildElementsAsArrayList()
    {
        ArrayList<XMLData> result = new ArrayList<>();
        for (Node child = xmlNode_.getFirstChild(); null != child; child = child.getNextSibling())
            if (child.getNodeType() == Node.ELEMENT_NODE)
            {
                result.add(this.getDocumentPart((Element)child));
            }
        return result;
    }

    /**
    *  Gets all sub-elements of this data.
    *  @return iterator of the childrens. Each element of iterator is XMLData.
    *  @author Konstantine Matokhin
    */
    public ISizedIterator<XMLData> getChildElements ()
    {
        ArrayList<XMLData> arrayList = getChildElementsAsArrayList();
        return SizedIteratorUtil.newSizedIterator(arrayList, true);
    }

    /**
     * @see ChildNodesIterator
     * @author Andrey Aleshnikov
     */
    public Iterator<Node> getChildNodesIterator()
    {
        return new ChildNodesIterator(xmlNode_);
    }

    /**
     * Returns number of immediate child nodes (not necesseraly elements, but
     * also Text nodes, comment nodes, etc.).
     *
     * @see #hasChildElements()
     *
     * @author Max Bezsaly
     * @author Andrey Aleshnikov
     */
    public int getChildNodesCount()
    {
        // AA: we cannot use org.w3c.dom.Node.getChildNodes() + org.w3c.dom.NodeList.getLength()
        // since it is not thread safe even for read accesses in Xerces
        int result = 0;
        for (Node child = xmlNode_.getFirstChild(); null != child; child = child.getNextSibling())
            result++;
        return result;
    }

    /**
     * If root Node of XMLData has "Text Node"s - returns String value of it.
     * Otherwise returns null.
     * @return String root node value or null if it's not a root node.
     * @author Sergey Bulakh
     */
    public String getTextValueOfNode()
    {
        return XMLDataUtil.getTextValueOfNode(xmlNode_);
    }

    /**
     * Returns count of CDATA-elements of this data. Note this implicitely
     * iterates orver all immmediate children nodes of the current element, so
     * in many cases it is recommended to use {@link #getCDATAValuesIterator()}
     * instead of this method.
     *
     * @return count of CDATA-elements of this data
     * @author Serge Dorofeev
     * @author Andrey Aleshnikov
     */
    public int getCDATAsCount()
    {
        int result = 0;
        Iterator<CDATASection> cdatas = getCDATAIterator();
        while (cdatas.hasNext())
        {
            cdatas.next();
            result++;
        }
        return result;
    }

    /**
     * Returns CDATA-element of this data by its index
     * @param index Index of CDATA-element, 0-based.
     * @author Serge Dorofeev
     */
    public String getCDATAElement(int index)
    {
        Iterator<CDATASection> iterator = getCDATAIterator();
        for (int i = 0; iterator.hasNext(); i++)
        {
            Node next = iterator.next();
            if (i == index)
                return next.getNodeValue();
        }

        return "";
    }

    /**
     * @author Andrey Aleshnikov
     */
    public Iterator<String> getCDATAValuesIterator()
    {
        final Iterator<CDATASection> cdataNodes = getCDATAIterator();
        return new Iterator<String>()
                {
                    @Override
                    public boolean hasNext() { return cdataNodes.hasNext(); }

                    @Override
                    public void remove() { cdataNodes.remove(); }

                    @Override
                    public String next()
                    {
                        CDATASection next = cdataNodes.next();
                        return null == next ? null : next.getNodeValue();
                    }
                };
    }

    /**
    *  Gets all CDATA-elements of this data.
    *  @return iterator of the CDATA-elements.
    *  @author Serge Dorofeev
    *  @author Andrey Aleshnikov
    */
    private Iterator<CDATASection> getCDATAIterator()
    {
        return new Iterator<CDATASection>()
        {
            private CDATASection currentCDATANode_;
            {
                currentCDATANode_ = findFirstCDATAStartingFrom(xmlNode_.getFirstChild());
            }

            @Override
            public boolean hasNext()
            {
                return null != currentCDATANode_;
            }

            @Override
            public CDATASection next()
            {
                if (null == currentCDATANode_)
                    throw new IndexOutOfBoundsException();
                CDATASection oldCurrentCDATANode = currentCDATANode_;
                currentCDATANode_ = findFirstCDATAStartingFrom(currentCDATANode_.getNextSibling());
                return oldCurrentCDATANode;
            }

            @Override
            public void remove()
            {
                throw new UnsupportedOperationException();
            }

            private CDATASection findFirstCDATAStartingFrom(Node startNode)
            {
                for (; null != startNode; startNode = startNode.getNextSibling())
                    if (startNode.getNodeType() == Node.CDATA_SECTION_NODE)
                        return (CDATASection)startNode;
                return null;
            }
        };
    }

    /**
     * Adds a CDATA-element to this data
     * @param value String value of CDATA-element
     * @author Serge Dorofeev
     */
    public void addCDATAElement(String value)
    {
        xmlNode_.appendChild(xmlNode_.getOwnerDocument().createCDATASection(
            value));
    }

    /**
    * Gets all child-nodes of this data.
    * @return an array of all child-nodes of this data
    * @author Andrey Aleshnikov
    */
    public Node[] getChildNodesArray()
    {
        final Node[] result;
        if (null == xmlNode_.getFirstChild())
            result = EMPTY_NODE_ARRAY;
        else
        {
            ArrayList<Node> tmp = new ArrayList<>();
            for (Node child = xmlNode_.getFirstChild(); null != child; child = child.getNextSibling())
                tmp.add(child);
            result = tmp.toArray(new Node[tmp.size()]);
        }

        return result;
    }

    /**
    *  Returns the name of this data in the xml-document
    *  @return name of this data in the xml document.
    *  @author Konstantine Matokhin
    */
    public String getName()
    {
        return  xmlNode_.getNodeName();
    }

    /**
     * Checks whether property <code>key</code> was set in the main property
     * list.
     *
     *  If you need to call <code>getAttributeXXXValue()</code>,
     *  don't call <code>isAttributeDefined()</code> before.
     *  It leads to twice as many hash table lookups. instead, do:
     *
     *          value = getAttributeXXXValue(PROP, null);
     *          if (value != null)
     *              ...
     *  With primitive types one could do:
     *  boolean value = config.getAttributeBooleanValue(key, false);
     *          if (false == value)
     *              ...
     *
     * @param  attrName the checked property.
     * @return     true - if the property <code>key</code> was set in the main
     *             XML source(or if it is set in the  default),
     * or flase - otherwise
     * @author Alexander Prozor.
     * @see #isAttributeSet(String)
     */
    public boolean isAttributeDefined(String attrName)
    {
        checkAttributeAccess(attrName, "isDefined()");
        if (xmlNode_.hasAttribute(attrName))
            return true;
        if (null != defaultData_)
            return defaultData_.isAttributeDefined(attrName);
        return false;
    }

    /**
     * Checks whether property <code>key</code> was set in the main property
     * list.
     *
     * @param  attrName the checked property.
     * @return     true - if the property <code>key</code> was set in the main
     *             XML source
     * or false - otherwise , (even if it is set in the  default),
     * @author Alexander Prozor.
     * @see #isAttributeDefined(String)
     */
    public boolean isAttributeSet(String attrName)
    {
        checkAttributeAccess(attrName, "isSet()");
        return (xmlNode_.getAttribute(attrName).length() != 0);
    }

    /**
     * Should return all child XMLData objects.
     * @author Vadim Hmelik
     */
    public XMLData[] getDocumentParts()
    {
        ArrayList<XMLData> list = getChildElementsAsArrayList();
        return list.toArray(new XMLData[list.size()]);
    }

    /**
     * Creates XMLData class with document root
     * @param branchRoot - branch that will be used to create instance
     * @return XMLData with document root.
     * @author  Alexander Prozor
     */
    public XMLData getDocumentPart(Element branchRoot)
    {
        XMLData documentPart = null;
        if(branchRoot != null)
        {
            documentPart = new XMLData(branchRoot);
        }
        else if(defaultData_ != null)
        {
            documentPart = defaultData_.getDocumentPart(branchRoot);
        }
        return documentPart;
    }

    /**
     * Creates XMLData class with document root
     * that will be found with tag name specified
     * @param tag - tag that defines root for newly created instance
     * @return XMLData with document root, defined by input tag.
     * @author  Alexander Prozor
     */
    @Deprecated // instead use getDocumentPart(String tag,  boolean searchRecursively)
    public XMLData getDocumentPart(String tag)
    {
        return getDocumentPart(tag, true);
    }

    /**
     * Creates XMLData class with document root
     * that will be found with tag name specified
     * @param tag - tag that defines root for newly created instance
     * @param searchRecursively - true to search  recursively
     * @return XMLData with document root, defined by input tag.
     * @author VS
     */
    public XMLData getDocumentPart(String tag,  boolean searchRecursively)
    {
        return getDocumentPart(new XMLDataPathElement(tag), searchRecursively);
    }

    /**
     * Creates XMLData class with document root
     * that will be found by pathElement specified
     * @param pathElement - element of XML path that will be used to create instance
     * @return XMLData with document root, defined by input pathElement.
     * @author  Alexander Prozor
     */
    public XMLData getDocumentPart(XMLDataPathElement pathElement)
    {
        return getDocumentPart(pathElement, true);
    }

    /**
     * Creates XMLData class with document root
     * that will be found by pathElement specified
     * @param pathElement
     * @param searchRecursively - true to search  recursively
     * @return XMLData with document root, defined by input pathElement.
     * @author  Alexander Prozor
     */
    public @Nullable XMLData getDocumentPart(XMLDataPathElement pathElement, boolean searchRecursively)
    {
        XMLData documentPart = null;
        Element soughtElement = getElement(pathElement, searchRecursively);
        if(soughtElement != null)
        {
            documentPart = new XMLData(soughtElement);
        }

        if(defaultData_ != null)
        {
            XMLData defaultDataPart = defaultData_.getDocumentPart(pathElement, searchRecursively);
            if (defaultDataPart != null)
            {
                if (documentPart == null)
                    documentPart = XMLData.newInstance(pathElement.getTagName());
                documentPart.setDefaultData(defaultDataPart);
            }
        }

        return documentPart;
    }

    public Collection<XMLData> getDocumentParts(XMLDataPathElement pathElement, boolean searchRecursively)
    {
        Collection<Element> soughtElements = pathElement.getElements(xmlNode_, searchRecursively);
        Collection<XMLData> result = new ArrayList<>();
        for (Element element : soughtElements)
        {
            XMLData documentPart = new XMLData(element);
            result.add(documentPart);
        }
        return result;
    }

    /**
     * Creates XMLData class with document root
     * that will be found by path specified
     * @param path XML data path that will be used to create instance
     * @return XMLData with document root, defined by input path.
     * @author  Alexander Prozor
     */
    public XMLData getDocumentPart(XMLDataPath path)
    {
        return getDocumentPart(path, true);
    }

    /**
     * Creates XMLData class with document root
     * that will be found by path specified
     * @param path XML data path that will be used to create instance
     * @param searchRecursively - true to search recursively
     * @return XMLData with document root, defined by input path.
     * @author  Alexander Prozor
     */
    public XMLData getDocumentPart(XMLDataPath path,  boolean searchRecursively)
    {
        XMLData documentPart = null;
        Element soughtElement = getElement(path, xmlNode_, searchRecursively);
        if(soughtElement != null)
        {
            documentPart = new XMLData(soughtElement);
        }
        else if(defaultData_ != null)
        {
            documentPart = defaultData_.getDocumentPart(path, searchRecursively);
        }
        return documentPart;
    }

    /**
     * Returns iterator of elements with specified tag name
     * @param tagName tag name
     * @return ISizedIterator with XMLData's.
     * @author  Alexander Prozor
     */
    @Deprecated // use getDocumentPartsByTagName(String tagName, boolean searchRecursively) method
    public ISizedIterator<XMLData> getDocumentPartsByTagName(String tagName)
    {
        return getDocumentPartsByTagName(tagName, true);
    }

    public ISizedIterator<XMLData> getDocumentPartsByTagName(String tagName, boolean searchRecursively)
    {
        return getDocumentPartsByTagName(tagName, searchRecursively, true);
    }

    /**
     * @author Andrey Aleshnikov
     */
    public ISizedIterator<XMLData> getDocumentPartsByTagName(String tagName, boolean searchRecursively, boolean includeRoot)
    {
        List<Element> els = getElementsByTagName(tagName, searchRecursively, includeRoot);
        if (els.isEmpty() && null != defaultData_)
            return defaultData_.getDocumentPartsByTagName(tagName, searchRecursively);
        return new DocumentPartsIterator(els);
    }
/**
     * Usage:<p>{@code for (XMLData child: parent.subElements("ChildSectionName") {...} }
     * @return iterable of immediate child sections with given name. Can be empty but not null.
     */
    public Iterable<XMLData> subElements(String sectionName)
    {
        return () -> getDocumentPartsByTagName(sectionName, false);
    }
    /**
     * Returns list of nodes by tag name.
     *
     * @param tagName name of tag
     * @param searchRecursively - true to search recursively
     * @return NodeList with Node
     * @author Konstantin Matokhin
     * @author Andrey Aleshnikov
     */
    protected List<Element> getElementsByTagName(String tagName,  boolean searchRecursively)
    {
        return getElementsByTagName(tagName, searchRecursively, false);
    }

    /**
     * @author Andrey Aleshnikov
     */
    protected List<Element> getElementsByTagName(String tagName, boolean searchRecursively, boolean includeRoot)
    {
        final List<Element> resultElements = new ArrayList<>();
        if (includeRoot && xmlNode_.getNodeName().equals(tagName))
            resultElements.add(xmlNode_);
        if (searchRecursively)
            for (Node child1 = xmlNode_.getFirstChild(); null != child1; child1 = child1.getNextSibling())
                findAllElementsInSubtree(child1, tagName, resultElements);
        else
            for (Node child = xmlNode_.getFirstChild(); null != child; child = child.getNextSibling())
                if (Node.ELEMENT_NODE == child.getNodeType() && tagName.equals(child.getNodeName()))
                    resultElements.add((Element)child);
        return Collections.unmodifiableList(resultElements);
    }

    private static void findAllElementsInSubtree(Node start, String tagName, List<Element> accum)
    {
        if (null == start)
            return;
        if (Node.ELEMENT_NODE == start.getNodeType() &&
                (null == tagName || tagName.equals(start.getNodeName())))
            accum.add((Element)start);
        for (Node child = start.getFirstChild(); null != child; child = child.getNextSibling())
            findAllElementsInSubtree(child, tagName, accum);
    }

    /**
     * Returns iterator of elements with specified tag name,
     * expanding elements &lt;Include&gt;.
     *
     * Element &lt;Include&gt; has two attributes:
     * <ul>
     * <li>{@code datapath} - text representation of XMLDataPath of element that its content will be included; and
     * <li>{@code resource} - name of file that will be used for inclusion (optional - current file is used by default).
     * </ul>
     *
     * @param tagName name of sought xml element
     * @return sized iterator
     */
    @Deprecated // use getDocumentPartsByTagNameWithIncludes(String tagName, boolean searchRecursively) method instead
    public ISizedIterator<XMLData> getDocumentPartsByTagNameWithIncludes(String tagName)
    {
        return getDocumentPartsByTagNameWithIncludes(tagName, true);
    }

    public ISizedIterator<XMLData> getDocumentPartsByTagNameWithIncludes(String tagName, boolean searchRecursively)
    {
        return getDocumentPartsByTagNameWithIncludes(tagName, searchRecursively, DEFAULT_RESOLVER);
    }

    /**
     * @return include name if given data contains &lt;Include&gt; element,
     * null otherwise
     */
    public String getIncludedResourceName()
    {
        XMLData include = getDocumentPart(ELEMENT_INCLUDE, true);
        if (include != null)
        {
            return include.getAttributeStringValue(ATTRIBUTE_RESOURCE, null);
        }
        else
        {
            return null;
        }
    }

    public ISizedIterator<XMLData> getDocumentPartsByTagNameWithIncludes(String tagName, boolean searchRecursively, IResourceResolver resourceResolver)
    {
        return getDocumentPartsByTagNameWithIncludes(tagName, searchRecursively, resourceResolver, false);
    }

    public ISizedIterator<XMLData> getDocumentPartsByTagNameWithIncludes(String tagName, boolean searchRecursively, IResourceResolver resourceResolver, boolean substituteSysVars)
    {
        List<XMLData> result = new ArrayList<XMLData>();
        SizedIteratorUtil.fill(result, getDocumentPartsByTagName(tagName, searchRecursively));
        ISizedIterator<XMLData> includes = getDocumentPartsByTagName(ELEMENT_INCLUDE, searchRecursively);
        while (includes.hasNext())
        {
            XMLData include = includes.next();
            String resource = include.getAttributeStringValue(ATTRIBUTE_RESOURCE, null);

            // Set params for substitution as system ones temporary:
            Map<String, String> prevSysParams = null;
            if (substituteSysVars)
            {
                ISizedIterator<XMLData> parameters = include.getDocumentPartsByTagName(ELEMENT_PARAMETER, false);
                prevSysParams = new HashMap<>();
                while (parameters.hasNext())
                {
                    XMLData parameter = parameters.next();
                    String key = parameter.getAttributeStringValue(ATTRIBUTE_KEY);
                    String value = parameter.getAttributeStringValue(ATTRIBUTE_VALUE);

                    String prevSysVal = System.getProperties().getProperty(key);
                    prevSysParams.put(key, prevSysVal);
                    System.getProperties().setProperty(key, value);
                }
            }

            if (resource == null)
            {
                XMLData start = new XMLData(xmlNode_.getOwnerDocument().getDocumentElement());
                String datapath = include.getAttributeStringValue(ATTRIBUTE_DATAPATH);
                XMLData found = start.getDocumentPart(new XMLDataPath(datapath));
                if (found != null)
                {
                    if (substituteSysVars)
                    {
                        found.substituteEnvVariables();
                    }
                    SizedIteratorUtil.fill(result, found.getDocumentPartsByTagNameWithIncludes(tagName, searchRecursively, resourceResolver, substituteSysVars));
                }
            }
            else
            {
                try
                {
                    for (XMLData startData : resourceResolver.resolveResources(resource))
                    {
                        if (startData != null)
                        {
                            String datapath = include.getAttributeStringValue(ATTRIBUTE_DATAPATH, "/");
                            XMLData found = startData.getDocumentPart(new XMLDataPath(datapath));
                            if (found != null)
                            {
                                if (substituteSysVars)
                                {
                                    found.substituteEnvVariables();
                                }
                                SizedIteratorUtil.fill(result, found.getDocumentPartsByTagNameWithIncludes(tagName, searchRecursively, resourceResolver, substituteSysVars));
                            }
                        }
                    }
                }
                catch (BadResourceException ex)
                {
                    error().write(ex);
                }
            }

            // restore previous state of system properties:
            if (prevSysParams != null && prevSysParams.size() > 0)
            {
                for (Entry<String, String> entry: prevSysParams.entrySet())
                {
                    if (entry.getValue() == null)
                    {
                        System.getProperties().remove(entry.getKey());
                    }
                    else
                    {
                        System.getProperties().setProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return SizedIteratorUtil.newSizedIterator(result);
    }

    /**
     * Returns XMLData's with specified tag names.
     * The same as getDocumentPartsByTagName(String), but not recursive.
     * @param tagName tag of children to find.
     * @return  array with found children.
     * @author  Konstantin Matokhin
     */
    @Nonnull
    public XMLData[] getChildrenByTagName(String tagName)
    {
        XMLData[] children = getChildElementsAsArrayList().stream()
            .filter(child -> child.getName().equals(tagName))
            .toArray(XMLData[]::new);

        return defaultData_ != null
               ? UtilityFunctions.uniteArrays(children, defaultData_.getChildrenByTagName(tagName))
               : children;
    }

    /**
     * Returns XMLData's with specified tag names.
     * The same as getDocumentPartsByTagName(String), but not recursive.
     * @param tagName tag of children to find.
     * @return  array with found children.
     * @author  Konstantin Matokhin
     */
    @Nonnull
    public XMLData[] getChildrenByTagNameWithoutUniteWithDefault(String tagName)
    {
        XMLData[] children = getChildElementsAsArrayList().stream()
            .filter(child -> child.getName().equals(tagName))
            .toArray(XMLData[]::new);

        return (children.length == 0 && defaultData_ != null)
               ? defaultData_.getChildrenByTagName(tagName)
               : children;
    }

    /**
     * Finds element, specified in the pathElement
     * @param pathElement element of path that specifies element
     * @return found element or null
     */
    protected Element getElement(XMLDataPathElement pathElement)
    {
        return getElement(pathElement, true);
    }

    /**
     * Finds element, specified in the pathElement
     * @param pathElement - element of path that specifies element
     * @param searchRecursively - true to search recursively
     * @return found element or null
     * @see #getElement(XMLDataPath, Element, boolean)
     */
    protected Element getElement(XMLDataPathElement pathElement, boolean searchRecursively)
    {
        Collection<Element> elements = pathElement.getElements(xmlNode_, searchRecursively);

        if (elements.size() > 1 && warning().isEnabled())
        {
            StringBuilder warning = new StringBuilder();
            warning.append("Found multiple elements for path ").append(pathElement).append(": ");
            boolean first = true;
            for (Element el : elements)
            {
                if (!first)
                    warning.append(", ");
                else first = false;
                warning.append(XMLDataUtil.getXPATHLikeNodePath(el));
            }
            warning.append(". The first one will be used.");
            if (urlConfigInfoFile_ != NO_SYSTEM_ID && !urlConfigInfoFile_.isEmpty() )
                warning.append("XML resource: ").append(urlConfigInfoFile_);
            else
            {
                warning.append(GlobalDefs.EOL).append("---XML data begin---").append(GlobalDefs.EOL);
                String stringValue = stringValue(true);
                warning.append(stringValue);
                if (!stringValue.endsWith(GlobalDefs.EOL))
                    warning.append(GlobalDefs.EOL);
                warning.append("---XML data end---");
            }

            if (RunningProfile.isConfigurationChecked())
                warning().write(new Exception(warning.toString()));
            else
                warning().write(warning.toString());
        }

        return elements.isEmpty() ? null : elements.iterator().next();
    }

    /**
     * Finds element, specified in the path
     * @param path path that specifies element
     * @return found element or null
     * @author  Alexander Prozor
     */
    protected Element getElement(XMLDataPath path)
    {
        return getElement(path, xmlNode_, true);
    }

    /**
     * Please avoid using this as XMLData wrapper methods ensure additional
     * properties are satisfied (e.g. preserving attributes order) compared to
     * bare {@link Element}.
     *
     * @see #ATTR_INDEX
     * @return document element.
     */
    public Element getDocumentRoot()
    {
        return xmlNode_;
    }

    /**
     * Sets document element
     * PENDING we haven't to get such info
     * @author  Alexander Prozor
     */
    public void setDocumentRoot(Element root)
    {
        xmlNode_ = root;
    }

    /**
     * Returns all attrubutes for specified element.
     * @return list of Map.Entry objects.
     * @author Alexander Prozor
     */
    @Nonnull
    public List<XMLAttrNameValuePair> getAttributeList()
    {
        AttrsIterator it = new AttrsIterator(xmlNode_);
        List<XMLAttrNameValuePair> attributesPairs = new ArrayList<XMLAttrNameValuePair>(it.size());
        while (it.hasNext())
        {
            Attr a = it.next();
            attributesPairs.add(new XMLAttrNameValuePair(a.getNodeName(), a.getNodeValue()));
        }

        return attributesPairs;
    }

    /**
     * Gets int value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData)
     * @param     attributeName - name for the attribute.
     * @return    value of the attribute.
     * @exception NumberFormatException throws when string in attribute value has incorrect number format
     */
    public int getAttributeIntegerValue(String attributeName)
        throws NumberFormatException
    {
        String value = getAttributeStringValue(attributeName);
        return Integer.parseInt(value);
    }

    /**
     * Gets int value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData).
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     * @exception NumberFormatException throws when string in attribute value has incorrect number format
     * @see    XMLData#getAttributeStringValue(String,String)
     */
    public int getAttributeIntegerValue(String attributeName, int defaultValue)
    {
        String value = getAttributeStringValue(attributeName, null);
        return (null == value ? defaultValue : Integer.parseInt(value));
    }

    /**
     * Gets int value of the specified attribute, if there is no such value,
     * attribute value from the defaultAttributeSource will be used.
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultAttributeSource - default XMLData, is used if attribute
     *            is not found in the this XMLData.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     * @see    XMLData#getAttributeStringValue(String,XMLData,String)
     */
    public int getAttributeIntegerValue(
        String attributeName,
        XMLData defaultAttributeSource,
        int defaultValue)
    {
        String value = getAttributeStringValue(attributeName, defaultAttributeSource, null);
        return (null == value ? defaultValue : Integer.parseInt(value));
    }

    /**
     * @see XMLData#getAttributeIntegerValue(String)
     */
    public int getAttributeIntValue(String attributeName)
        throws NumberFormatException
    { return getAttributeIntegerValue(attributeName); }

    /**
     * @see    XMLData#getAttributeIntegerValue(String,int)
     */
    public int getAttributeIntValue(String attributeName, int defaultValue)
    { return getAttributeIntegerValue(attributeName, defaultValue); }

    /**
     * @see    XMLData#getAttributeIntegerValue(String,XMLData,int)
     */
    public int getAttributeIntValue(
        String attributeName,
        XMLData defaultAttributeSource,
        int defaultValue)
    { return getAttributeIntegerValue(attributeName, defaultAttributeSource, defaultValue); }

    /**
     * Gets double value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData)
     * @param     attributeName - name for the attribute.
     * @return    value of the attribute.
     * @exception NumberFormatException throws when string in attribute value has incorrect number format
     */
    public double getAttributeDoubleValue(String attributeName)
        throws NumberFormatException
    {
        String value = getAttributeStringValue(attributeName);
        return Double.parseDouble(value);
    }

    /**
     * Gets doble value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData).
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     * @exception NumberFormatException throws when string in attribute value has incorrect number format
     * @see    XMLData#getAttributeStringValue(String,String)
     */
    public double getAttributeDoubleValue(String attributeName, double defaultValue)
    {
        String value = getAttributeStringValue(attributeName, null);
        return (null == value ? defaultValue : Double.parseDouble(value));
    }

    /**
     * Gets double value of the specified attribute, if there is no such value,
     * attribute value from the defaultAttributeSource will be used.
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultAttributeSource - default XMLData, is used if attribute
     *            is not found in the this XMLData.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     * @see    XMLData#getAttributeStringValue(String,XMLData,String)
     */
    public double getAttributeDoubleValue(
        String attributeName,
        XMLData defaultAttributeSource,
        double defaultValue)
    {
        String value = getAttributeStringValue(attributeName, defaultAttributeSource, null);
        return (null == value ? defaultValue : Double.parseDouble(value));
    }

    /**
     * Gets long value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData)
     * @param     attributeName - name for the attribute.
     * @return    value of the attribute.
     * @exception NumberFormatException throws when string in attribute value has incorrect number format
     */
    public long getAttributeLongValue(String attributeName)
        throws NumberFormatException
    {
        String value = getAttributeStringValue(attributeName);
        return Long.parseLong(value);
    }

    /**
     * Gets long value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData).
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     * @exception NumberFormatException throws when string in attribute value has incorrect number format
     * @see    XMLData#getAttributeStringValue(String,String)
     */
    public long getAttributeLongValue(String attributeName, long defaultValue)
    {
        String value = getAttributeStringValue(attributeName, null);
        return (null == value ? defaultValue : Long.parseLong(value));
    }

    /**
     * Gets long value of the specified attribute, if there is no such value,
     * attribute value from the defaultAttributeSource will be used.
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultAttributeSource - default XMLData, is used if attribute
     *            is not found in the this XMLData.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     * @see    XMLData#getAttributeStringValue(String,XMLData,String)
     */
    public long getAttributeLongValue(
        String attributeName,
        XMLData defaultAttributeSource,
        long defaultValue)
    {
        String value = getAttributeStringValue(attributeName, defaultAttributeSource, null);
        return (null == value ? defaultValue : Long.parseLong(value));
    }

    /**
     * Gets boolean value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData)
     * @param     attributeName - name for the attribute.
     * @return    value of the attribute.
     */
    public boolean getAttributeBooleanValue(String attributeName)
    {
        String value = getAttributeStringValue(attributeName);
        return Boolean.parseBoolean(value);
    }

    /**
     * Gets boolean value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData).
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     * @exception NumberFormatException throws when string in attribute value has incorrect number format
     * @see    XMLData#getAttributeStringValue(String,String)
     */
    public boolean getAttributeBooleanValue(String attributeName, boolean defaultValue)
    {
        String value = getAttributeStringValue(attributeName, null);
        return (null == value ? defaultValue : Boolean.parseBoolean(value));
    }

    /**
     * Gets boolean value of the specified attribute, if there is no such value,
     * attribute value from the defaultAttributeSource will be used.
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultAttributeSource - default XMLData, is used if attribute
     *            is not found in the this XMLData.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     * @see    XMLData#getAttributeStringValue(String,XMLData,String)
     */
    public boolean getAttributeBooleanValue(
        String attributeName,
        XMLData defaultAttributeSource,
        boolean defaultValue)
    {
        String value = getAttributeStringValue(attributeName, defaultAttributeSource, null);
        return (null == value ? defaultValue : Boolean.parseBoolean(value));
    }


    /**
     * Gets String value of the specified attribute, if there is no such value,
     * attribute value from the default data will be used, see setDefaultData(XMLData)
     * @param     attributeName - name for the attribute.
     * @return    value of the attribute.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    @Nonnull
    public String getAttributeStringValue(String attributeName)
    {
        String value = null;
        try
        {
            value = xmlNode_.getAttribute(attributeName);
            if (!value.isEmpty())
            {
                value = tryToResolveEnvVariables(value);
            }
            else
            {
                if (null == defaultData_)
                {
                    value = "<not found>";
                    throw createXMLNoSuchAttributeException(attributeName);
                }
                else
                {
                    value = defaultData_.getAttributeStringValue(attributeName);
                }
            }
            return value;
        }
        finally
        {
            checkAttributeAccess(attributeName, value);
        }
    }

    /**
     * Gets String attribute value.
     * If attribute is not found in this XML source,
     * the default XML source is then checked. If the key is not found in
     * the default XML source, it throws an exception
     * <code>XMLNoSuchAttributeException</code> .
     *
     * @param     attributeName the name of the attribute.
     * @param     defaultValue the default value of the attribute.
     * @return        the <code>int</code> value in this XML source
     *                with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     * @see       XMLData#getAttributeStringValue(String)
     */
    public String getAttributeStringValue(String attributeName, String defaultValue)
    {
        String value = null;

        try
        {
            Attr attr = xmlNode_.getAttributeNode(attributeName);
            if (null == attr)
            {
                if (null == defaultData_)
                    value = defaultValue;
                else
                    value = defaultData_.getAttributeStringValue(attributeName, defaultValue);
            }
            else
            {
                value = tryToResolveEnvVariables(attr.getValue());
            }
            return value;
        }
        finally
        {
            checkAttributeAccess(attributeName, value);
        }
    }

    /**
     * Gets String value of the specified attribute, if there is no such value,
     * attribute value from the defaultAttributeSource will be used.
     * If there is still no value, defaultValue will be returned.
     * @param     attributeName - name for the attribute.
     * @param     defaultAttributeSource - default XMLData, is used if attribute
     *            is not found in the this XMLData.
     * @param     defaultValue the default value of the attribute.
     * @return    value of the attribute.
     */
     public String getAttributeStringValue(
        String attributeName,
        XMLData defaultAttributeSource,
        String defaultValue)
    {
        String value = null;
        try
        {
            Attr attr = xmlNode_.getAttributeNode(attributeName);
            if (null == attr)
            {
                value = defaultAttributeSource.getAttributeStringValue(attributeName, defaultValue);
            }
            else
            {
                value = tryToResolveEnvVariables(attr.getValue());
            }
            return value;
        }
        finally
        {
            checkAttributeAccess(attributeName, value);
        }
    }

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this XML source. If the key is not found
     * in this XML source, the default XML source is then checked.
     * If the key is not found in the default XML source,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key the key.
     * @return        the array of <code>String</code> value
     *                in this XML source with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     * @see       com.finsent.util.Configurator#getPropertyStringValue(String)
     */
    public
    String[] getAttributeArrayValue(String key )
    {
        return getAttributeArrayValue(key, DEFAULT_DELIMETERS, false);
    } // Configurator.getPropertyArrayValue()

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this XML source. If the key is not found
     * in this XML source, the default XML source is then checked.
     * If the key is not found in the default XML source,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key the key.
     * @param     delimeters string contains delimeters
     * @return        the array of <code>String</code> value
     *                in this XML source with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public
    String[] getAttributeArrayValue(String key, String delimeters )
    {
        return getAttributeArrayValue(key, delimeters, false);
    } // Configurator.getPropertyArrayValue()

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this XML source. If the key is not found
     * in this XML source, the default XML source is then checked.
     * If the key is not found in the default XML source,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key the key.
     * @param     trimBlanks trim spaces from both ends of the strings
     * @return        the array of <code>String</code> value
     *                in this XML source with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public
    String[] getAttributeArrayValue( String key, boolean trimBlanks )
    {
        return getAttributeArrayValue(key, DEFAULT_DELIMETERS, trimBlanks);
    } // Configurator.getPropertyArrayValue()

    public String[] getAttributeArrayValue(String key, String delimiters, boolean trimBlanks)
    {
        return getAttributeArrayValue(key, delimiters, trimBlanks, true);
    }
    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this XML source. If the key is not found
     * in this XML source, the default XML source is then checked.
     * If the key is not found in the default XML source,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key the key.
     * @param     delimiters string contains delimiters
     * @param     trimBlanks is trom spaces from both ends of the strings
     * @return        the array of <code>String</code> value
     *                in this XML source with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public
    String[] getAttributeArrayValue(String key, String delimiters, boolean trimBlanks, boolean skipEmpty)
    {
        if(!isAttributeDefined(key))
        {
            throw createXMLNoSuchAttributeException(key);
        }
        String str = xmlNode_.getAttribute(key);
        return UtilityFunctions.getTokens(tryToResolveEnvVariables(str), delimiters, trimBlanks, skipEmpty);
    }

    public
    String[] getAttributeArrayValue(INamed key, String delimiters, boolean trimBlanks, boolean skipEmpty)
    {
        return getAttributeArrayValue(key.getName(), delimiters, trimBlanks, skipEmpty);
    }

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this XML source. If the key is not found
     * in this XML source, the default XML source is then checked.
     * If the key is not found in the default XML source,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key          the key.
     * @param     defaultValue the default value.
     * @return    the array of <code>String</code> value
     *            in this XML source with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public
    String[] getAttributeArrayValue(String key, String[] defaultValue )
    {
        return getAttributeArrayValue(key, DEFAULT_DELIMETERS, false, defaultValue);
    }

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this XML source. If the key is not found
     * in this XML source, the default XML source is then checked.
     * If the key is not found in the default XML source,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key          the key.
     * @param     delimiters string contains delimiters
     * @param     trimBlanks trim spaces from both ends of the strings
     * @param     defaultValue the default value.
     * @return    the array of <code>String</code> value
     *            in this XML source with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public
    String[] getAttributeArrayValue(String key, String delimiters,
                                    boolean trimBlanks, String[] defaultValue )
    {
        String str = xmlNode_.getAttribute(key);
        if ( str.length() == 0 )
        {
            return defaultValue;
        }
        return UtilityFunctions.getTokens(tryToResolveEnvVariables(str), delimiters, trimBlanks);
    }

    public
    String[] getAttributeArrayValue(INamed key, String delimiters,
                                    boolean trimBlanks, String[] defaultValue )
    {
        return getAttributeArrayValue(key.getName(), delimiters, trimBlanks, defaultValue);
    }
    /**
     * Searches for the attribute values. If the attribute is not found
     * returns default value
     * @param skipEmpty  If true skips sempty tokens, otherwise returns
     * null token between each two delimiters
     * @return List
     */
    public List<String> getAttributeArrayValueAsList(String key, String delimiters,
                                                   boolean skipEmpty, List<String> defaultValue )
    {
        String str = (xmlNode_).getAttribute(key);
        if ( str.length() == 0 )
        {
            return defaultValue;
        }
        return UtilityFunctions.getTokensAsList(str, delimiters,skipEmpty);
    }

    /**
     * Searches for the attribute value. If the attribute is not found
     * returns default value
     * @param skipEmpty  If true skips sempty tokens, otherwise returns
     * null token between each two delimiters
     * @return List
     */
    public List<String> getAttributeArrayValueAsList(INamed key,
                                                     boolean skipEmpty, List<String> defaultValue )
    {
        return getAttributeArrayValueAsList(key.getName(), DEFAULT_DELIMETERS, skipEmpty, defaultValue);
    }

    /**
     * Searches for the attribute value. If the attribute is not found
     * returns default value
     * @param skipEmpty  If true skips sempty tokens, otherwise returns
     * null token between each two delimiters
     * @return Set
     */
    public Set<String> getAttributeArrayValueAsSet(String key, String delimiters,
                                                   boolean skipEmpty, Set<String> defaultValue )
    {
        String str = (xmlNode_).getAttribute(key);
        if ( str.length() == 0 )
        {
            return defaultValue;
        }
        return UtilityFunctions.getTokensAsSet(str, delimiters, skipEmpty);
    }
    /**
     * Searches for the attribute value. If the attribute is not found
     * returns default value
     * @param skipEmpty  If true skips sempty tokens, otherwise returns
     * null token between each two delimiters
     * @return Set
     */
    public
    Set<String> getAttributeArrayValueAsSet(INamed key,  boolean skipEmpty, Set<String> defaultValue )
    {
        return getAttributeArrayValueAsSet(key.getName(), DEFAULT_DELIMETERS, skipEmpty, defaultValue);
    }

    /**
     * @see com.finsent.directory.IDirectoryResource
     * @return a UTF-8-encoded byte sequence corresponding to a "pretty" string
     *         representation of this instance.
     * @author Andrey Aleshnikov
     */
    @Override
    public byte[] getDirectoryValue()
    {
        String stringValue = stringValue(true);
        try
        {
            ByteBuffer bb = DEFAULT_ENCODING.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(stringValue));
            byte[] bufArray = bb.array();
            byte[] result;
            if (bb.limit() == bufArray.length)
                result = bufArray; // there are no unused array elements
            else
                result = Arrays.copyOf(bufArray, bb.limit()); // trim unused array elements
            return result;
        }
        catch (CharacterCodingException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    /**
    *  Creates XMLData based on the DIRECTORY server resource
    *  @param resource resource from DIRECTORY.
    *  @return created XMLData.
    *  @author Konstantine Matokhin
    */
    // TODO AA: this should be named valueOfResource
    // TODO AA: replace this with other means of creating XMLData that set urlConfigInfoFile_ properly
    public static XMLData createOnResource(IDirectoryResource resource)
    {
        if (resource == null)
        {
            return null;
        }
        // TODO AA: byte[] -> String conversion must only happen inside parser
        // TODO AA: should we create XMLData(byte[]) ctor or valueOf(byte[]) ?
        return valueOf(UtilityFunctions.bytesToString(resource.getDirectoryValue()));
    }

    /**
     * Adds all information from dataToAdd to this data ( nodes, attributes).
     * @param dataToAdd - XMLData from which we want to copy nodes and attributes.
     */
    public final void addData(XMLData dataToAdd)
    {
        if (dataToAdd != null)
        {
            copy(dataToAdd.getDocumentRoot(), xmlNode_, true, false);
        }
    }

    /**
     * Adds all information from dataToAdd to this data ( nodes, attributes) in such a way
     * that it is be saved as separate resource and Include element appears in this document
     * @param dataToAdd - XMLData from which we want to copy nodes and attributes.
     */
    public final void addIncludeData(XMLData dataToAdd, String resourceName, IResourceSaver resourceSaver)
    {
        XMLData include = new XMLData();
        include.setDocumentRoot(include.createNewElement(ELEMENT_INCLUDE));
        include.setAttributeValue(ATTRIBUTE_RESOURCE, resourceName);

        addData(include);

        resourceSaver.saveResource(resourceName, dataToAdd);
    }

    /**
     * Same as addData() except it does not include the root from dataToCopy.
     * @param dataToCopy - XMLData from which we want to copy nodes and attributes.
     * @see XMLData#addData(XMLData)
     */
    public final void copyData(XMLData dataToCopy)
    { copy(dataToCopy.getDocumentRoot(), xmlNode_, false, false); }

    /**
     * Same as addData() except it does not include the root and
     * copies only attributes.
     * @param dataToCopy - XMLData from which we want to copy attributes.
     * @see XMLData#addData(XMLData)
     */
    public final
    void copyAttributes(XMLData dataToCopy)
    { copy(dataToCopy.getDocumentRoot(), xmlNode_, false, true); }

    /**
     * Copy all child nodes and its attributes from source node to target
     * (to avoid exception, when adding node not belong to the same document
     * as target node)
     * @param sourceNode node to copy from
     * @param targetNode node to copy to
     * @author Alexander Prozor
     */
    public final static void copy(Node sourceNode, Node targetNode)
    { copy(sourceNode, targetNode, true, false); }

    /**
     * Copy all child nodes and its attributes from source node to target.
     * (to avoid exception, when adding node not belong to the same document
     * as target node).
     * @param sourceNode node to copy from
     * @param targetNode node to copy to
     * @param addRootAsChildNode if true, root of targetNode is addes as child to the
     *        sourceNode, if false , all except root is copied from source to target.
     * @param attributesOnly - if true, only attributes are copied from source
     *                         to target, if false, children are also copied with
     *                         help of recursion.
     * @author Alexander Prozor
     */
    public final static void copy(
        Node sourceNode,
        Node targetNode,
        boolean addRootAsChildNode,
        boolean attributesOnly)
    {
        String rootNodeName = sourceNode.getNodeName();
        Document ownerDoc = targetNode.getOwnerDocument();
        Element rootElement;
        if (addRootAsChildNode && !attributesOnly)
        {
            // copy root node
            rootElement = ownerDoc.createElement(rootNodeName);
            targetNode.appendChild(rootElement);
            ElemPositionUserData.Instance_.set(rootElement, new Position(-1, -1, false));
        }
        else
        {
            rootElement = (Element)targetNode;
        }

        // copy all its attributes
        if(sourceNode instanceof Element && targetNode instanceof Element)
        {
            //NamedNodeMap attributes = extractAttributesFromNode(sourceNode);
            for (AttrsIterator it = new AttrsIterator((Element) sourceNode); it.hasNext(); )
            {
                Attr attr = it.next();
                String attrName = attr.getNodeName();
                String attrValue = attr.getNodeValue();
                setAttributeValueImpl(rootElement, attrName, attrValue);
            }
        }
        if (!attributesOnly)
        {
            // copy child nodes (pending right now I copy only Element and Text Nodes ( not Comment and Entity)
            for (Node currentNode = sourceNode.getFirstChild(); null != currentNode; currentNode = currentNode.getNextSibling())
            {
                if (currentNode.getNodeType() == Node.ELEMENT_NODE)
                {
                    copy(currentNode, rootElement);
                }
                else if (currentNode.getNodeType() == Node.TEXT_NODE)
                {
                    Node textNode = rootElement.getOwnerDocument().createTextNode(currentNode.getNodeValue());
                    rootElement.appendChild(textNode);
                }
                else if (currentNode.getNodeType() == Node.COMMENT_NODE)
                {
                    Comment comment = rootElement.getOwnerDocument().createComment(currentNode.getNodeValue());
                    rootElement.appendChild(comment);
                }
                else if (currentNode.getNodeType() == Node.CDATA_SECTION_NODE)
                {
                    rootElement.appendChild(rootElement.getOwnerDocument().
                        createCDATASection(currentNode.getNodeValue()));
                }
                else if (currentNode.getNodeType() == Node.ENTITY_REFERENCE_NODE)
                {
                    EntityReference entityReference = rootElement.getOwnerDocument().createEntityReference(currentNode.getNodeName());
                    rootElement.appendChild(entityReference);
                }
            }
        }
    }

    /**
     * Is root dummy ( empty).
     * @return true if document root is dummy.
     */
    public boolean isDummyRoot()
    {
        return getName().equals(EMPTY_DOCUMENT_TAG);
    }

    /**
     * @return true if document root has child elements.
     *
     * @see #getChildNodesCount()
     *
     * @author Andrey Aleshnikov
     */
    public boolean hasChildElements()
    {
        for (Node child = xmlNode_.getFirstChild(); null != child; child = child.getNextSibling())
            if (Node.ELEMENT_NODE == child.getNodeType())
                return true;
        return false;
    }

    /**
     * @return true if document root has attributes.
     * @author Andrey Aleshnikov
     */
    public boolean hasAttributes()
    {
        return xmlNode_.hasAttributes();
    }

    /**
     * @author Andrey Aleshnikov
     */
    public String getAttributeStringValueSubst(String attributeName, String defaultValue)
    {
        String result = getAttributeStringValue(attributeName, defaultValue);
        if (result != null)
        {
            try
            {
                result = UtilityFunctions.substituteEnvironmentVariables(result);
            }
            catch (ParseException e)
            {
//                result = defaultValue;
                handleParseException(e);
            }
        }
        return result;
    }

    /**
     * @author Andrey Aleshnikov
     */
    public String getAttributeStringValueSubst(String attributeName)
    {
        String result = getAttributeStringValue(attributeName);
        if (result != null)
        {
            try
            {
                result = UtilityFunctions.substituteEnvironmentVariables(result);
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }
        return result;
    }

    /**
     * @author Andrey Aleshnikov
     */
    public int getAttributeIntValueSubst(String attributeName, int defaultValue)
    {
        int result = defaultValue;
        String stringValue = getAttributeStringValue(attributeName, null);
        if (stringValue != null)
        {
            try
            {
                stringValue = UtilityFunctions.substituteEnvironmentVariables(stringValue);
                // Don't catch NumberFormatException
                // to reproduce behavior of XMLData.getAttributeIntValue(String, int)
                result = Integer.parseInt(stringValue);
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }

        return result;
    }

    /**
     * @author Andrey Aleshnikov
     */
    public int getAttributeIntValueSubst(String attributeName)
    {
        String stringValue = getAttributeStringValue(attributeName);
        if (stringValue != null)
        {
            try
            {
                stringValue = UtilityFunctions.substituteEnvironmentVariables(stringValue);
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }
        // Don't check for any errors
        // to reproduce behavior of XMLData.getAttributeIntValue()
        return Integer.parseInt(stringValue);
    }

    public long getAttributeLongValueSubst(String attributeName, long defaultValue)
    {
        long result = defaultValue;
        String stringValue = getAttributeStringValue(attributeName, null);
        if (stringValue != null)
        {
            try
            {
                // Don't catch NumberFormatException
                // to reproduce behavior of #getAttributeLongValue(String, long)
                result = Long.parseLong(UtilityFunctions.substituteEnvironmentVariables(stringValue));
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }

        return result;
    }

    public long getAttributeLongValueSubst(String attributeName)
    {
        String stringValue = getAttributeStringValue(attributeName);
        if (stringValue != null)
        {
            try
            {
                stringValue = UtilityFunctions.substituteEnvironmentVariables(stringValue);
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }
        // Don't check for any errors to reproduce behavior of #getAttributeLongValue()
        return Long.parseLong(stringValue);
    }

    public double getAttributeDoubleValueSubst(String attributeName, double defaultValue)
    {
        double result = defaultValue;
        String stringValue = getAttributeStringValue(attributeName, null);
        if (stringValue != null)
        {
            try
            {
                // Don't catch NumberFormatException
                // to reproduce behavior of #getAttributeIntValue(String, double)
                result = Double.parseDouble(UtilityFunctions.substituteEnvironmentVariables(stringValue));
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }

        return result;
    }

    public double getAttributeDoubleValueSubst(String attributeName)
    {
        String stringValue = getAttributeStringValue(attributeName);
        if (stringValue != null)
        {
            try
            {
                stringValue = UtilityFunctions.substituteEnvironmentVariables(stringValue);
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }
        // Don't check for any errors to reproduce behavior of #getAttributeDoubleValue()
        return Double.parseDouble(stringValue);
    }

    public boolean getAttributeBooleanValueSubst(String attributeName, boolean defaultValue)
    {
        boolean result = defaultValue;
        String stringValue = getAttributeStringValue(attributeName, null);
        //have to check for null,
        //otherwise UtilityFunctions#substituteEnvironmentVariables() may throw NPE
        if (stringValue != null)
        {
            try
            {
                result = Boolean.parseBoolean(UtilityFunctions.substituteEnvironmentVariables(stringValue));
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }

        return result;
    }

    public boolean getAttributeBooleanValueSubst(String attributeName)
    {
        String stringValue = getAttributeStringValue(attributeName);
        //have to check for null,
        //otherwise UtilityFunctions#substituteEnvironmentVariables() may throw NPE
        if (stringValue != null)
        {
            try
            {
                stringValue = UtilityFunctions.substituteEnvironmentVariables(stringValue);
            }
            catch (ParseException e)
            {
                handleParseException(e);
            }
        }

        return Boolean.parseBoolean(stringValue);
    }

    public <E extends Enum<E>> E getAttributeEnumValue(final String attributeName, final Class<E> clazz)
    {
        return Enum.valueOf(clazz, getAttributeStringValue(attributeName));
    }

    public <E extends Enum<E>> E getAttributeEnumValueNoThrow(final String attributeName, final E defaultValue)
    {
        try
        {
            return getAttributeEnumValue(attributeName, defaultValue);
        }
        catch (IllegalArgumentException e)
        {
            GlobalSystem.getLogFacility().warning().writes(getElementUrlWithPosHint(), attributeName, "->", e);
            return defaultValue;
        }
    }

    public <E extends Enum<E>> E getAttributeEnumValue(final String attributeName, final E defaultValue)
    {
        final String value = getAttributeStringValue(attributeName, null);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        return Enum.valueOf(defaultValue.getDeclaringClass(), value);
    }

    public <E extends Enum<E>> E getAttributeEnumValueIgnoreCase(final String attributeName, final E defaultValue)
    {
        final String value = getAttributeStringValue(attributeName, null);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        final Class<E> declaringClass = defaultValue.getDeclaringClass();
        for (E enumValue : declaringClass.getEnumConstants())
        {
            if (enumValue.name().equalsIgnoreCase(value))
                return enumValue;
        }
        return Enum.valueOf(declaringClass, value);
    }

    /**
     * @see #adjustForIRURISyntax(String)
     */
    protected static String toSystemId(File f)
    {
        return f.toURI().toASCIIString(); // as in javax.xml.parsers.DocumentBuilder.parse(File)
    }

    protected static InputSource createInputSource(String dataString, byte[] dataBytes, String urlConfigInfoFile)
    {
        InputSource is = null != dataString
                ? new InputSource(new StringReader(dataString))
                : new InputSource(new ByteArrayInputStream(dataBytes));
        if (NO_SYSTEM_ID != urlConfigInfoFile
                && null != urlConfigInfoFile) // readExternal
            // TODO it makes no sence (but causes no errors so far) for directory://
            // resources. We can't just add urlConfigInfoFile.startsWith("directory://")
            // check since not all directory resource pathes have such prefix.
            // Ideally we should somehow determine if the resource was actually loaded
            // from Directory.
            is.setSystemId(toSystemId(new File(urlConfigInfoFile)));
        return is;
    }

    /**
     * <b>WARNING</b>: all {@code documentType} data except {@code name} and
     * {@code PublicID} will be lost.
     *
     * @author Andrey Aleshnikov
     */
    public void setSystemDtdID(String dtdSystemID)
    {
        Document doc = xmlNode_.getOwnerDocument();
        DocumentType doctype = doc.getDoctype();
        DOMImplementation impl = doc.getImplementation();
        if (null == doctype)
        {
            doctype = impl.createDocumentType(xmlNode_.getNodeName(), null, dtdSystemID);
            doc.insertBefore(doctype, doc.getFirstChild());
        }
        else
        {
            DocumentType newDoctype = impl.createDocumentType(doctype.getName(), doctype.getPublicId(), dtdSystemID);
            doc.replaceChild(newDoctype, doctype);
        }
    }

    private static Element createEmptyRootElement()
    {
        return createDocument(EMPTY_DOCUMENT_TAG).getDocumentElement();
    }

    private void saveAttributesOrder(Document doc, List<AttrInfo[]> attrsOrderInfo, List<Position> elemPositions) // TODO move
    {
        DocumentTraversal traversal = (DocumentTraversal)doc;
        Node root = doc.getDocumentElement();
        int whattoshow = NodeFilter.SHOW_ELEMENT;
        NodeFilter nodefilter = null;
        boolean expandreferences = false;
        TreeWalker walker = traversal.createTreeWalker(root,
                whattoshow,
                nodefilter,
                expandreferences);
        Iterator<AttrInfo[]> it = attrsOrderInfo.iterator();
        Iterator<Position> ln = elemPositions.iterator();

        for (Element el = (Element) walker.getCurrentNode();
                null != el;
                el = (Element) walker.nextNode())
        {
            AttrInfo[] thisAttrInfo = it.next();
            Position elemPosition = ln.next();
            NamedNodeMap attributes = el.getAttributes();

            for (int i = 0; i < attributes.getLength(); i++)
            {
                Attr attr = (Attr) attributes.item(i);
                String namespace = attr.getNamespaceURI();
                String localName = attr.getLocalName();
                String qname = attr.getName();
                for (int j = 0; j < thisAttrInfo.length; j++)
                {
                    AttrInfo attrInfo = thisAttrInfo[j];
                    if (UtilityFunctions.eq(qname, attrInfo.qname_) ||
                            (UtilityFunctions.eq(namespace, attrInfo.namespace_) && UtilityFunctions.eq(localName, attrInfo.localName_)))
                        // TODO namespace + local name = qname
                        AttrIndexUserData.Instance_.set(attr, j);
                }
            }
            MaxAttrIndexUserData.Instance_.set(el, attributes.getLength() - 1);
            ElemPositionUserData.Instance_.set(el, elemPosition);
        }
    }

    public void internalizeStrings(XMLDataStringPools.IStringPool pool)
    {
        Document doc = xmlNode_.getOwnerDocument();
        DocumentTraversal traversal = (DocumentTraversal)doc;
        TreeWalker walker = traversal.createTreeWalker(xmlNode_,
                NodeFilter.SHOW_ELEMENT,
                null, // no filter
                false); // do no expand entity references
        for (Element el = (Element) walker.getCurrentNode();
                null != el;
                el = (Element) walker.nextNode())
        {
            NamedNodeMap attributes = el.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++)
            {
                Node attr = attributes.item(i);
                attr.setNodeValue(pool.get(attr.getNodeValue()));
            }
        }
    }

    /**
     * @author AlexO
     */
    public void substituteEnvVariables()
    {
        Document doc = xmlNode_.getOwnerDocument();
        DocumentTraversal traversal = (DocumentTraversal)doc;
        TreeWalker walker = traversal.createTreeWalker(xmlNode_,
                NodeFilter.SHOW_ELEMENT,
                null, // no filter
                false); // do no expand entity references
        for (Element el = (Element) walker.getCurrentNode();
                null != el;
                el = (Element) walker.nextNode())
        {
            NamedNodeMap attributes = el.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++)
            {
                Node attr = attributes.item(i);
                String attrName = attr.getNodeName();
                String attrVal = attr.getNodeValue();
                if (!attrVal.isEmpty())
                {
                    String substitutedVal = tryToResolveEnvVariables(attrVal);
                    if (!Objects.equals(substitutedVal, attrVal))
                    {
                        attr.setNodeValue(substitutedVal);
                    }
                }
            }
        }
        setResolveVariables(false);
    }


    /**
     * Converts string representation of {@link URI}) to a String that
     * is properly treated by {@link ResourceLoader} and {@link DirectorySystem}.
     *
     * @author Andrey Aleshnikov
     * @see #toSystemId(File)
     */
    private String adjustForIRURISyntax(String s)
    {
        // 1) Java URIs have "file:/" prefix, while InfoReach classes require "file://".
        // 2) On both Windows and Linux File.toURI().toASCIIString() will
        // result in "file:/"-prefixed String.
        // But on *nix systems the leadind slash should NOT be stripped before
        // passing to File(String) ctor
        // as it's a part of path:
        // new File("/home/me/test.txt").toURI().toASCIIString() -> file:/home/me/test.txt
        // new File("./test.txt").toURI().toASCIIString() -> file:/home/me/test.txt

        String result = s;
        if (s.startsWith("file:/"))
            try
            {
                    result = new File(new URI(s)).getCanonicalPath();
            }
            catch (Exception e)
            {
                // something went wrong - ignore as exception will be thrown later anyway
            }

        return result;
    }

    public XMLNoSuchAttributeException createXMLNoSuchAttributeException(String attrName)
    {
        String resourcePath = getUrlConfigInfoFileHint();
        Position position = ElemPositionUserData.Instance_.get(xmlNode_);
        return new XMLNoSuchAttributeException(attrName, xmlNode_, resourcePath, position);
    }

// Inner Classes /////////////////////////////////////

    /**
     * Is used by getAttributeList() method to populate the returned list
     * with entities of this class.
     */
    public static class XMLAttrNameValuePair
        implements Entry<String, String>
    {
        XMLAttrNameValuePair(String key, String value)
        {
            key_ = key;
            value_ = value;
        }

        @Override
        public String getKey()
        {
            return key_;
        }

        @Override
        public String getValue()
        {
            return value_;
        }

        @Override
        public String setValue(String obj)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean equals(Object obj)
        {
            if(!(obj instanceof Map.Entry))
            {
                return false;
            }
            else
            {
                Entry<?,?> entry = (Entry<?,?>)obj;
                return (key_ != null ? key_.equals(entry.getKey()) : entry.getKey() == null) && (value_ != null ? value_.equals(entry.getValue()) : entry.getValue() == null);
            }
        }

        @Override
        public int hashCode()
        {
            return (value_ != null ? value_.hashCode() : 0);
        }

        public String toString()
        {
            return getClass().getName() + '[' + key_ + "->" + value_ + ']';
        }

        String key_;
        String value_;

        public void accept(BiConsumer<String, String> consumer)
        {
            consumer.accept(key_, value_);
        }
    }

    public interface IResourceResolver
    {
        XMLData[] resolveResources(String resource) throws BadResourceException;
    }

    public static class DefaultResolver implements IResourceResolver
    {
        @Override
        public XMLData[] resolveResources(String resource) throws BadResourceException
        {
            String[] resources;
            try
            {
                resources = ResourceLoader.getInstance().findResources(resource);
                XMLData[] data = new XMLData[resources.length];
                for (int i=0; i<resources.length; i++)
                {
                    data[i] = new XMLData(resources[i]);
                }
                return data;
            }
            catch (ResourceLoaderException ex)
            {
                throw new BadResourceException(resource, ex);
            }
        }
    }

    public static class CachingResolver implements IResourceResolver
    {
        private final IResourceResolver delegate_;
        private final Map<String, XMLData[]> cache_ = new HashMap<>();

        public CachingResolver()
        {
            this(new DefaultResolver());
        }

        public CachingResolver(IResourceResolver delegate)
        {
            delegate_ = delegate;
        }

        @Override
        public XMLData[] resolveResources(String resource) throws BadResourceException
        {
            try
            {
                return cache_.computeIfAbsent(resource, function(delegate_::resolveResources));
            }
            catch (RuntimeWrapperException e)
            {
                Throwable cause = e.getCause();
                if (cause instanceof BadResourceException) throw (BadResourceException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw e;
            }
        }
    }

    public interface IResourceSaver
    {
        void saveResource(String name, XMLData content);
    }

//    private static class DefaultSaver implements IResourceSaver
//    {
//        @Override
//        public void saveResource(String name, XMLData content)
//        {
//            try
//            {
//                ResourceLoader.save(name, UtilityFunctions.stringToBytes(content.stringValue(true)));
//            }
//            catch (ResourceLoaderException ex)
//            {
//                error().write(ex);
//            }
//        }
//    }

    /**
     * is used by getDocumentPartsByTagName(String)
     */

    protected static class DocumentPartsIterator implements ISizedIterator<XMLData>
    {
        private final List<Element> els_;
        protected final Iterator<Element> listIterator_;

        public DocumentPartsIterator(List<Element> els)
        {
            els_ = els;
            listIterator_ = els.iterator();
        }

        @Override
        public int size()
        {
            return els_.size();
        }

        @Override
        public boolean hasNext()
        {
            return listIterator_.hasNext();
        }

        @Override
        public XMLData next()
        {
            return new XMLData(listIterator_.next());
        }

        @Override
        public void remove()
        {
            listIterator_.remove();
        }
    }

    /**
     * Converts XMLData to the xml string
     * @param prettyOutput - if true, output string will be
     *                               formatted (multiline &amp; indented), else
     *                               will be single line
     * @param addVersionPrefix determines add or not "&lt;?xml version='1.0'?&gt;"
     * @return String - xml string.
     *
     * @author Bogdan Vaskov
     * @author Vadim Hmelik
     */
    public String stringValue(boolean prettyOutput, boolean addVersionPrefix)
    {
        return stringValue(prettyOutput? FORMATTING_LEVEL_SMART : FORMATTING_LEVEL_NONE,
                           addVersionPrefix);
    }

    public String stringValue(int formattingLevel, boolean addVersionPrefix)
    {
        return stringValue(formattingLevel, addVersionPrefix, -1);
    }

    public String stringValue(int formattingLevel, boolean addVersionPrefix, int indent)
    {
        return stringValue(formattingLevel, addVersionPrefix, indent, false);
    }

    /**
     * Converts XMLData to the xml string
     * @param formattingLevel  level of formatting. E.g.
     *                         XMLData.FORMATTING_LEVEL_NONE - no formatting (no EOLs, no spaces)
     *                         XMLData.FORMATTING_LEVEL_ONE_LINE - all attributes will be
     *                             in the same line as element name
     *                         XMLData.FORMATTING_LEVEL_MULTI_LINE - every attribute will
     *                             be in the new line
     * @param addVersionPrefix determines add or not "&lt;?xml version='1.0'?&gt;"
     * @return String - xml string.
     *
     * @author Bogdan Vaskov
     * @author Vadim Hmelik
     */
    public String stringValue(int formattingLevel, boolean addVersionPrefix, int indent, boolean skipComments)
    {
        StringBuilder buf = new StringBuilder();

        int xmlVersionStart = -1;
        String xmlVersion = null;
        if (addVersionPrefix)
        {
            buf.append("<?xml version=\"");
            xmlVersionStart = buf.length();
            xmlVersion = xmlNode_.getOwnerDocument().getXmlVersion();
            buf.append(xmlVersion);
            if (3 != buf.length() - xmlVersionStart) // 1.0 or 1.1
                throw new AssertionError("XML version length is not 3 characters: " + xmlVersion);
            buf.append("\"?>");
        }
        DocumentType doctype = xmlNode_.getOwnerDocument().getDoctype();
        if (null != doctype)
        {
            if ((formattingLevel > XMLData.FORMATTING_LEVEL_NONE) && (buf.length() > 0))
            {
                buf.append(EOL);
            }
            appendDocumentType(doctype, buf);
        }
        XMLPrettyOutputBuilder builder = new XMLPrettyOutputBuilder(buf, indent, formattingLevel)
        {
            @Override
            public void commentMet(String comment)
            {
                if (skipComments)
                    return;

                super.commentMet(comment);
            }
        };
        builder.setCheckForXML11RestrictedChars(addVersionPrefix && "1.0".equals(xmlVersion));
        new XMLParser(builder).parseData(this);
        if (addVersionPrefix)
        {
            //assume that extra EOL is needed only along with the version prefix
            builder.applyXMLBeautifyBackwardCompatWorkaround();
        }

        // XML 1.1 character entities are allowed to reference more chars than those of XML 1.0.
        // So, we switch to XML 1.1 if such chars are found.
        // See:
        //  - http://www.w3.org/TR/xml/#NT-Char
        //  - http://www.w3.org/TR/xml11/#NT-Char
        //  - http://www.w3.org/TR/xml11/#NT-RestrictedChar
        if (addVersionPrefix && "1.0".equals(xmlVersion) && builder.hasXML11RestrictedChars())
            buf.setCharAt(xmlVersionStart + 2, '1');

        return buf.toString();
    }

    protected static StringBuilder appendDocHeader(final StringBuilder buf, final XMLData doc)
    {
        buf.append("<?xml version=\"");
        final int xmlVersionStart = buf.length();
        final String xmlVersion = doc.xmlNode_.getOwnerDocument().getXmlVersion();
        buf.append(xmlVersion);
        if (3 != buf.length() - xmlVersionStart) // 1.0 or 1.1
            throw new AssertionError("XML version length is not 3 characters: " + xmlVersion);
        buf.append("\"?>");
        return buf;
    }

    public String stringValue(int formattingLevel)
    {
        return stringValue(formattingLevel, true);
    }

    public String stringValue(int formattingLevel, int indent)
    {
        return stringValue(formattingLevel, true, indent);
    }

    /**
     * Compares document root of this XML object against
     * document root of specified object.
     * The following node types are processed recursively:
     *    - Node.ELEMENT_NODE;
     *    - Node.CDATA_SECTION_NODE;
     * @param obj the XML object to compare document with; can be {@code null}.
     * @author Vadim Hmelik
     */
    public boolean equalsDocument(@Nullable XMLData obj)
    {
        if (obj == null)
            return false;
        return this != obj
            ? areDocumentsEqual(xmlNode_, obj.xmlNode_)
            : true;
    }

    /**
     * See XML 1.0 spec link give below for restrictions.
     *
     * @see <a href="http://www.w3.org/TR/REC-xml/#sec-comments">http://www.w3.org/TR/REC-xml/#sec-comments</a>
     * @throws IllegalArgumentException
     *             in case the comment text does not comply with XML 1.0 spec.
     * @author Andrey Aleshnikov
     */
    public void appendComment(String text)
    {
        if (text.contains("--"))
            throw new IllegalArgumentException("\"--\" (double-hyphen) is not allowed in XML comments"
                    + ", but \"" + text + "\" was provided");
        if (text.endsWith("-"))
            throw new IllegalArgumentException("XML comments must not end with \"-\" (hyphen)"
                    + ", but \"" + text + "\" was provided");
        for (int offset = 0; offset < text.length();)
        {
            int codepoint = text.codePointAt(offset);
            if (!XMLDataUtil.isChar(codepoint))
                throw new IllegalArgumentException("codepoint " +  Integer.toHexString(codepoint)
                        + "(" + new String(Character.toChars(codepoint)) + ")"
                        + " is not allowed in XML comments"
                        + ", but \"" + text + "\" was provided");
            offset += Character.charCount(codepoint);
        }
        xmlNode_.appendChild(xmlNode_.getOwnerDocument().createComment(text));
    }

    private static boolean areDocumentsEqual(Node node1, Node node2)
    {
        if (!node1.getNodeName().equals(node2.getNodeName()))
            return false;

        if (node1.getNodeType() != node2.getNodeType())
            return false;

        if (node1.getNodeType() == Node.TEXT_NODE && !node1.getTextContent().trim().equals(node2.getTextContent().trim()))
            return false;

        NamedNodeMap nodesMap1 = node1.getAttributes();
        NamedNodeMap nodesMap2 = node2.getAttributes();
        int itemCount1 = nodesMap1 != null ? nodesMap1.getLength() : 0;
        int itemCount2 = nodesMap2 != null ? nodesMap2.getLength() : 0;
        if (itemCount1 != itemCount2)
        {
            return false;
        }
        for (int i = 0; i < itemCount1; i++)
        {
            Node attrNode1 = nodesMap1.item(i);
            Node attrNode2 = nodesMap2.getNamedItem(attrNode1.getNodeName());

            if (attrNode2 == null ||
                !attrNode2.getNodeValue().equals(attrNode1.getNodeValue()))
            {
                return false;
            }
        }

        Node sibling1 = getNextComparableNode(node1.getFirstChild());
        Node sibling2 = getNextComparableNode(node2.getFirstChild());
        while (sibling1 != null)
        {
            if (sibling2 != null && areDocumentsEqual(sibling1, sibling2))
            {
                sibling1 = getNextComparableNode(sibling1.getNextSibling());
                sibling2 = getNextComparableNode(sibling2.getNextSibling());
            }
            else
            {
                return false;
            }
        }
        return sibling2 == null;
    }

    private static Node getNextComparableNode(Node node)
    {
        Node tmpNode = node;
        while (tmpNode != null &&
            tmpNode.getNodeType() != Node.CDATA_SECTION_NODE &&
            (tmpNode.getNodeType() != Node.TEXT_NODE || !isComparableTextNode(tmpNode)) &&
            tmpNode.getNodeType() != Node.ELEMENT_NODE)
        {
            tmpNode = tmpNode.getNextSibling();
        }
        return tmpNode;
    }

    private static boolean isComparableTextNode(Node node)
    {
        String text = node.getTextContent();
        return !text.trim().isEmpty();
    }

    public static String resolveEnvVariables(String value) throws ParseException
    {
        return UtilityFunctions.substituteEnvironmentVariables(value,ENV_VARIABLE_PREFIX,ENV_VARIABLE_SUFFIX);
    }

    /**
     * Resolves environment variables if <code>resolveVariables _</code> is <code>true</code>.
     * @author Vladimir Gadyatskiy
     */

    private String tryToResolveEnvVariables(String value)
    {
        if (resolveVariables_)
        {
            try
            {
                return resolveEnvVariables(value);
            }
            catch(ParseException ex)
            {
                error().write(ex.toString());
            }
        }
        return value;
    }

    private void handleParseException(ParseException e)
    {
        if (GlobalSystem.isInitialized())
        {
            error().write(e);
        }
        else
        {
            e.printStackTrace();
        }
    }

    /**
     * Converts a {@link DocumentType} node to String. Everything but SystemID,
     * PublicID and internal subset is ignored.
     *
     * <p>
     * We need this since currently (JAXP 1.6.0)
     * {@link javax.xml.transform.Transformer} would produce empty string for
     * such nodes, and {@link org.w3c.dom.ls.LSSerializer} just throws a
     * "The node could not be serialized" {@code LSException}.
     * </p>
     *
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-doctypedecl">http://www.w3.org/TR/REC-xml/#NT-doctypedecl</a>
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-ExternalID">http://www.w3.org/TR/REC-xml/#NT-ExternalID</a>
     *
     * @author Andrey Aleshnikov
     */
    private static void appendDocumentType(DocumentType doctype, StringBuilder buff)
    {
        buff.append("<!DOCTYPE ").append(doctype.getName());
        String systemID = doctype.getSystemId();
        String publicID = doctype.getPublicId();
        String internalSubset = doctype.getInternalSubset();
        if (publicID != null)
        {
            buff.append(" PUBLIC \"").append(publicID).append('"');
            if (systemID != null) buff.append(" \"").append(systemID).append('"');
        }
        else if(systemID != null)
        {
            buff.append(" SYSTEM \"").append(systemID).append('"');
        }

        if (internalSubset != null)
        {
            buff.append(" [ ");
            XMLDataUtil.appendStringWithFixedEOLs(internalSubset.trim(), buff);
            buff.append(XMLPrettyOutputBuilder.EOL);
            buff.append(']');
        }
        buff.append('>');
    }

    public static void checkAttributeAccessExt(String attributeName, String attrValue)
    {
        checkAttributeAccess(NO_SYSTEM_ID, null, attributeName, attrValue);
    }

    private void checkAttributeAccess(String attributeName, String attrValue)
    {
        checkAttributeAccess(urlConfigInfoFile_, xmlNode_, attributeName, attrValue);
    }

    static void checkAttributeAccess(@Nonnull String nodeSystemId, Element node, String attributeName, String attrValue)
    {
        String stack = "";
        if (dumpStackForAttribute(attributeName))
        {
            stack = ThreadUtils.getCurrentThreadStack();
        }

        if (!stack.isEmpty() || logAccessForAllAttributes())
        {
            String systemId = nodeSystemId.equals(NO_SYSTEM_ID) ? "?" : nodeSystemId;
            String path = (node == null) ? "?" : XMLDataUtil.getRootToNodePath(node);

            error().write(format("Accessed XML attribute '%s://%s/%s=%s' %s", systemId, path, attributeName, attrValue, stack));
        }
    }

    private static boolean dumpStackForAttribute(String attributeName) {
        return DumpStackForAttributes_ != null && Arrays.binarySearch(DumpStackForAttributes_, attributeName) >= 0;
    }

    private static boolean logAccessForAllAttributes()
    {
        return LogAccessForAllAttributes_;
    }

    /**
     * @see #getElement(XMLDataPathElement, boolean)
     */
    private Element getElement(XMLDataPath path, Element searchingElement, boolean searchRecursively)
    {
        Collection<Element> elements = path.getElements(searchingElement, searchRecursively);

        if (elements.size() > 1)
        {
            String resourceInfo = urlConfigInfoFile_ != NO_SYSTEM_ID ? urlConfigInfoFile_ : this.toString();
            warning().write(
                    "Found multiple elements for path " + path
                    + ". The first one will be used. XML resource: " + resourceInfo,
                    RunningProfile.isConfigurationChecked() ? new Exception() : null);
        }

        return elements.isEmpty() ? null : elements.iterator().next();
    }

    private static void logParseError(XMLBadDataException ex)
    {
        String exceptionMessage = ex.getMessage();
        StringBuilder messageToLog = new StringBuilder(exceptionMessage);
        Throwable cause = ex.getCause();
        if (null != cause)
        {
            String causeMessage = cause.getMessage();
            if (null != causeMessage
                    // in many cases cause's msg is already integrated in
                    // exception's message
                    && !exceptionMessage.contains(causeMessage))
            {
                if ('.' != messageToLog.charAt(messageToLog.length() - 1))
                    messageToLog.append('.');
                messageToLog.append(" Cause: ").append(causeMessage);
            }
        }
        error().write(messageToLog);
    }

    public static boolean isMultilineElement(Element e)
    {
        Position position = ElemPositionUserData.Instance_.get(e);
        return position != null && position.multi_;
    }
// Inner classes


    private static class DTDDetector extends DefaultHandler2
    {
        boolean foundDTD_ = false;

        @Override
        public void startDTD(String name, String publicId, String systemId)
                throws SAXException
        { foundDTD_ = true; }
    }

    protected static class AttrInfo
    {
        final String namespace_;
        final String localName_;
        final String qname_;

        AttrInfo(String namespace, String localName, String qname)
        {
            namespace_  = namespace;
            localName_ = localName;
            qname_ = qname;
        }
    }

    protected static class Position
    {
        final int line_;
        final int column_;
        final boolean multi_;

        Position(int line, int column, boolean multi)
        {
            line_ = line;
            column_ = column;
            multi_ = multi;
        }

        @Override
        public String toString()
        {
            return line_ + ":" + column_;
        }
    }

    public static class CopyOnCloneOrImportUserDataHandler implements UserDataHandler
    {
        public static final CopyOnCloneOrImportUserDataHandler Instance_ = new CopyOnCloneOrImportUserDataHandler();

        @Override
        public void handle(short operation, String key, Object data, Node src, Node dst)
        {
            if (UserDataHandler.NODE_CLONED == operation || UserDataHandler.NODE_IMPORTED == operation)
                dst.setUserData(key, data, this);
        }
    }

    static class AttrIndexUserData
    {
        static final AttrIndexUserData Instance_ = new AttrIndexUserData();

        Integer get(Attr attr)
        {
            return (Integer) attr.getUserData(ATTR_INDEX);
        }

        void set(Attr attr, int index)
        {
            attr.setUserData(ATTR_INDEX, index, CopyOnCloneOrImportUserDataHandler.Instance_);
        }
    }

    static class MaxAttrIndexUserData
    {
        static final MaxAttrIndexUserData Instance_ = new MaxAttrIndexUserData();

        Integer get(Element el)
        {
            return (Integer) el.getUserData(ATTR_INDEX_MAX);
        }

        void set(Element el, int index)
        {
            el.setUserData(ATTR_INDEX_MAX, index, CopyOnCloneOrImportUserDataHandler.Instance_);
        }
    }

    static class ElemPositionUserData
    {
        static final ElemPositionUserData Instance_ = new ElemPositionUserData();

        Position get(Element el)
        {
            return (Position) el.getUserData(ELEM_POSITION);
        }

        void set(Element el, Position position)
        {
            el.setUserData(ELEM_POSITION, position, CopyOnCloneOrImportUserDataHandler.Instance_);
        }
    }

    static class ResourcePathUserData
    {
        static final ResourcePathUserData Instance_ = new ResourcePathUserData();

        String get(Document doc)
        {
            return (String) doc.getUserData(RESOURCE_PATH);
        }

        void set(Document doc, String resourcePath)
        {
            doc.setUserData(RESOURCE_PATH, resourcePath, CopyOnCloneOrImportUserDataHandler.Instance_);
        }
    }

    public static void main(String[] args)
    {
        if (args.length != 1)
        {
            System.out.println("Usage: java " + XMLData.class.getName() + " <xml resource to read>");
        }
        else
        {
            String resource = args[0];
            XMLData xmlData = new XMLData(resource);
            System.out.println(xmlData.toString());
        }
    }
}
