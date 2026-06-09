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

public class XMLDtdAttributeDecl
{
    // constructors
    public XMLDtdAttributeDecl(
                String		elementName,
                String		attributeName,
                String		attributeType,
                String		options [],
                String		defaultValue,
                boolean		isFixed,
                boolean		isRequired
                               )
    {
        this.elementName = elementName;
        this.attributeName = attributeName;
        this.attributeType = attributeType;
        this.options = options;
        this.defaultValue = defaultValue;
        this.isFixed = isFixed;
        this.isRequired = isRequired;
    }

    // instance accessories methods
    public String getOwnerElementName()
    {
        return elementName;
    }
    public String getAttributeName()
    {
        return attributeName;
    }
    public String getAttributeType()
    {
        return attributeType;
    }
    /**
     * Returns enumeration of attribute's values
     *
     * @author Alexander Prozor
     */
    public String[] getOptions()
    {
        return options;
    }
    public String getDefaultValue()
    {
        return defaultValue;
    }
    public boolean isRequired()
    {
        return isRequired;
    }
    public boolean isFixed()
    {
        return isFixed;
    }

    // instance variables
    String		elementName;
    String		attributeName;
    String		attributeType;
    String[]	options;
    String		defaultValue;
    boolean		isFixed;
    boolean		isRequired;
}
