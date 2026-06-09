/*
 * Copyright (c) 1999-2000 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 1.0
 * @author VS
 * @author Alexander Prozor
 *
 */


package com.finsent.util.xml;

import com.finsent.util.ISizedIterator;
import com.finsent.util.SizedIteratorUtil;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.StringTokenizer;

/**
 * This class defines XML data path.
 * @author  VS
 * @author  Alexander Prozor
 * @version 1.0
 */
public class XMLDataPath
{

    // constructors

    public
    XMLDataPath()
    {
        pathArray = new ArrayList<>();
    }

    /**
     * Creates path from the String.
     * @param path Path in format "/tag[attributeName=value]/tag[attributeName=value]/..."
     */
    public
    XMLDataPath(String path)
    {
        StringTokenizer pathElementTokenizer = new StringTokenizer(path, "/");
        int pathElementCount = pathElementTokenizer.countTokens();
        pathArray = new ArrayList<>(pathElementCount);
        //
        String pathElement = "";
        while(pathElementTokenizer.hasMoreElements())
        {
            pathElement = pathElementTokenizer.nextToken();
            addElement(pathElement);
        }
    }

    /**
     * Creates path from array of path elements.
     * @param path - array of path elements
     */
    public
    XMLDataPath(XMLDataPathElement... path)
    {
        int pathElementCount = path.length;
        pathArray = new ArrayList<>(pathElementCount);
        for (XMLDataPathElement aPath : path)
        {
            addElement(aPath);
        }
    }

    /**
     * Construct new XMLDataPath from existing one.
     * @param path - existing path, which will be duplicated in new
     *               one.
     * @author MB
     */
    public
    XMLDataPath(XMLDataPath path)
    {
        if (path!=null)
        {
            ISizedIterator<XMLDataPathElement> pathElements = path.elements();
            pathArray = new ArrayList<>(pathElements.size());
            while (pathElements.hasNext())
            {
                addElement(pathElements.next());
            }
        }
        else  pathArray = new ArrayList<>();
    }

    // instance mutators

    /**
     * Extracts from full path element's tag name.
     * @param path - path
     * @return tag name from the path.
     * @author  Alexander Prozor
     * @version 1.0
     */
    private static String getTagName(String path)
    {
        int attrNameOffset = path.indexOf('[');
        // if attrubute doesn't present in this path
        if(attrNameOffset == -1)
            attrNameOffset = path.length();
        String tagName = path.substring(0, attrNameOffset);
        return tagName.trim();
    }

    /**
     * Extracts from full path attribute's name
     * @param path path
     * @return attribute name from the path.
     * @author  Alexander Prozor
     * @version 1.0
     */
    private String getAttributeName(String path)
    {
        int attrNameOffset = path.indexOf('[');
        // if attribute name doesn't presents
        if(attrNameOffset == -1)
            return null;
        int attrNameEnd = path.indexOf('=');
        String attrName = path.substring(attrNameOffset+1, attrNameEnd);
        return attrName.trim();
    }

    /**
     * Extracts from full path attribute's value
     * @param path path
     * @return attribute value from the path.
     * @author  Alexander Prozor
     * @version 1.0
     */
    private String getAttributeValue(String path)
    {
        int attrValueOffset = path.indexOf('=');
        // if attribute value doesn't presents
        if(attrValueOffset == -1)
            return null;
        int attrValueEnd = path.indexOf(']');
        String attrValue = path.substring(attrValueOffset+1, attrValueEnd);
        return attrValue.trim();
    }

    /**
     * Adds new element to path
     * @param pathElement path element
     */
    public void addElement(XMLDataPathElement pathElement)
    {
        pathArray.add(pathElement);
    }

    /**
     * Adds new element to path
     * @param path path element tag name
     */
    public void addElement(String path)
    {
        String tagName = getTagName(path);
        String attributeName = getAttributeName(path);
        XMLDataPathElement pathElement = null;
        if(attributeName == null)
        {
            pathElement = new XMLDataPathElement(tagName);
        }
        else
        {
            String attributeValue = getAttributeValue(path);
            pathElement = new XMLDataPathElement(tagName, attributeName, attributeValue);
        }
        addElement(pathElement);
    }

    /**
     * There are no equal paths
     * @return boolean  - always false
     */
    public boolean equals(Object path)
    {
        return false;
    }

    // instance accessors

    @Override
    public String toString()
    {
        return "XMLDataPath{" +
            "pathArray=" + pathArray +
            '}';
    }

    /**
     * Return enumeration of XMLDataPathElement
     * NOTE it return ISizedIterator because we
     * will need quick access to the last item.
     * @return ISizedIterator of XMLDataPathElement.
     * @author Alexander Prozor
     */
    public ISizedIterator<XMLDataPathElement> elements()
    {
        return SizedIteratorUtil.newSizedIterator(pathArray);
    }

    // instance variables

    /**
     * array of path elements (XMLDataPathElement).
     */
    final ArrayList<XMLDataPathElement> pathArray;

    public Collection<Element> getElements(Element searchingElement, boolean searchRecursively)
    {
        // to let to start searching from root node
        //pending AP this not allows to include in the elementPath
        // root node of DOM tree.
        Collection<Element> elements = Collections.singletonList(searchingElement);

        for (XMLDataPathElement pathElement : pathArray)
        {
            Collection<Element> newElements = new ArrayList<>();
            for (Element element : elements)
                newElements.addAll(pathElement.getElements(element, searchRecursively));
            elements = newElements;
        }

        return elements;
    }
}

