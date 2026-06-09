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
//

import com.finsent.util.GlobalSystem;
import org.w3c.dom.*;

import java.util.Iterator;
import java.util.StringTokenizer;

class ElementChanger
{
    // class stuff
    //
    static String ATTR_PATH = "Path";
    static String ATTR_NEW_ELEMENT = "NewElement";
    static String ATTR_ATTRIBUTE_LIST = "AttributeList";
    static String ATTR_ATTRIBUTE_DESC = "AttributeDesc";
    static String ATTR_ATTRIBUTE_NAME = "attribute_name";
    static String ATTR_PATH_ELEMENT = "PathElement";
    // element path tags
    static String ATTR_STEP_ORDER = "step_order";
    static String ATTR_TAG_NAME = "tag";
    static String ATTR_KEY_ATTRIBUTE = "key_attribute";
    static String ATTR_KEY_VALUE = "key_value";

    // instruction's tags
    static String ATTR_DELETE_INSTRUCTION = "DeleteInstruction";
    static String ATTR_REPLACE_INSTRUCTION = "ReplaceInstruction";
    static String ATTR_ADD_INSTRUCTION = "AddInstruction";

    ElementChanger()
    {
    }
    /**
     *
     * takes <DeleteInstruction> element
     * @author Alexander Prozor
     */
    static Element proceedDeleteInstruction(Element originalElement, Element changeRequest)
    {
        Element elementPath = getElementPath(changeRequest);
        NodeList stepList = getStepsFromElementPath(elementPath);
        NodeList attributeDescList = getAttributeDescList(changeRequest);

        Element soughtElement = null;
        Node parentNode = null;

        soughtElement = findElement(stepList, originalElement);
        if(soughtElement == null)
        {
            GlobalSystem.getLogFacility().warning().write("Element for changing not found");
            return originalElement;
        }
        // if attributes list don't present
        // delete element
        if(attributeDescList == null)
        {
            parentNode = soughtElement.getParentNode();
            parentNode.removeChild(soughtElement);
        }
        else // else delete attribute(s)
        {
            int attrDescCount = attributeDescList.getLength();
            for(int j=0; j<attrDescCount; j++)
            {
                Element attrDesc = (Element)attributeDescList.item(j);
                soughtElement.removeAttribute(attrDesc.getAttribute(ATTR_ATTRIBUTE_NAME));
            }
        }
        return originalElement;
    }
    /**
     *
     * takes <ChangeInstruction> element
     * @author Alexander Prozor
     */
    static Element proceedReplaceInstruction(Element originalElement, Element changeRequest)
    {
        Element elementPath = getElementPath(changeRequest);
        Element newElement = getNewElement(changeRequest);
        NodeList stepList = getStepsFromElementPath(elementPath);

        Element soughtElement = null;
        Node parentNode = null;
        soughtElement = findElement(stepList, originalElement);
        if(soughtElement == null)
        {
            GlobalSystem.getLogFacility().warning().write("Element for changing not found");
            return originalElement;
        }
        // replace attributes
        for (AttrsIterator it = new AttrsIterator(newElement); it.hasNext(); )
        {
            Attr attr = it.next();
            XMLData.setAttributeValueImpl(soughtElement, attr.getNodeName(), attr.getNodeValue());
        }
        // replace elements
        // if we have to replace element
        if (newElement.hasChildNodes())
        {
            Document doc = originalElement.getOwnerDocument();
            doc.adoptNode(newElement);
            //parentNode.replaceChild(newElement.getFirstChild(), soughtElement);
            parentNode = soughtElement.getParentNode();
            parentNode.replaceChild(newElement.getFirstChild(), soughtElement);
        }
/*        NodeList newChildren = newElement.getChildNodes();
        int childCount = newChildren.getLength();
        parentNode = soughtElement.getParentNode();
        for(int k=0; k<childCount; k++)
        {
            // ap replace only element
            if(newChildren.item(k) instanceof Element)
            {
                Document ownerDoc = soughtElement.getOwnerDocument();
                Element newChild = (Element)newChildren.item(k);
                Element appendChild = ownerDoc.createElement(newChild.getNodeName());
                NamedNodeMap newChildArributes = newChild.getAttributes();
                int newChildArributeCount = newChildArributes.getLength();
                // copy attributes
                for(int l=0; l<newChildArributeCount; l++)
                {
                    Attr appendAttr = (Attr)newChildArributes.item(l);
                    appendChild.setAttribute(appendAttr.getName(), appendAttr.getValue());
                }
                parentNode.replaceChild(appendChild, soughtElement);
            }
        }*/
        return originalElement;
    }
    /**
     *
     * takes <ChangeInstruction> element
     * @author Alexander Prozor
     */
    public static Element proceedChangeInstruction(Element originalElement, Element changeRequest)
    {
        Element changedElement = null;
        for (Iterator<Node> it = new ChildNodesIterator(changeRequest); it.hasNext(); )
        {
            Node currentChangeInstructionNode = it.next();
            if (Node.ELEMENT_NODE == currentChangeInstructionNode.getNodeType())
            {
                Element currentInstruction = (Element)currentChangeInstructionNode;
                String instructionName = currentInstruction.getNodeName();
                if(instructionName.equals(ATTR_ADD_INSTRUCTION))
                {
                    changedElement = proceedAddInstruction(originalElement, currentInstruction);
                }
                else if(instructionName.equals(ATTR_DELETE_INSTRUCTION))
                {
                    changedElement = proceedDeleteInstruction(originalElement, currentInstruction);
                }
                else if(instructionName.equals(ATTR_REPLACE_INSTRUCTION))
                {
                    changedElement = proceedReplaceInstruction(originalElement, currentInstruction);
                }
            }
        }
        return changedElement;
    }
    /**
     *
     * takes <AddInstruction> element
     * @author Alexander Prozor
     */
    static Element proceedAddInstruction(Element originalElement, Element changeRequest)
    {
        Element elementPath = getElementPath(changeRequest);
        Element newElement = getNewElement(changeRequest);
        NodeList stepList = getStepsFromElementPath(elementPath);

        Element soughtElement = null;
        soughtElement = findElement(stepList, originalElement);
        if(soughtElement == null)
        {
            GlobalSystem.getLogFacility().warning().write("Element for changing not found");
            return originalElement;
        }
        // add attributes
        for (AttrsIterator it = new AttrsIterator(newElement); it.hasNext(); )
        {
            Attr attr = it.next();
            XMLData.setAttributeValueImpl(soughtElement, attr.getNodeName(), attr.getNodeValue());
        }
        // add new elements
        for (Iterator<Node> nodesIt = new ChildNodesIterator(soughtElement); nodesIt.hasNext(); )
        {
            Node node = nodesIt.next();
            // ap add only element
            if (Node.ELEMENT_NODE == node.getNodeType())
            {
                Document ownerDoc = soughtElement.getOwnerDocument();
                Element newChild = (Element)node;
                Element appendChild = ownerDoc.createElement(newChild.getNodeName());
                // copy attributes
                for (AttrsIterator it = new AttrsIterator(newChild); it.hasNext(); )
                {
                    Attr attr = it.next();
                    XMLData.setAttributeValueImpl(appendChild, attr.getNodeName(), attr.getNodeValue());
                }
                soughtElement.appendChild(appendChild);
            }
        }        
        return originalElement;
    }
    /**
     * Exctract <ElementPath> element from change instruction
     * @author Alexander Prozor
     */
    static Element getElementPath(Element instruction)
    {
        return XMLDataUtil.findElement(instruction, ATTR_PATH);
    }
    /**
     * Exctract <AttributeList> element from change instruction
     * @author Alexander Prozor
     */
    static NodeList getAttributeDescList(Element instruction)
    {
        Element attributesList = XMLDataUtil.findElement(instruction, ATTR_ATTRIBUTE_LIST);
        if (null == attributesList)
            return null;
        NodeList attributeDescList = attributesList.getElementsByTagName(ATTR_ATTRIBUTE_DESC);
        return attributeDescList;
    }
    /**
     * Exctract <NewElement> element from change instruction
     * @author Alexander Prozor
     */
    static Element getNewElement(Element instruction)
    {
        return XMLDataUtil.findElement(instruction, ATTR_NEW_ELEMENT);
    }
    static NodeList getStepsFromElementPath(Element elementPath)
    {
        NodeList stepList = elementPath.getElementsByTagName(ATTR_PATH_ELEMENT);
        return stepList;
    }
    /**
     * Finds and returns element for elementPath
     * @author Alexander Prozor
     */
    static Element findElement(NodeList stepList, Element originalElement)
    {
        Element currentElementPath = originalElement;
        Element searchingElement = null;
        for(int i=0; i< stepList.getLength(); i++)
        {
            currentElementPath = findElementForStep((Element)stepList.item(i) , currentElementPath);
        }
        searchingElement = currentElementPath;
        return searchingElement;
    }

    /**
     * Finds and returns element for elementPath
     * @author Alexander Prozor
     */
    static Element findElementForStep(Element step, Element originalElement)
    {
        if(step == null || originalElement == null)
            return null;

        // get element's informations
        String stepOrder = step.getAttribute(ATTR_STEP_ORDER);
        String elementName = step.getAttribute(ATTR_TAG_NAME);
        String keyAttribute = step.getAttribute(ATTR_KEY_ATTRIBUTE);
        String keyValue = step.getAttribute(ATTR_KEY_VALUE);

        // EZ080300: We can try to search just originalElement element. For ex.
        //  we received connection properties from connectionPropertyManager
        //  and try to change some attributes using this mechanism.
        //  We need to find and change the top-most element of this XMLData.
        //  And we have nothing except it to pass into XMLDataChangeRequest.
        //  The way it was implemented I could not perform this operation.
        if(originalElement.getTagName().equals(elementName) &&
           originalElement.getAttribute(keyAttribute).equals(keyValue))
        {
            return originalElement;
        }

        // PENDING - must be optimized find process
        StringTokenizer tokinezer = new StringTokenizer(elementName, "::");
        String token = "";
/*        while(tokinezer.hasMoreTokens())
        {
            token = tokinezer.nextToken();
//AP             NodeList items = originalElement.getElementsByTagName(token);
        }
        // prozor pending: by now I check only last element in ElementPath
        NodeList items = originalElement.getElementsByTagName(token);*/
        NodeList items = originalElement.getElementsByTagName(elementName);
        // detect needed element
        Element searchingElement = null;
        Element currentElement = null;
        // if step doesn't contains any key attributes
        if(keyAttribute == null)
        {
            if(items.getLength() != 0)
            {
                   searchingElement = (Element)items.item(0);
            }
        }
        else
        {
            for(int i=0; i< items.getLength(); i++)
            {
                currentElement = (Element)items.item(i);
                if(currentElement.getAttribute(keyAttribute).equals(keyValue))
                {
                    searchingElement = currentElement;
                    break;
                }
            }
        }
        //__
        return searchingElement;
    }
}