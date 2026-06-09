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

import java.util.List;
import java.util.Objects;

public class XMLDtdElementDecl
{
    // instance variables
    private String elementName_;
    private String contentModel_;
    /**
     * Full presentation of content model
     * ()
     * @author Alexander Prozor
     */
    private XMLDtdElementContent elementContent_;

    public XMLDtdElementDecl(String elementName, String contentModel)
    {
        elementName_ = elementName;
        contentModel_ = contentModel;
    }

    public String getElementName()
    {
        return elementName_;
    }

    /**
     * Return the compact content model (from DTD )
     * @author Alexander Prozor
     */
    public String getContentModel()
    {
        return contentModel_;
    }

    public List getTreeChildElementList(List isRequiredAttrList, List isRepeatingAttrList)
    {
        return getElementContent().getChildElements(isRequiredAttrList, isRepeatingAttrList);
    }

    /**
     * Return the elements name list, that could be add
     * to this element with such already added.
     * @author Alexander Prozor
     */
    public List getElementsThatCouldBeAdd(List alreadyAddedElements)
    {
        XMLDtdElementContentIterator content = new XMLDtdElementContentIterator(getElementContent());
        return content.next(alreadyAddedElements);
    }

    /**
     * Return the full structure of posible content of element
     * @author Alexander Prozor
     */
    public XMLDtdElementContent getElementContent()
    {
        if(elementContent_ == null)
        {
            elementContent_ = new XMLDtdElementContent(contentModel_);
        }
        return elementContent_;
    }
    // parse of content of element
    
    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        XMLDtdElementDecl that = (XMLDtdElementDecl) o;
        return Objects.equals(elementName_, that.elementName_) &&
            Objects.equals(contentModel_, that.contentModel_);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(elementName_, contentModel_);
    }
}