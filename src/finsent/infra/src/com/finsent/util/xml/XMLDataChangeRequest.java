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
 * @author Alexander Prozor
 *
 */

package com.finsent.util.xml;

import com.finsent.util.ISizedIterator;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.Iterator;

/**
 * After filling this class will be passed to
 * changeData method of XMLData class
 * @author Alexander Prozor
 */
public class XMLDataChangeRequest extends XMLData
{
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	// EZ080400: We need this strings as constants!
    public final static String ATTR_PATH = "Path";
    public final static String ATTR_NEW_ELEMENT = "NewElement";
    public final static String ATTR_ATTRIBUTE_LIST = "AttributeList";
    public final static String ATTR_ATTRIBUTE_DESC = "AttributeDesc";
    public final static String ATTR_ATTRIBUTE_NAME = "attribute_name";
    public final static String ATTR_PATH_ELEMENT = "PathElement";
    public final static String ATTR_TAG = "tag";
    public final static String ATTR_KEY_ATTRIBUTE = "key_attribute";
    public final static String ATTR_KEY_VALUE = "key_value";
    public final static String TAG_ROOT = "ChangeRequest";
    public final static String TAG_ADD_INSTRUCTION = "AddInstruction";
    public final static String TAG_DELETE_INSTRUCTION = "DeleteInstruction";
    public final static String TAG_REPLACE_INSTRUCTION = "ReplaceInstruction";

    public XMLDataChangeRequest()
    {
        setDocumentRoot(createDocument(TAG_ROOT).getDocumentElement());
    }
    /**
     * @param position - specify elements position will be changed
     * @param data - attributes to deleted, if null - will be removing
     * whole element
     * @author Alexander Prozor
     */
    public void addDeleteInstruction(XMLDataPath position, XMLData data)
    {
        Element changeRequest = createChangeRequestInstruction(TAG_DELETE_INSTRUCTION, position, data);
        getDocumentRoot().appendChild(changeRequest);
    }
    /**
     * @param position - specify elements position will be changed
     * @author Alexander Prozor
     */
    public void addAddInstruction(XMLDataPath position, XMLData data)
    {
        Element changeRequest = createChangeRequestInstruction(TAG_ADD_INSTRUCTION, position, data);
        getDocumentRoot().appendChild(changeRequest);
    }
    /**
     * @param position - specify elements position will be changed
     * @author Alexander Prozor
     */
    public void addReplaceInstruction(XMLDataPath position, XMLData data)
    {
        Element changeRequest = createChangeRequestInstruction(TAG_REPLACE_INSTRUCTION, position, data);
        getDocumentRoot().appendChild(changeRequest);
    }
    /**
     * Constructs <ChangeRequest> element ( that contains <Path> ) to be
     * stored in this class for later changed data in other class
     * @param position - specify elements position will be changed
     * @param data - additional changes specified
     * @return <ChangeRequest> element
     * @author Alexander Prozor
     */
    Element createChangeRequestInstruction(String instruction, XMLDataPath position, XMLData data)
    {
        Document ownerDoc = getDocumentRoot().getOwnerDocument();
        Element changeRequest = ownerDoc.createElement(instruction);
        Element path = getXMLPath(position);
        // add path to request
        changeRequest.appendChild(path);
        // add data to request
        if(data != null)
        {
            Element newElement = ownerDoc.createElement(ATTR_NEW_ELEMENT);
            Element rootData = (Element)data.getDocumentRoot( );

            // EZ080400:
            // We need ability to change just some attributes of XMLData, not
            //  elements of it.
            if(data.isDummyRoot())
            {
                for (AttrsIterator it = new AttrsIterator(rootData); it.hasNext(); )
                {
                    Attr attr = it.next();
                    String attrName = attr.getNodeName();
                    String attrValue = attr.getNodeValue();
                    XMLData.setAttributeValueImpl(newElement, attrName, attrValue);
                }
                
                for (Iterator<Node> it = new ChildNodesIterator(rootData); it.hasNext(); )
                {
                    Node currentNode = it.next();
                    if (currentNode.getNodeType() == Node.ELEMENT_NODE)
                        XMLData.copy(currentNode, newElement);
                }
            }
            else
            {
                // to do - store original document
                ownerDoc.adoptNode(rootData);
                newElement.appendChild(rootData);
            }
            changeRequest.appendChild(newElement);
        }
        return changeRequest;
    }
    /**
     * Constructs <Path> element ( that contains <PathElement> ) to be
     * stored in this class for later changed data in other class
     * @param position - specify elements position will be changed
     * @return XML presentation of specified position
     * @author Alexander Prozor
     */
    Element getXMLPath(XMLDataPath position)
    {
        ISizedIterator pathElements = position.elements();
        Document ownerDoc = getDocumentRoot().getOwnerDocument();
        Element xmlPath = ownerDoc.createElement(ATTR_PATH);
        Element xmlPathElement = null;
        while(pathElements.hasNext())
        {
            XMLDataPathElement pathElement = (XMLDataPathElement)pathElements.next();
            xmlPathElement = ownerDoc.createElement(ATTR_PATH_ELEMENT);
            // copy attributes from pathElement
            XMLData.setAttributeValueImpl(xmlPathElement, ATTR_TAG, pathElement.getTagName());
            XMLData.setAttributeValueImpl(xmlPathElement, ATTR_KEY_ATTRIBUTE, pathElement.getAttributeName());
            XMLData.setAttributeValueImpl(xmlPathElement, ATTR_KEY_VALUE, pathElement.getAttributeValue());
            // add PathElement to Path
            xmlPath.appendChild(xmlPathElement);
        }
        return xmlPath;
    }
}
