/*
 * Copyright (c) 2015 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 1.0
 *
 */

package com.finsent.util.xml.parser;

import com.finsent.util.xml.XMLData;
import org.junit.Test;

import static org.easymock.EasyMock.*;

/**
 * @author Andrey Aleshnikov
 */
public class XMLParser_utest
{
    @Test
    public void trivial()
    {
        IXMLDataBuilder b = createMock(IXMLDataBuilder.class);
        b.elementStartMet("root", false);
        b.elementEndMet("root");
        replay(b);
        new XMLParser(b).parseData(XMLData.valueOf("<root/>"));
        verify(b);
    }
    
    @Test
    public void attributes()
    {
        IXMLDataBuilder b = createMock(IXMLDataBuilder.class);
        b.elementStartMet("root", false);
        b.attributeMet("root", "x", "y");
        b.attributeMet("root", "a", "b");
        b.attributeMet("root", "c", "d");
        b.elementEndMet("root");
        replay(b);
        new XMLParser(b).parseData(XMLData.valueOf("<root x='y' a='b' c='d'/>"));
        verify(b);
    }
    
    @Test
    public void text()
    {
        IXMLDataBuilder b = createMock(IXMLDataBuilder.class);
        b.elementStartMet("root", false);
        b.subLevel();
        b.textValueMet("text");
        b.superLevel();
        b.elementEndMet("root");
        replay(b);
        new XMLParser(b).parseData(XMLData.valueOf("<root>text</root>"));
        verify(b);
    }
    
    @Test
    public void comment()
    {
        IXMLDataBuilder b = createMock(IXMLDataBuilder.class);
        b.elementStartMet("root", false);
        b.subLevel();
        b.commentMet("comment");
        b.superLevel();
        b.elementEndMet("root");
        replay(b);
        new XMLParser(b).parseData(XMLData.valueOf("<root><!--comment--></root>"));
        verify(b);
    }
    
    @Test
    /**
     * \r\n and \r must are converted to \n by XML parser (actual parser, 
     * not com.alex.util.xml.parser.XMLParser).  
     * @see http://www.w3.org/TR/REC-xml/#sec-line-ends
     */
    public void comment_with_EOLs()
    {
        IXMLDataBuilder b = createMock(IXMLDataBuilder.class);
        b.elementStartMet("root", false);
        b.subLevel();
        b.commentMet("comment\nwith\nEOLs\n");
        b.superLevel();
        b.elementEndMet("root");
        replay(b);
        new XMLParser(b).parseData(XMLData.valueOf("<root><!--comment\nwith\rEOLs\r\n--></root>"));
        verify(b);
    }

    @Test
    public void textInterleavingWithElements()
    {
        IXMLDataBuilder b = createMock(IXMLDataBuilder.class);
        b.elementStartMet("root", false);
        b.subLevel();
        b.textValueMet("a");
        b.elementStartMet("b", false);
        b.elementEndMet("b");
        b.textValueMet(" c ");
        b.elementStartMet("d", false);
        b.elementEndMet("d");
        b.textValueMet("\t");
        b.superLevel();
        b.elementEndMet("root");
        replay(b);
        new XMLParser(b).parseData(XMLData.valueOf("<root>a<b/> c <d/>\t</root>"));
        verify(b);
    }
    
    @Test
    public void cdata()
    {
        IXMLDataBuilder b = createMock(IXMLDataBuilder.class);
        b.elementStartMet("root", false);
        b.subLevel();
        b.otherNodeMet("<![CDATA[<text>&amp;&lt;</text>&<]]>");
        b.superLevel();
        b.elementEndMet("root");
        replay(b);
        new XMLParser(b).parseData(XMLData.valueOf("<root><![CDATA[<text>&amp;&lt;</text>&<]]></root>"));
        verify(b);
    }

    @Test
    public void complex()
    {
        XMLData xml = XMLData.valueOf("<root>"
                + "  text 1"
                + "  <child1>"
                + "    child text 1"
                + "     <grandchild>grandchild text</grandchild>"
                + "   </child1>"
                + "   <!--comment-->"
                + "   text 2"
                + "   <child2>"
                + "     child 2 text"
                + "   </child2>"
                + "</root>");
        IXMLDataBuilder b = createMock(IXMLDataBuilder.class);
        b.elementStartMet("root", false);
        
        b.subLevel();
        b.textValueMet("  text 1  ");
        b.elementStartMet("child1", false);
        
        b.subLevel();
        b.textValueMet("    child text 1     ");
        
        b.elementStartMet("grandchild", false);
        
        b.subLevel();
        b.textValueMet("grandchild text");
        b.superLevel();
        b.elementEndMet("grandchild");        
        b.textValueMet("   ");
        b.superLevel();
        b.elementEndMet("child1");
        b.textValueMet("   ");
        b.commentMet("comment");
        b.textValueMet("   text 2   ");
        b.elementStartMet("child2", false);
        
        b.subLevel();
        b.textValueMet("     child 2 text   ");
        b.superLevel();
        b.elementEndMet("child2");        
        b.superLevel();
        b.elementEndMet("root");
        replay(b);
        new XMLParser(b).parseData(xml);
        verify(b);
    }
}
