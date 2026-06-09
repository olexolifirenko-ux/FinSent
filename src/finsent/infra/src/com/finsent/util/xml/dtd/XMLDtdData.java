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


package com.finsent.util.xml.dtd;

import com.finsent.util.ISizedIterator;
import com.finsent.util.xml.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.TreeWalker;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;


/**
 * This class extends XMLData functionality ( allows to work with
 * DTD information)
 * @author Alexander Prozor
 * @author Andrey Aleshnikov
 */
public class XMLDtdData extends XMLData
{
	private static final long serialVersionUID = 1L;
	private XMLDtd xmlDtd_;

    // constructors
    public XMLDtdData()
    {
        super();
    }

    public XMLDtdData(String urlConfigInfoFile)
    {
        super(urlConfigInfoFile);
    }

    public XMLDtdData(Reader reader)
    {
        super(reader);
    }

    // AA: do not remove as this is used by /InfoReach/Projects/TMS/trunk/test/cfgchecker/src/com/inforeach/cfgchecker/FIXMetadataHelper.groovy
    public XMLDtdData(File file) throws IOException
    {
        super(toSystemId(file));
    }

    public XMLDtdData(Element rootElement)
    {
        super(rootElement);
    }

    public XMLDtdData(Reader reader, ErrorHandler errorHandler)
    {
        super(reader, true, errorHandler);
    }

 // class mutator
    /**
     * Restore element's content from string
     * @author Alexander Prozor
     */
    public static XMLData valueOf(String buffer)
    {
        Reader r = new StringReader(buffer);
        return new XMLDtdData(r);
    }

// instance mutators
    /**
     * Removes all empty text nodes from xml tree
     * @author  Alexander Prozor
     */
    public void normalizeData()
    {
        DocumentTraversal traversal = (DocumentTraversal)xmlNode_.getOwnerDocument();
        TreeWalker walker = traversal.createTreeWalker(xmlNode_, 
                NodeFilter.SHOW_ALL, 
                null, 
                false);
        Node node;
        ArrayList<Node> removedTextNodes = new ArrayList<Node>(1);

        for (node = walker.getCurrentNode();
            node != null;
            node = walker.nextNode())
        {
            if (node instanceof Text)
            {
                String data = ((Text)node).getData();
                if( (data.length() == 1) && (XMLDataUtil.isSpace(data.charAt(0))) )
                {
                    removedTextNodes.add(node);
                }
            }
        }
        // delete text nodes
        for(int i=0; i<removedTextNodes.size(); i++)
        {
            Text deleteNode = (Text)removedTextNodes.get(i);
            Node parent = deleteNode.getParentNode();
            if(parent != null)
                parent.removeChild(deleteNode);
        }
    }
    
    /**
     * @author Andrey Aleshnikov
     */
    @Override
    protected Element parseDocument(
            String dataString,
            byte[] dataBytes,
            boolean validate,
            ErrorHandler errorHandler,
            boolean quiet) 
            throws XMLBadDataException
    {
        return super.parseDocument(dataString, dataBytes, true, errorHandler, quiet); // force validation to ensure DTD is loaded
    }
    
    /**
     * @author Andrey Aleshnikov
     */
    @Override
    protected void preParseUsingSAX(InputSource input, 
            EntityResolver entityResolver,
            DeclHandler declHandler,
            LexicalHandler lexicalHandler,
            ErrorHandler saxPreParseErrorHandler,
            final List<AttrInfo[]> attrsOrderInfoOUT,
            final List<Position> elemPositionsOUT) throws ParserConfigurationException, SAXException, IOException
    {
        xmlDtd_ =  createDtdHandler();
        super.preParseUsingSAX(input,
                entityResolver, 
                null == declHandler ? xmlDtd_ : new DeclHandlerMultiplexor(declHandler, xmlDtd_), 
                null == lexicalHandler ? xmlDtd_ : new LexicalHandlerMultiplexor(lexicalHandler, xmlDtd_),
                saxPreParseErrorHandler,
                attrsOrderInfoOUT,
                elemPositionsOUT);
    }
    
    @Override
    protected boolean validateIfNoSystemId()
    {
        return true;
    }

// instance accessors

   /**
    * Creates default DTD handler for XML parser
    * @author Sergey Bulakh
    */
    protected XMLDtd createDtdHandler()
    {
        return new XMLDtd();
    }

    /**
     * Creates XMLData class with documment root
     * finded with pathElement specified
     * @param pathElement
     * @author  Alexander Prozor
     */
    public XMLData getDocumentPart(XMLDataPathElement pathElement)
    {
        XMLDtdData documentPart = null;
        Element soughtElement = getElement(pathElement);
        if(soughtElement != null)
        {
            documentPart = new XMLDtdData(soughtElement);
            documentPart.setDtd(getDtd());
        }
        return documentPart;
    }
    /**
     * Creates XMLData class with documment root
     * finded with path specified
     * @param pathElement
     * @author  Alexander Prozor
     */
    public XMLData getDocumentPart(XMLDataPath path)
    {
        XMLDtdData documentPart = null;
        Element soughtElement = getElement(path);
        if(soughtElement != null)
        {
            documentPart = new XMLDtdData(soughtElement);
            documentPart.setDtd(getDtd());
        }
        return documentPart;
    }
    /**
     * Returns iterator of elements with specified tag name
     */
    public ISizedIterator<XMLData> getDocumentPartsByTagName(String tagName)
    {
        List<Element> els = getElementsByTagName(tagName, true, false);
        return new XMLDtdDataDocPartsIterator(els);
    }
    /**
     * Returns DTD for this document
     * @author  Alexander Prozor
     */
    public XMLDtd getDtd()
    {
        return xmlDtd_;
    }
    /**
     * Sets DTD for this document
     * @author  Alexander Prozor
     */
    public void setDtd(XMLDtd dtd)
    {
        xmlDtd_ = dtd;
    }
    
// Inner classes

    private static class XMLDtdDataDocPartsIterator extends DocumentPartsIterator
    {
        public XMLDtdDataDocPartsIterator(List<Element> els)
        {
            super(els);
        }
        
        @Override
        public XMLDtdData next()
        {
            return new XMLDtdData(listIterator_.next());
        }
    }
}