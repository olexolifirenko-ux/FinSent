/*
 * Copyright (c) 1997-2014 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.xml.parser;

import com.finsent.util.xml.XMLData;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * U-test for {@link XMLDataCopingBuilder}
 *
 * @author Eugeny.Schava
 */
public class XMLDataCopingBuilder_utest
{
    @Test
    public void basic() throws Exception
    {
        XMLData data = XMLData.valueOf("<?xml version=\"1.0\"?>"
                + "<Root a=\"1\">"
                + "<Child1>Text with special chars: &amp; &lt;</Child1>"
                + "<Child2 b=\"44\"/>"
                + "</Root>");
        XMLDataCopingBuilder builder = new XMLDataCopingBuilder();
        new XMLParser(builder).parseData(data);
        XMLData data2 = builder.getCopy();
        assertEquals(data.toString(), data2.toString());
    }
    
    @Test
    @Ignore("AA: it looks like it was broken from the very beginning")
    public void testCDATACopying() throws Exception
    {
        XMLData data = XMLData.valueOf("<?xml version=\"1.0\"?>"
                + "<Root>"
                + "<![CDATA[test]]>"
                + "</Root>");
        XMLDataCopingBuilder builder = new XMLDataCopingBuilder();
        new XMLParser(builder).parseData(data);
        XMLData data2 = builder.getCopy();
        assertEquals(data.toString(), data2.toString());
    }
}
