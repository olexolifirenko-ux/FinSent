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


package com.finsent.util.xml.dtd;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class XMLDtdElementContentIterator
{
    /**
     * Takes the list of adding element from element's content
     * and returns the list of element that can be added
     * @author Alexander Prozor
     */
    public XMLDtdElementContentIterator(XMLDtdElementDecl elementDecl)
    {
        content_ = new XMLDtdElementContent(elementDecl.getContentModel());
    }
    /**
     * Takes the list of adding element from element's content
     * and returns the list of element that can be added
     * @author Alexander Prozor
     */
    public XMLDtdElementContentIterator(XMLDtdElementContent elementContent)
    {
        content_ = elementContent;
    }

    /**
     * Indicates - if there are elements to be addiding
     * @param addedElements already added elements name
     * (and/or PCDADA)
     * @athor Alexander Prozor
     */
    public boolean hasNext(List addedElements)
    {
        return true;
    }
    public List next(List addedElements)
    {
        List list = null;//new ArrayList();
        switch(content_.getContentType())
        {
            case XMLDtdElementContent.CONTENT_TYPE_ANY:
            {
                // return all element list from this document
                // ...
                list = new ArrayList();
                // just for test
                list.add("any");
                break;
            }
            case XMLDtdElementContent.CONTENT_TYPE_EMPTY:
            {
                // there is nothing to add
                list = new ArrayList(1);  // for returning of empty list
                // just for test
                //list.add("empty");
                break;
            }
            case XMLDtdElementContent.CONTENT_TYPE_MIXED:
            {
                // this list for mixed content independing from
                // previously added elements
                list = getNameListForMixedModel();
                break;
            }
            case XMLDtdElementContent.CONTENT_TYPE_CHILDREN:
            {
                list = getNameListForChildrenModel(addedElements);
                break;
            }
        }
        return list;
    }
    /**
     * Returns list of elements , that can be inserted
     * in to the children content
     * @author Alexander Prozor
     */
    protected List getNameListForChildrenModel(List addedElements)
    {
        XMLDtdElementContent.ChildrenModel content = (XMLDtdElementContent.ChildrenModel)content_.getContent();
        XMLDtdElementContent.Child child = content.getChildContent();
        List names = getNameListFromChild(addedElements, child);
        return names;
    }
    protected List getNameListFromChild(
        List addedElements,
        XMLDtdElementContent.Child child
                                       )
    {
        XMLDtdElementContent.CpContent cpElement = null;
        Iterator cpElementList = null;

        List elementForAdding = new ArrayList(1);
        List names = null;

        if(child instanceof XMLDtdElementContent.ChoiseContent )
        {
            names = getNameListFromChoiseElement((XMLDtdElementContent.Child)child, addedElements);
        }
        else // if child instanceof SeqContent
        {
            names = getNameListFromSeqElement((XMLDtdElementContent.Child)child, addedElements);
        }
        elementForAdding.addAll(names);
        // revisit have to take repeat modify
        return elementForAdding;
    }

    protected List getNameListFromCpElement(XMLDtdElementContent.CpContent cpElement, List addedElements)
    {
        // return empty list, if no elements was found
        List nameList = new ArrayList(1);
        // cp may contains choise, seq and name
        Object elementContent = cpElement.getContent();
        if(elementContent instanceof String) // if it contains name
        {
            nameList = new ArrayList(1);
            nameList.add(elementContent);
        }
        else if(elementContent instanceof XMLDtdElementContent.ChoiseContent)
        {
            List namesFromChild = getNameListFromChoiseElement((XMLDtdElementContent.ChoiseContent)elementContent, addedElements);
            //...
            nameList = namesFromChild;
        }
        else if(elementContent instanceof XMLDtdElementContent.SeqContent)
        {
            List namesFromChild = getNameListFromSeqElement((XMLDtdElementContent.SeqContent)elementContent, addedElements);
            //...
            nameList = namesFromChild;
        }
        return nameList;
    }
    protected List getNameListFromSeqElement(XMLDtdElementContent.Child seq, List addedElements)
    {
        XMLDtdElementContent.CpContent cpElement = null;
        List elementForAdding = new ArrayList(1);
        Iterator cpElements = ((XMLDtdElementContent.Child)seq).getCPElementList().iterator();
        while(cpElements.hasNext())
        {
            cpElement = (XMLDtdElementContent.CpContent)cpElements.next();
            List names = getNameListFromCpElement(cpElement, addedElements);
            if(isNamePresentsInList(addedElements, names))
            {
                // to do - check if item can to be adding more than once
                    // PENDING return all elements' name from seq
                    elementForAdding.addAll(names);
                    //
            }
            else
            {
                elementForAdding.addAll(names);
                    // PENDING return all elements' name from seq
                    //break;
            }
        }
        return elementForAdding;
    }
    protected List getNameListFromChoiseElement(XMLDtdElementContent.Child choise, List addedElements)
    {
        XMLDtdElementContent.CpContent cpElement = null;
        List elementForAdding = new ArrayList(1);

        Iterator cpElements = ((XMLDtdElementContent.Child)choise).getCPElementList().iterator();
        while(cpElements.hasNext())
        {
            cpElement = (XMLDtdElementContent.CpContent)cpElements.next();
            List names = getNameListFromCpElement(cpElement, addedElements);
            if(isNamePresentsInList(addedElements, names))
            {
                // to do - check if item can to be adding more than once
                elementForAdding.addAll(names);
            }
            else
            {
                elementForAdding.addAll(names);
            }
        }
        return elementForAdding;
    }
    /**
     * Checks - if some name from currentNames list
     * presents in the addedNames list
     * @author Alexander Prozor
     */
    protected boolean isNamePresentsInList(List addedNames, List currentNames)
    {
        boolean presents = false;
        Iterator currentNameIterator = currentNames.iterator();
        while(currentNameIterator.hasNext())
        {
            if(addedNames.contains(currentNameIterator.next()))
            {
                presents = true;
                break;
            }   
        }
        return presents;
    }
    /**
     * Returns list of elements , that can be inserted
     * in to the mixed content
     * @author Alexander Prozor
     */
    protected List getNameListForMixedModel()
    {
        List allNames = new ArrayList(1);
        XMLDtdElementContent.MixedModel model = (XMLDtdElementContent.MixedModel)content_.getContent();
        allNames.add("#PCDATA");
        List nameFromContent = model.getNameList();
        allNames.addAll(nameFromContent);
        return allNames;
    }

    protected XMLDtdElementContent content_ = null;
}
