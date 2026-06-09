/*
 * Copyright (c) 1997-2019 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.xml;

/**
 * Check if the thread is printed for configured "abc" XML attribute.
 */
public class XMLData_access_attrs_test
{
    public static void main(String[] args)
    {
        System.setProperty("XMLData.dumpStackForAttributes", "abc");
        XMLData xmlData = XMLData.valueOf("<A abc=\"12\" def=\"34\" />");
        check(xmlData);
    }

    private static void check(XMLData xmlData)
    {
        check2(xmlData);
    }

    private static void check2(XMLData xmlData)
    {
        System.out.println(xmlData.getAttributeIntegerValue("abc"));
        System.out.println(xmlData.getAttributeIntegerValue("def"));
        System.out.println(XMLDataUtil.getAttributeIntegerValue(xmlData.getDocumentRoot(), "abc"));
        System.out.println(XMLDataUtil.getAttributeIntegerValue(xmlData.getDocumentRoot(), "def"));
    }
}
