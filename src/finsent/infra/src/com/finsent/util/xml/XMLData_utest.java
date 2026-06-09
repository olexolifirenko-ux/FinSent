/*
 * Copyright (c) 2014 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 *
 */

package com.finsent.util.xml;


import com.finsent.directory.DirectorySystem;
import com.finsent.util.ISizedIterator;
import com.finsent.util.ReflectionUtils;
import com.finsent.util.UtilityFunctions;
import com.finsent.util.map.cache.Cache;
import com.finsent.util.test.TestUtils;
import org.junit.*;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

/**
 * @author Andrey Aleshnikov
 */
public class XMLData_utest extends Assert // TODO cleanup redundant tests
{
    protected static File dirRoot_;
    
    @BeforeClass
    public static void setUp() throws Exception
    {
        dirRoot_ = new File (TestUtils.findTMSRoot(), "release/backend/Directory");
        DirectorySystem.initializeDefault(dirRoot_.getCanonicalPath());
    }
    
    @After
    public void tearDown() 
    {
    }
                   
    @Test
    public void testDOMInvariants()
    {
        XMLData xml = ctor(new File(dirRoot_, "ElTrader/FIXTMSMetaData/FIX.4.2-Fields.xml").getPath());
        assertDOMInvariantsAreMet(xml);
    }
    
    @Test
    public void doctypeShouldBeParsedProperly()
    {
        XMLData xml = ctor(new File(dirRoot_, "ElTrader/FIXTMSMetaData/FIX.4.2-Fields.xml").getPath());
        assertEquals("directory://ElTrader/FIXTMSMetaData/FIX.4.2-Fields.dtd", xml.getSystemDtdID());
        DocumentType doctype = xml.getDocumentRoot().getOwnerDocument().getDoctype();
        assertNotNull("doctype should be present", doctype);
        assertEquals("directory://ElTrader/FIXTMSMetaData/FIX.4.2-Fields.dtd", doctype.getSystemId());
        assertNull("this doctype has no publicID", doctype.getPublicId());
        assertEquals("FixFieldsMetaData", doctype.getName());
        assertNull("this doctype has no internalSubset", doctype.getInternalSubset());

        xml = valueOf("<?xml version='1.0'?>"
                + "<!DOCTYPE Bootstrap [ <!ELEMENT Bootstrap (Directory)>"
                + "  <!ATTLIST Bootstrap bootstrapResource CDATA #REQUIRED>"
                + "  <!ELEMENT Directory (OutwardAdapter)?>"
                + "  <!ATTLIST Directory kind (JNDI|FILE|FILE_REMOTE) #REQUIRED>"
                + "]>"
                + "<Bootstrap bootstrapResource='/ElTrader/TMS/Config/processes.xml'>"
                + "  <Directory kind='FILE'/>"
                + "</Bootstrap>");
        doctype = xml.getDocumentRoot().getOwnerDocument().getDoctype();
        assertNotNull("doctype should be present", doctype);
        assertNull("this doctype has no systemId", doctype.getSystemId());
        assertNull("this doctype has no publicID", doctype.getPublicId());
        assertEquals("Bootstrap", doctype.getName());
        assertNotNull("internalSubset should be present", doctype.getInternalSubset());
    }
    
    @Test
    public void stringValueForDoctypeHavingBothPublicAndSystedId()
    {
        String expected = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE FixFieldsMetaData PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\"" // crap, but OK for test
                + " \"directory://ElTrader/FIXTMSMetaData/FIX.4.2-Fields.dtd\">"
                + "<FixFieldsMetaData/>";
        assertEquals(expected, valueOf(expected).stringValue(false).trim());
    }
    
    @Test
    public void prettyStringValueForDTDWithInternalSubset()
    {
        String EOL = XMLPrettyOutputBuilder.EOL;
        String expected = 
                "<?xml version=\"1.0\"?>" + EOL
                + "<!DOCTYPE Bootstrap [ <!ELEMENT Bootstrap (Directory)>" + EOL
                + "<!ATTLIST Bootstrap bootstrapResource CDATA #REQUIRED>" + EOL
                + "<!ELEMENT Directory (OutwardAdapter)?>" + EOL
                + "<!ATTLIST Directory kind (JNDI|FILE|FILE_REMOTE) #REQUIRED>"+ EOL
                + "]>" + EOL
                + "<Bootstrap bootstrapResource=\"/ElTrader/TMS/Config/processes.xml\">" + EOL
                + "\t<Directory kind=\"FILE\"/>" + EOL
                + "</Bootstrap>";
        XMLData xml = valueOf(expected);
        assertEquals(expected, xml.stringValue(true).trim());
        // comment prev line and leave this one to troubleshoot diff in invisible chars (spaces, newlines)
        assertEquals(toHexCodes(expected), toHexCodes(xml.stringValue(true).trim()));
    }

    @Test
    public void testCDATASections()
    {
        String EOL = XMLPrettyOutputBuilder.EOL;
        char TAB = XMLPrettyOutputBuilder.SYMBOL_TAB;
        // Basic
        String cdata0 = "test";
        XMLData xml = valueOf("<?xml version=\"1.0\"?>"
                + "<root><![CDATA[" + cdata0 + "]]></root>");
        assertEquals(1, xml.getCDATAsCount());
        assertEquals(cdata0, xml.getCDATAElement(0));
        assertEquals("<?xml version=\"1.0\"?>" +  EOL
                + "<root>" +  EOL
                + TAB + "<![CDATA[" + cdata0 + "]]>" +  EOL
                + "</root>", xml.stringValue(true).trim());
        // With spaces        
        cdata0 = " \ttest ";
        xml = valueOf("<?xml version=\"1.0\"?>"
                + "<root><![CDATA[" + cdata0 + "]]></root>");
        assertEquals(cdata0, xml.getCDATAElement(0));
        assertEquals("<?xml version=\"1.0\"?>" +  EOL
                + "<root>" +  EOL
                + TAB + "<![CDATA[" + cdata0 + "]]>" +  EOL
                + "</root>", xml.stringValue(true).trim());
        
        // With newlines  
        cdata0 = "t\re\ns\r\nt";
        String cdata0Normalized = "t\ne\ns\nt"; // http://www.w3.org/TR/REC-xml/#sec-line-ends
        StringBuilder sb = new StringBuilder();
        XMLDataUtil.appendStringWithFixedEOLs(cdata0Normalized, sb);        
        String cdata0IRNormalized = sb.toString();
        
        xml = valueOf("<?xml version=\"1.0\"?>"
                + "<root><![CDATA[" + cdata0 + "]]></root>");
        assertEquals(cdata0Normalized, xml.getCDATAElement(0));
        String expectedStr = "<?xml version=\"1.0\"?>"
                + "<root>"
                + "<![CDATA[" + cdata0IRNormalized + "]]>"
                + "</root>";
        String actualStr = xml.stringValue(false).trim();
        assertEquals(toHexCodes(expectedStr), toHexCodes(actualStr));        
        assertEquals("<?xml version=\"1.0\"?>" +  EOL
                + "<root>" +  EOL
                + TAB + "<![CDATA[" + cdata0IRNormalized + "]]>" +  EOL
                + "</root>", xml.stringValue(true).trim());
    }
    @Test
    public void testCCDATASections_Complex()
    {
        String cdata0 = " \t\r\n ";
        String cdata0Normalized = " \t\n ";
        StringBuilder sb = new StringBuilder();
        XMLDataUtil.appendStringWithFixedEOLs(cdata0Normalized, sb);        
        String cdata0IRNormalized = sb.toString();
        String cdata1 = "<greeting>Hello, world!</greeting>";
        String cdata3 = " \t&0xD;&0xA; ";
        XMLData xml = valueOf("<?xml version=\"1.0\"?>"
                + "<root>"
                + "<![CDATA[" + cdata0 + "]]>"
                + "<![CDATA[" + cdata1 + "]]>"
                + "<![CDATA[" + cdata3 + "]]>"
                + "</root>");
        assertEquals(3, xml.getCDATAsCount());
        assertEquals(UtilityFunctions.bytesToHexString(cdata0Normalized.getBytes()),
            UtilityFunctions.bytesToHexString(xml.getCDATAElement(0).getBytes()));
        assertEquals(cdata1, xml.getCDATAElement(1));
        assertEquals(UtilityFunctions.bytesToHexString(cdata3.getBytes()),
            UtilityFunctions.bytesToHexString(xml.getCDATAElement(2).getBytes()));
        
        String EOL = XMLPrettyOutputBuilder.EOL;
        char TAB = XMLPrettyOutputBuilder.SYMBOL_TAB;
        // Basic
        assertEquals("<?xml version=\"1.0\"?>"
                + "<root>"
                + "<![CDATA[" + cdata0IRNormalized + "]]>"
                + "<![CDATA[" + cdata1 + "]]>"
                + "<![CDATA[" + cdata3 + "]]>"
                + "</root>", xml.stringValue(false).trim());
        assertEquals("<?xml version=\"1.0\"?>" +  EOL
                + "<root>" +  EOL
                + TAB + "<![CDATA[" + cdata0IRNormalized + "]]>" +  EOL
                + TAB + "<![CDATA[" + cdata1 + "]]>" +  EOL
                + TAB + "<![CDATA[" + cdata3 + "]]>" +  EOL
                + "</root>", xml.stringValue(true).trim());
    }
    
    @Test
    public void prettyOutputRespectsTextNodes()
    {
        XMLData xml = newInstance("root");
        String expectedTextNodeValue = "test";
        xml.setNodeValue(expectedTextNodeValue);
        XMLData xml2 = valueOf(xml.stringValue(true));
        assertEquals(expectedTextNodeValue, xml2.getTextValueOfNode());
    }
    
    @Test
    public void testFileLoading() throws Exception
    {
        Charset[] charsets = new Charset[] { StandardCharsets.UTF_8,
                StandardCharsets.UTF_16, // optional byte-order mark
                StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE,
                Charset.forName("cp1251") };
        for (Charset cs : charsets)
        {
            XMLDataTestHelper t = new XMLDataTestHelper(cs);
            Path p = Files.createTempFile(getClass().getSimpleName() + '_' + t.cs_ + '_', ".xml");
            try
            {
                Files.write(p, Collections.singletonList(t.getXMLStr()), t.cs_);
                parseUsingJREJAXPImpl(p); // to ensure what we want from XMLData is supported by JRE's JAXP implementation
                XMLData xmlData = ctor(p.toFile().getCanonicalPath());
                t.checkAssertions(xmlData);
            }
            finally
            {
                Files.delete(p);
            }
        }
    }
       
    @Test
    public void testFileSaving() throws Exception
    {
        // XMLData -> File -> XMLData
        Charset[] charsets = new Charset[] { StandardCharsets.UTF_8,
                StandardCharsets.UTF_16, // optional byte-order mark
                StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE,
                Charset.forName("cp1251") };
        for (Charset cs : charsets)
        {
            XMLDataTestHelper t = new XMLDataTestHelper(cs);
            XMLData xmlData = t.buildInMemory();
            Path p = Files.createTempFile(getClass().getSimpleName() + '_' + t.cs_ + '_', ".xml");
            Files.delete(p);
            try
            {
                xmlData.saveToFile(p.toFile().getCanonicalPath());
                parseUsingJREJAXPImpl(p); // to ensure what we want from XMLData is supported by JRE's JAXP implementation
                XMLData xmlData2 = ctor(p.toFile().getCanonicalPath());
                t.checkAssertions(xmlData2);
            }
            finally
            {
                Files.delete(p);
            }
        }
    }
    
    @Test
    public void testStringRoundtrip() throws Exception {
        // XMLData -> String -> XMLData
        Charset[] charsets = new Charset[] { StandardCharsets.UTF_8,
                StandardCharsets.UTF_16, // optional byte-order mark
                StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE,
                Charset.forName("cp1251") };
        for (Charset cs : charsets)
        {
            XMLDataTestHelper h = new XMLDataTestHelper(cs);
            XMLData orig = h.buildInMemory();
            String stringValue = orig.stringValue(true);
            XMLData parsed = valueOfWithException(stringValue);
            h.checkAssertions(parsed);
        }
    }
        
    @Test
    public void testClone()
    {
        XMLData xml1 = ctor(new File(dirRoot_, "ElTrader/FIXTMSMetaData/FIX.4.2-Fields.xml").getPath());
        XMLData xml2 = valueOf("<root><child><grandchild/></child></root>");
        XMLData xml3 = xml2.getDocumentPart("child", false); 
        XMLData xml4 = new XMLData(xml2.getElement(new XMLDataPathElement("child")));
        XMLData xml5 = XMLData.newInstance("root");
        XMLData xml6 = valueOf("<?xml version='1.1'?><root/>");
        XMLData xml7 = ctor("directory://ElTrader/TMS/Config/processes.xml");
        for (XMLData orig : new XMLData[]{xml1, xml2, xml3, xml4, xml5, xml6, xml7})
        {
            XMLData clone = orig.clone();
            assertNotSame(orig, clone);
            assertEquals(orig.getSystemDtdID(), clone.getSystemDtdID());
            assertTrue(orig.stringValue(true) + " vs. " + clone.stringValue(true), orig.equalsDocument(clone));
            Element origDocRoot = orig.getDocumentRoot();
            Element cloneDocRoot = clone.getDocumentRoot();
            // note we compare root elements, not whole documents: this is because for "derived"
            // XMLDatas the clone() will produce a document having only relevant sub-tree of the orig. document
            assertTrue(orig.stringValue(true) + " vs. " + clone.stringValue(true), origDocRoot.isEqualNode(cloneDocRoot));
            assertNotSame(origDocRoot, cloneDocRoot);
            Document origOwnerDoc = origDocRoot.getOwnerDocument();
            Document cloneOwnerDoc = cloneDocRoot.getOwnerDocument();
            assertNotSame(origOwnerDoc, cloneOwnerDoc);
            DocumentType origDoctype = origOwnerDoc.getDoctype();
            if (null != origDoctype)
            {
                DocumentType cloneDoctype = cloneOwnerDoc.getDoctype();
                assertEquals(origDoctype.getName(), cloneDoctype.getName());
                assertEquals(origDoctype.getPublicId(), cloneDoctype.getPublicId());
                assertEquals(origDoctype.getSystemId(), cloneDoctype.getSystemId());
                assertEquals(origDoctype.getInternalSubset(), cloneDoctype.getInternalSubset());
            }
            assertDOMInvariantsAreMet(clone);
            assertEquals(orig.stringValue(true), clone.stringValue(true));
        }
    }

    @Test
    public void testUserDataAfterClone() throws Exception
    {
        XMLData original = XMLData.valueOf("<Root attr0=\"0\"><Inner1 attr1=\"1\"><Inner2 attr2=\"2\"><Inner3 attr3=\"3\"><Inner4 attr4=\"4\"/></Inner3></Inner2></Inner1></Root>");
        Document originalOwnerDocument = original.getDocumentRoot().getOwnerDocument();
        int originalDocUserDataSize = getUserData(originalOwnerDocument).size();

        XMLData toClone = original.getDocumentPart("Inner2", true);
        XMLData clone = toClone.clone();

        assertEquals(originalDocUserDataSize, getUserData(originalOwnerDocument).size());
        assertEquals(7, getUserData(clone.getDocumentRoot().getOwnerDocument()).size());
    }

    @Test
    public void testUserDataAfterRemoveAttribute() throws NoSuchFieldException, IllegalAccessException
    {
        final XMLData xml = valueOf("<?xml version='1.1'?><root attr1=\"1\"/>");
        final String attrName1 = "attr1";
        final String attrName2 = "attr2";

        final Document originalOwnerDocument = xml.getDocumentRoot().getOwnerDocument();
        final int originalDocUserDataSize = getUserData(originalOwnerDocument).size();

        xml.setAttributeValue(attrName2, "2");
        xml.removeAttribute(attrName2);
        assertEquals(originalDocUserDataSize, getUserData(originalOwnerDocument).size());

        xml.removeAttribute(attrName1);
        assertEquals(originalDocUserDataSize - 1, getUserData(originalOwnerDocument).size());
    }
    
    @Test
    @Ignore // current implementation of XML parser doesn't remove user data when node is removed, see https://bugzilla.mozilla.org/show_bug.cgi?id=550400
    public void testUserDataAfterRemove() throws Exception
    {
        XMLData original = XMLData.valueOf("<Root attr0=\"0\"><Inner1 attr1=\"1\"><Inner2 attr2=\"2\"><Inner3 attr3=\"3\"><Inner4 attr4=\"4\"/></Inner3></Inner2></Inner1></Root>");
        Document originalOwnerDocument = original.getDocumentRoot().getOwnerDocument();
        int originalDocUserDataSize = getUserData(originalOwnerDocument).size();

        XMLData toRemove = original.getDocumentPart("Inner2", true);

        original.removeChildNode(toRemove.getName());

        assertEquals(originalDocUserDataSize - 6, getUserData(originalOwnerDocument).size());
    }

    @Test
    public void testUserDataAfterAdd1() throws Exception
    {
        XMLData original = XMLData.valueOf("<Root attr0=\"0\"><Inner1 attr1=\"1\"/></Root>");
        Document originalOwnerDocument = original.getDocumentRoot().getOwnerDocument();
        int originalDocUserDataSize = getUserData(originalOwnerDocument).size();

        XMLData toAdd = XMLData.valueOf("<Inner2 attr2=\"2\"/>");
        Document toAddOwnerDocument = toAdd.getDocumentRoot().getOwnerDocument();
        int toAddDocUserDataSize = getUserData(toAddOwnerDocument).size();

        original.getDocumentPart("Inner1", true)
            .addData(toAdd);

        assertEquals(originalDocUserDataSize + toAddDocUserDataSize - 1, getUserData(originalOwnerDocument).size());
    }
    
    @Test
    public void testUserDataAfterAdd0() throws Exception
    {
        XMLData original = XMLData.valueOf("<Root attr0=\"0\"><Inner1 attr1=\"1\"/></Root>");
        Document originalOwnerDocument = original.getDocumentRoot().getOwnerDocument();
        int originalDocUserDataSize = getUserData(originalOwnerDocument).size();

        XMLData toAdd = XMLData.valueOf("<Inner2/>");
        Document toAddOwnerDocument = toAdd.getDocumentRoot().getOwnerDocument();
        int toAddDocUserDataSize = getUserData(toAddOwnerDocument).size();

        original.getDocumentPart("Inner1", true)
            .addData(toAdd);

        assertEquals(originalDocUserDataSize + toAddDocUserDataSize - 1, getUserData(originalOwnerDocument).size());
    }

    @Test
    public void attrsOrderShouldBePreservedOnStringRoundtrip()
    {
        // basic
        {
            String expected = "<?xml version=\"1.0\"?><root b=\"3\" c=\"2\" a=\"1\"/>";
            XMLData xmlData = valueOf(expected);
            assertEquals(expected, xmlData.stringValue(false).trim());
            // the way we preserve attributes order is sensitive to cloning
            assertEquals(expected, xmlData.clone().stringValue(false).trim());
        }
        
        // generate XML string with random order of attributes and their values
        List<String> allAttrNames = new ArrayList<>();
        List<String> allAttrValues = new ArrayList<>();
        for (char c = 'a'; c < 'z'; c++)
        {
            allAttrNames.add(String.valueOf(c));
            allAttrValues.add(String.valueOf(c));
        }        
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> rootAttrNames = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> rootAttrValues = new ArrayList<>(allAttrValues.subList(0, 10));
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> child1AttrNames = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> child1AttrValues = new ArrayList<>(allAttrValues.subList(0, 10));
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> child2AttrNames = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> child2AttrValues = new ArrayList<>(allAttrValues.subList(0, 10));
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> grandChild1AttrNames = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> grandChild1AttrValues = new ArrayList<>(allAttrValues.subList(0, 10));
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> grandChild2AttrNames = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> grandChild2AttrValues = new ArrayList<>(allAttrValues.subList(0, 10));
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> grandChild1bAttrNames = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> grandChild1bAttrValues = new ArrayList<>(allAttrValues.subList(0, 10));
        
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>");
        appendElementStart(sb, "root", rootAttrNames, rootAttrValues);
        sb.append(">");
        appendElementStart(sb, "child1", child1AttrNames, child1AttrValues);
        sb.append(">");
        appendElementStart(sb, "grandChild1", grandChild1AttrNames, grandChild1AttrValues);
        sb.append("/>");
        appendElementStart(sb, "grandChild2", grandChild2AttrNames, grandChild2AttrValues);
        sb.append("/></child1>");
        appendElementStart(sb, "child2", child2AttrNames, child2AttrValues);
        sb.append(">");
        appendElementStart(sb, "grandChild1", grandChild1bAttrNames, grandChild1bAttrValues);
        sb.append("/></child2>");
        sb.append("</root>");
        String expected = sb.toString();
        XMLData xmlData = valueOf(expected);
        assertEquals(expected, xmlData.stringValue(false).trim());
        // the way we preserve attributes order is sensitive to cloning
        assertEquals(expected, xmlData.clone().stringValue(false).trim());
    }
    
    @Test
    public void attrsOrderShouldBePreservedWhenCreatingProgrammatically() throws Exception
    {
        List<String> allAttrNames = new ArrayList<>();
        List<String> allAttrValues = new ArrayList<>();
        for (char c = 'a'; c < 'z'; c++)
        {
            allAttrNames.add(String.valueOf(c));
            allAttrValues.add(String.valueOf(c));
        }
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> rootAttrNames = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> rootAttrValues = new ArrayList<>(allAttrValues.subList(0, 10));
        
        XMLData xml = valueOf("<?xml version=\"1.0\"?><root/>");
        for (int i = 0; i < rootAttrNames.size(); i++)
        {
            xml.setAttributeValue(
                    String.valueOf(rootAttrNames.get(i)), 
                    String.valueOf(rootAttrValues.get(i)));
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>");
        appendElementStart(sb, "root", rootAttrNames, rootAttrValues);
        sb.append("/>");
        assertEquals(sb.toString(), xml.stringValue(false).trim());
        
//        // delete random attrs
//        Collections.shuffle(allAttrNames);
//        List<String> attrNamesToDelete = new ArrayList<>(allAttrNames.subList(0, 10));
//        for (String attrName1 : attrNamesToDelete)
//        {
//            int attrIndex = rootAttrNames.indexOf(attrName1);
//            if (-1 != attrIndex)
//            {
//                rootAttrNames.remove(attrIndex);
//                rootAttrValues.remove(attrIndex);
//            }
//            xml.removeAttribute(String.valueOf(attrName1));
//        }
//        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>");
//        appendElementStart(sb, "root", rootAttrNames, rootAttrValues);
//        sb.append("/>");
//        assertEquals(sb.toString(), xml.stringValue(false).trim());
//        
//        // add/set random attrs
//        Collections.shuffle(allAttrNames);
//        Collections.shuffle(allAttrValues);
//        List<String> attrNamesToSet = new ArrayList<>(allAttrNames.subList(0, 10));
//        List<String> attrValuesToSet = new ArrayList<>(allAttrValues.subList(0, 10));
//        for (int toSetIndex = 0; toSetIndex < attrNamesToSet.size(); toSetIndex++)
//        {
//            String attrName = attrNamesToSet.get(toSetIndex);
//            String newValue = attrValuesToSet.get(toSetIndex);
//            int destIndex = rootAttrNames.indexOf(attrName);
//            if (-1 != destIndex)
//            {
//                rootAttrValues.set(destIndex, newValue);
//            }
//            else
//            {
//                rootAttrNames.add(attrName);
//                rootAttrValues.add(newValue);
//            }
//            xml.setAttributeValue(attrName, newValue);            
//        }
//        sb = new StringBuilder("<?xml version=\"1.0\"?>");
//        appendElementStart(sb, "root", rootAttrNames, rootAttrValues);
//        sb.append("/>");
//        assertEquals(sb.toString(), xml.stringValue(false).trim());
    }
    
    @Test
    public void attrsOrderShouldBePreservedOnStringRoundtrip_AfterProgrammaticModifications() throws Exception
    {
        // basic
        {
            XMLData xml = valueOf("<?xml version=\"1.0\"?><root c=\"3\" d=\"2\" b=\"1\"/>");
            xml.setAttributeValue("a", 33);
            xml.setAttributeValue("b", 22);
            assertEquals("<?xml version=\"1.0\"?><root c=\"3\" d=\"2\" b=\"22\" a=\"33\"/>",
                    xml.stringValue(false).trim());
            xml.removeAttribute("c");
            xml.removeAttribute("d");
            xml.removeAttribute("a");
            assertEquals("<?xml version=\"1.0\"?><root b=\"22\"/>",
                    xml.stringValue(false).trim());
            xml.setAttributeValue("c", 55);
            xml.setAttributeValue("a", 44);
            assertEquals("<?xml version=\"1.0\"?><root b=\"22\" c=\"55\" a=\"44\"/>",
                    xml.stringValue(false).trim());
        }
        
        List<String> allAttrNames = new ArrayList<>();
        List<String> allAttrValues = new ArrayList<>();
        for (char c = 'a'; c < 'z'; c++)
        {
            allAttrNames.add(String.valueOf(c));
            allAttrValues.add(String.valueOf(c));
        }
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> rootAttrNames = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> initialRootAttrNames = new ArrayList<>(rootAttrNames);
        List<String> rootAttrValues = new ArrayList<>(allAttrValues.subList(0, 10));
        
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>");
        appendElementStart(sb, "root", rootAttrNames, rootAttrValues);
        sb.append("/>");
        XMLData xml = valueOf(sb.toString());
        
        // delete random attrs
        Collections.shuffle(allAttrNames);
        List<String> attrNamesToDelete = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> deletedAttrs = new ArrayList<>();
        for (String attrName : attrNamesToDelete)
        {
            int attrIndex = rootAttrNames.indexOf(attrName);
            if (-1 != attrIndex)
            {
                rootAttrNames.remove(attrIndex);
                rootAttrValues.remove(attrIndex);
                deletedAttrs.add(attrName);
            }
            xml.removeAttribute(attrName);
        }
        sb = new StringBuilder("<?xml version=\"1.0\"?>");
        appendElementStart(sb, "root", rootAttrNames, rootAttrValues);
        sb.append("/>");
        assertEquals("deleted attrs: " + deletedAttrs, sb.toString(), xml.stringValue(false).trim());
        
        // add/set random attrs
        Collections.shuffle(allAttrNames);
        Collections.shuffle(allAttrValues);
        List<String> attrNamesToSet = new ArrayList<>(allAttrNames.subList(0, 10));
        List<String> attrValuesToSet = new ArrayList<>(allAttrValues.subList(0, 10));
        List<String> addedAttrs = new ArrayList<>();
        List<String> setAttrs = new ArrayList<>();
        for (int i = 0; i < attrNamesToSet.size(); i++)
        {
            String attrName = attrNamesToSet.get(i);
            String newValue = attrValuesToSet.get(i);
            int destIndex = rootAttrNames.indexOf(attrName);
            if (-1 != destIndex)
            {
                rootAttrValues.set(destIndex, newValue);
                setAttrs.add(attrName);
            }
            else
            {
                rootAttrNames.add(attrName);
                rootAttrValues.add(newValue);
                addedAttrs.add(attrName);
            }
            xml.setAttributeValue(attrName, newValue);            
        }
        sb = new StringBuilder("<?xml version=\"1.0\"?>");
        appendElementStart(sb, "root", rootAttrNames, rootAttrValues);
        sb.append("/>");

        assertEquals("initial atts: " + initialRootAttrNames
                + "\n deleted attrs: " + deletedAttrs
                + "\n set attrs: " + setAttrs
                + "\n added attrs: " + addedAttrs,
                sb.toString(), xml.stringValue(false).trim());        
    }
    
    @Test
    public void stringsShouldBeInterned()
    {
        Cache<String, String> stringCache = new Cache<>(HashMap::new, Function.identity());

        XMLData xml = valueOf("<?xml version=\"1.0\"?>"
                + "<element>"
                + "<subelement attr1='value1' attr2='value2'/>"
                + "<subelement attr1='value2' attr2='value1'/>"                
                + "</element>");
        xml.internalizeStrings(stringCache::get);

        Element root = xml.getDocumentRoot();
        Element subelement1 = (Element) root.getFirstChild();
        Element subelement2 = (Element) subelement1.getNextSibling();
        // element names
        assertSame(subelement1.getNodeName(), subelement2.getNodeName());
        // attribute values
        assertSame(subelement1.getAttribute("attr1"), subelement2.getAttribute("attr2"));
        assertSame(subelement1.getAttribute("attr2"), subelement2.getAttribute("attr1"));
        // attribute names
        Map<String, String> attrNames = new HashMap<>();
        String el1attr1 = subelement1.getAttributes().item(0).getNodeName();
        String el1attr2 = subelement1.getAttributes().item(1).getNodeName();
        attrNames.put(el1attr1, el1attr1);
        attrNames.put(el1attr2, el1attr2);
        String el2attr1 = subelement2.getAttributes().item(0).getNodeName();
        String el2attr2 = subelement2.getAttributes().item(1).getNodeName();
        assertSame(el2attr1, attrNames.get(el2attr1));
        assertSame(el2attr2, attrNames.get(el2attr2));
    }
    
    @Test
    public void testNonWellFormedXML() throws Exception
    {
        try
        {
            valueOfWithException("<intentionallyBrokenXML");
            fail("expected exception");
        }
        catch (XMLBadDataException ex)
        {
            // OK
        }
        XMLData xml = valueOf("<root");
        assertTrue(xml.isDummyRoot());
    }
    
    @Test
    public void xml11() throws Exception
    {
        String xmlStr = "<?xml version=\"1.1\"?><root x=\"&#4;\"/>";
        XMLData xml = valueOf(xmlStr);
        assertEquals(4, xml.getAttributeStringValue("x").charAt(0));
        assertEquals(xmlStr, xml.stringValue(false).trim());
    }
    
    @Test
    public void beautifyXMLWithTextAndCDATANodes() throws Exception
    {
        String EOL = XMLPrettyOutputBuilder.EOL;
        String s = "<?xml version=\"1.0\"?>" + EOL + "<root>something</root>";
        assertEquals(s, valueOf(s).stringValue(true).trim());
        
        s = "<?xml version=\"1.0\"?>" + XMLPrettyOutputBuilder.EOL + "<root>   something    </root>";
        assertEquals(s, valueOf(s).stringValue(true).trim());
        
        s = "<?xml version=\"1.0\"?>" + XMLPrettyOutputBuilder.EOL
                + "<root>" + XMLPrettyOutputBuilder.EOL
                + "   something    " + XMLPrettyOutputBuilder.EOL
                + "</root>";
        assertEquals(s, valueOf(s).stringValue(true).trim());
        
        s = "<?xml version=\"1.0\"?>" + XMLPrettyOutputBuilder.EOL
                + "<root>" + XMLPrettyOutputBuilder.EOL
                + "   something    " + XMLPrettyOutputBuilder.EOL
                + "</root>";
        assertEquals(s, valueOf(s).stringValue(true).trim());
        
        s = "<?xml version=\"1.0\"?>" + EOL
                + "<root>" + EOL
                + "   something    " + EOL
                + "<foo>bar</foo>"
                + "<foo>bar</foo>"
                + "</root>";
        String expected = "<?xml version=\"1.0\"?>" + EOL
                + "<root>" + EOL
                + "   something    " + EOL
                // must not print indent to not spoil previous Text node
                + "<foo>bar</foo>"  + EOL 
                + "\t<foo>bar</foo>"  + EOL 
                + "</root>";
        assertEquals(expected, valueOf(s).stringValue(true).trim());
    }
    
    @Test
    public void testSpecialCharactersInTextNodes() throws Exception
    {
        // http://www.w3.org/TR/REC-xml/#NT-CharData
        String xmlStr = "<?xml version=\"1.0\"?><text>&amp;&lt;</text>";
        XMLData xml = valueOf(xmlStr);
        assertEquals(xmlStr, xml.stringValue(false).trim());
        assertEquals("&<", xml.getTextValueOfNode());
    }
    
    @Test
    public void testSpecialCharactersInCDATA() throws Exception
    {
        String xmlStr = "<?xml version=\"1.0\"?>"
                + "<root><![CDATA[<text>&amp;&lt;</text>&<]]></root>";
        XMLData xml = valueOf(xmlStr);
        assertEquals(xmlStr, xml.stringValue(false).trim());
        assertEquals("<text>&amp;&lt;</text>&<", xml.getTextValueOfNode());
    }
    
    @Test
    public void testGetTextValueOfNode() throws Exception
    {
        String xmlStr = "<?xml version=\"1.0\"?>" 
                + "<root>"
                + "  text 1"
                + "  <child1>"
                + "    child text 1"
                + "     <grandchild>grandchild text</grandchild>"
                + "   </child1>"
                + "   <!-- comment -->"
                + "   text 2"
                + "   <child2>"
                + "     child 2 text"
                + "   </child2>"
                + "</root>";
        XMLData xml = valueOf(xmlStr);
        assertEquals("text 1        text 2", xml.getTextValueOfNode().trim());
        assertEquals("  text 1        text 2   ", xml.getTextValueOfNode());
    }
    
    @Test
    public void testNewInstance()
    {
        XMLData xml = XMLData.newInstance("root");
        assertDOMInvariantsAreMet(xml);
        assertEquals("<?xml version=\"1.0\"?><root/>", xml.stringValue(false).trim());
    }
    
    @Test
    public void testSettingSystemDTDId()
    {
        XMLData xml = XMLData.newInstance("FixFieldsMetaData");
        String dtdSystemID = "directory://ElTrader/FIXTMSMetaData/FIX.4.2-Fields.dtd";
        xml.setSystemDtdID(dtdSystemID);
        assertEquals(dtdSystemID, xml.getSystemDtdID());
        assertDOMInvariantsAreMet(xml);
        String expectedStr = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE FixFieldsMetaData SYSTEM "
                + "\"directory://ElTrader/FIXTMSMetaData/FIX.4.2-Fields.dtd\">"
                + "<FixFieldsMetaData/>";
        assertEquals(expectedStr, xml.stringValue(false).trim());
        
        // DOM tree in this case is different - same as in derived XMLDatas
        xml = ctor(new XMLData().createNewElement("FixFieldsMetaData"));
        xml.setSystemDtdID(dtdSystemID);
        assertEquals(dtdSystemID, xml.getSystemDtdID());
        assertEquals(expectedStr, xml.stringValue(false).trim());
    }
    
    @Test
    public void testXMLNoSuchAttributeException()
    {
        
        XMLData xml = ctor("directory://ElTrader/TMS/Config/processes.xml");
        xml = xml.getDocumentPart("Bootstrap", false);
        xml = xml.getDocumentPart("ELTEngineConfigurator", false);
        try 
        {
            xml.getAttributeStringValue("nonExistentAttribute");
            fail("should have thrown");
        }
        catch (XMLNoSuchAttributeException e)
        {
            assertEquals("nonExistentAttribute", e.getAttrName());
            assertEquals("directory://ElTrader/TMS/Config/processes.xml", e.getResourcePath());
            assertSame(xml.getDocumentRoot(), e.getElement());
            // see test after clone()
            assertEquals("/Bootstrap/ELTEngineConfigurator", XMLDataUtil.getXPATHLikeNodePath(e.getElement()));
        }
                
        xml.getAttributeStringValue("name"); // present
        
        xml = xml.clone();
        try 
        {
            xml.getAttributeStringValue("nonExistentAttribute");
            fail("should have thrown");
        }
        catch (XMLNoSuchAttributeException e)
        {
            assertEquals("nonExistentAttribute", e.getAttrName());
            assertEquals("directory://ElTrader/TMS/Config/processes.xml", e.getResourcePath());
            assertSame(xml.getDocumentRoot(), e.getElement());
            // note that the clone no longer contains parent element,
            // i.e. it is /ELTEngineConfigurator, not /Bootstrap/ELTEngineConfigurator anymore
            assertEquals("/ELTEngineConfigurator", XMLDataUtil.getXPATHLikeNodePath(e.getElement()));
        }
    }
    
    @Test
    public void testGetDocumentPartsByTagName() throws Exception
    {
        XMLData xml = valueOf(
            "<A l=\"1\">" +
                "test text node 1" +                
                "<Child l=\"2\"/>" +
                "<Child l=\"3\">" +
                    "<A l=\"4\"/>" +
                    "test text node 2" +                
                "</Child>" +
                "<A l=\"5\"/>" +
            "</A>");
        // search for non-existing part
        ISizedIterator<XMLData> actual = xml.getDocumentPartsByTagName("B");
        assertEquals(0, actual.size());
        
        // only root
        actual = xml.getDocumentPartsByTagName("A", false, true);
        assertEquals(2, actual.size());
        assertEquals("1", actual.next().getAttributeStringValue("l"));
        assertEquals("5", actual.next().getAttributeStringValue("l"));

        // exclude root
        actual = xml.getDocumentPartsByTagName("A", false, false);
        assertEquals(1, actual.size());

        // recursive
        actual = xml.getDocumentPartsByTagName("A", true, true);
        assertEquals(3, actual.size());
        assertEquals("1", actual.next().getAttributeStringValue("l"));
        assertEquals("4", actual.next().getAttributeStringValue("l"));
        assertEquals("5", actual.next().getAttributeStringValue("l"));

        // recursive exclude root
        actual = xml.getDocumentPartsByTagName("A", true, false);
        assertEquals(2, actual.size());
        assertEquals("4", actual.next().getAttributeStringValue("l"));
        assertEquals("5", actual.next().getAttributeStringValue("l"));

        // non-root item
        ISizedIterator<XMLData> children = xml.getDocumentPartsByTagName("Child", false, true);
        assertEquals(2, children.size());
        assertEquals("2", children.next().getAttributeStringValue("l"));
        assertEquals("3", children.next().getAttributeStringValue("l"));
        
        // Test on a "derived" XMLData
        XMLData child = xml.getDocumentParts(new XMLDataPathElement("Child", "l", "3"), false).iterator().next();
        actual = child.getDocumentPartsByTagName("A", true, true);
        assertEquals(1, actual.size());
        assertEquals("4", actual.next().getAttributeStringValue("l"));
        actual = child.getDocumentPartsByTagName("A", true, false);
        assertEquals(1, actual.size());
        assertEquals("4", actual.next().getAttributeStringValue("l"));
        actual = child.getDocumentPartsByTagName("A", false, true);
        assertEquals(1, actual.size());
        assertEquals("4", actual.next().getAttributeStringValue("l"));
        actual = child.getDocumentPartsByTagName("A", false, false);
        assertEquals(1, actual.size());
        assertEquals("4", actual.next().getAttributeStringValue("l"));
    }
    
    @Test
    public void test_isAttributeSet_vs_isAttributeDefined()
    {
        XMLData xml = valueOf("<a b='c' d=''/>");
        assertTrue(xml.isAttributeDefined("b"));
        assertTrue(xml.isAttributeDefined("d"));
        assertFalse(xml.isAttributeDefined("e"));
        
        assertTrue(xml.isAttributeSet("b"));
        assertFalse(xml.isAttributeSet("d"));
        assertFalse(xml.isAttributeSet("e"));
    }
    
    @Test
    public void testLoadingFromProperSource() throws Exception
    {
        // XMLData ctor should first try loading from DirectorySystem,
        // then try file system.
        try
        {
            Path currentDir = Paths.get(".");
            Path testXMLInFileSystem = null;
            if (Files.isWritable(currentDir)) // we have no guarantees that current dir will be writable when the test is run
            {
                try
                {
                    testXMLInFileSystem = Files.createTempFile(currentDir, getClass().getSimpleName(), ".xml");
                    Files.write(testXMLInFileSystem, Collections.singleton("<root readFromFileSystem='true'/>"), StandardCharsets.US_ASCII);
                }
                catch (Exception e) // yes, Files.isWritable("...") does not guaranty we can actually write files 
                {
                    // testXMLInFileSystem = null
                }
            }
            
            Path tmpDirectorySystemRoot = Files.createTempDirectory(getClass().getSimpleName());
            Path testXMLInDirectory = null == testXMLInFileSystem 
                    ? Files.createTempFile(tmpDirectorySystemRoot, getClass().getSimpleName(), ".xml")
                    : tmpDirectorySystemRoot.resolve(testXMLInFileSystem.getFileName());
            Files.write(testXMLInDirectory, Collections.singleton("<root readFromDirectory='true'/>"), StandardCharsets.US_ASCII);
            DirectorySystem.setDirectory(null); // initialize will fail otherwise
            DirectorySystem.initializeDefault(tmpDirectorySystemRoot.toFile().getCanonicalPath());
            
            String resourceNameWithoutPath = null != testXMLInFileSystem 
                    ? testXMLInFileSystem.getFileName().toFile().getName()
                    : testXMLInDirectory.getFileName().toFile().getName();
            try
            {
                XMLData xml = ctor("directory://" + resourceNameWithoutPath);
                assertTrue(xml.stringValue(), xml.isAttributeSet("readFromDirectory"));
                ctor(resourceNameWithoutPath);
                assertTrue(xml.stringValue(), xml.isAttributeSet("readFromDirectory"));
                Files.delete(testXMLInDirectory);
                
                if (null != testXMLInFileSystem)
                {
                    xml = ctor(resourceNameWithoutPath);
                    Files.deleteIfExists(testXMLInFileSystem); 
                    assertTrue(xml.stringValue(), xml.isAttributeSet("readFromFileSystem"));
                }
            }
            finally
            {
                Files.delete(tmpDirectorySystemRoot);            
            }
        }
        finally
        {
            DirectorySystem.setDirectory(null); // initialize will fail otherwise
            DirectorySystem.initializeDefault(dirRoot_.getCanonicalPath());
        }
    }
    
    @Test
    public void testRemoveChildNode() throws Exception
    {
        XMLData xml = valueOf("<A/>");
        xml.removeChildNode("A"); // root should not be affected
        assertEquals("<?xml version=\"1.0\"?><A/>", xml.stringValue(false).trim());
                
        xml = valueOf("<root><A/></root>");
        xml.removeChildNode("A");
        assertEquals("<?xml version=\"1.0\"?><root/>", xml.stringValue(false).trim());
                
        xml = valueOf("<root>"
                + "<A/>"
                + "<A/>"
                + "<B/>"
                + "<A/>"
                + "<C/>"
                + "<A>"
                + "  <A/>"
                + "  <D/>"
                + "</A>"
                + "</root>");
        xml.removeChildNode("A");
        assertEquals("<?xml version=\"1.0\"?><root><B/><C/></root>", xml.stringValue(false).trim());
        
        xml = valueOf("<root>"
                + "<A/>"
                + "<A k1='x'/>"
                + "<A x='v1'/>"
                + "<B/>"
                + "<A k1='v1'/>"
                + "<C/>"
                + "<A k1='v1' k2='v2'>"
                + "  <A/>"
                + "  <D/>"
                + "</A>"
                + "<A k1='v1' k2='v2' x='y'/>"
                + "</root>");
        Map<String,String> attrs = new HashMap<>();
        attrs.put("k1", "v1");
        attrs.put("k2", "v2");
        xml.removeChildNode("A", attrs);
        assertEquals("<?xml version=\"1.0\"?>"
                + "<root>"
                + "<A/>"
                + "<A k1=\"x\"/>"
                + "<A x=\"v1\"/>"
                + "<B/>"
                + "<A k1=\"v1\"/>"
                + "<C/>"
                + "</root>",
                xml.stringValue(false).trim());
    }
    
    @Test
    public void testAppendComent() throws Exception
    {
        XMLData xml = newInstance("Test");
        final String coment = "test coment <abt> \"`'? & xyz <!-&#8208; woohoo &#8208;->";
        xml.appendComment(coment);
        XMLData xml2 = valueOf(xml.stringValue(false));
        assertEquals(coment, xml2.getDocumentRoot().getFirstChild().getNodeValue());
        
        String badComent = "bad --- coment";
        try
        {
            xml.appendComment(badComent);
            fail("exception expected");
        }
        catch (IllegalArgumentException e)
        {
            // OK
        }
        
        badComent = "bad " + (char)0x0004 + " coment";
        try
        {
            xml.appendComment(badComent);
            fail("exception expected");
        }
        catch (IllegalArgumentException e)
        {
            // OK
        }
        
        badComent = " B+, B, or B-"; // <!-- B+, B, or B--->
        try
        {
            xml.appendComment(badComent);
            fail("exception expected");
        }
        catch (IllegalArgumentException e)
        {
            // OK
        }
        
        String coment2 = "test coment 2";
        xml.appendComment(coment2);
        xml2 = valueOf(xml.stringValue(false));
        assertEquals(coment, xml2.getDocumentRoot().getFirstChild().getNodeValue());
        assertEquals(coment2, xml2.getDocumentRoot().getFirstChild().getNextSibling().getNodeValue());
    }
    
    @Test
    public void testDefaultDataCyclesDetection() throws Exception
    {
        XMLData xml1 = newInstance("root");
        try
        {
            xml1.setDefaultData(xml1);
            fail("exception expected");
        }
        catch (IllegalArgumentException e)
        {
            // OK
        }
        
        XMLData xml2 = newInstance("root");
        xml2.setDefaultData(xml1);
        xml2.setDefaultData(xml1); // again
        
        try
        {
            xml1.setDefaultData(xml2);
            // xml2 -> xml1 -> xml2 -> ...
            fail("exception expected");
        }
        catch (IllegalArgumentException e)
        {
            // OK
        }
        
        XMLData xml3 = newInstance("root");
        xml3.setDefaultData(xml2);
        try
        {
            xml1.setDefaultData(xml3);
            // xml3 -> xml2 -> xml1 -> xml3 -> ...
            fail("exception expected");
        }
        catch (IllegalArgumentException e)
        {
            // OK
        }
        
        try
        {
            xml1.setDefaultData(xml2);
            // xml3 -> xml2 -> xml1 -> xml2 -> ...
            fail("exception expected");
        }
        catch (IllegalArgumentException e)
        {
            // OK
        }       
    }
    
    @Test
    public void testHasAttributes()
    {
        assertFalse(valueOf("<root/>").hasAttributes());
        assertTrue(valueOf("<root a='b'/>").hasAttributes());
    }
    
    @Test
    public void testIsEmpty()
    {
        assertFalse(valueOf("<root/>").hasChildElements());
        assertFalse(valueOf("<root></root>").hasChildElements());
        assertFalse(valueOf("<root> </root>").hasChildElements());
        assertFalse(valueOf("<root>\n</root>").hasChildElements());
        assertFalse(valueOf("<root>\r</root>").hasChildElements());
        assertFalse(valueOf("<root>\r\n</root>").hasChildElements());
        assertFalse(valueOf("<root><!--comment--></root>").hasChildElements());
        assertFalse(valueOf("<root>some text</root>").hasChildElements());
        assertFalse(valueOf("<root><![CDATA[some text]]></root>").hasChildElements());
        
        assertTrue(valueOf("<root><b/></root>").hasChildElements());
        assertTrue(valueOf("<root> abc <b/> efg </root>").hasChildElements());
    }
    
    @Test
    public void testXML11AutoDetection()
    {
        XMLData xmlData = valueOf("<root attr='a&amp;b'/>");
        String expected = "<?xml version=\"1.0\"?>"
                + "<root attr=\"a&amp;b\"/>" + XMLPrettyOutputBuilder.EOL;
        assertEquals(expected, xmlData.stringValue(false));
        
        xmlData = valueOf("<?xml version=\"1.1\"?><root attr='a&#4;b'/>");
        expected = "<?xml version=\"1.1\"?>"
                + "<root attr=\"a&#4;b\"/>" + XMLPrettyOutputBuilder.EOL;
        assertEquals(expected, xmlData.stringValue(false));
        
        String[] testStrings = new String[]{"abc", "" + (char) 4, "ab" + (char) 4 + "cd"};
        String[] expectedXMLVersions = new String[]{"1.0", "1.1", "1.1"};
        for (int i = 0; i < testStrings.length; i++)
        {
            xmlData = valueOf("<root/>");
            xmlData.setAttributeValue("attr", testStrings[i]);
            String strValue = xmlData.stringValue(false);
            String expectedXMLDecl = "<?xml version=\"" + expectedXMLVersions[i] + "\"?>";
            assertTrue(strValue, strValue.startsWith(expectedXMLDecl));
            
            xmlData = valueOf("<root/>");
            Element root = xmlData.getDocumentRoot();
            Document doc = root.getOwnerDocument();
            root.appendChild(doc.createTextNode(testStrings[i]));
            strValue = xmlData.stringValue(false);
            assertTrue(strValue, strValue.startsWith(expectedXMLDecl));
        }
    }

    @Test
    public void testEqualsDocument() throws XMLBadDataException
    {
        assertTrue(valueOfWithException("<Test/>").equalsDocument(valueOfWithException("<Test/>")));
        assertTrue(valueOfWithException("<Test/>").equalsDocument(valueOfWithException("<Test />")));
        assertTrue(valueOfWithException("<Test/>").equalsDocument(valueOfWithException("<Test\n/>")));
        assertFalse(valueOfWithException("<Test/>").equalsDocument(valueOfWithException("<Test1/>")));

        // attributes
        assertFalse(valueOfWithException("<Test/>").equalsDocument(valueOfWithException("<Test a=\"b\"/>")));
        assertTrue(valueOfWithException("<Test a=\"b\"/>").equalsDocument(valueOfWithException("<Test a=\"b\"/>")));
        assertTrue(valueOfWithException("<Test a=\"b\"/>").equalsDocument(valueOfWithException("<Test\na=\"b\"/>")));

        // text
        assertTrue(valueOfWithException("<Test></Test>").equalsDocument(valueOfWithException("<Test></Test>")));
        assertTrue(valueOfWithException("<Test></Test>").equalsDocument(valueOfWithException("<Test> </Test>")));
        assertTrue(valueOfWithException("<Test>\n</Test>").equalsDocument(valueOfWithException("<Test> </Test>")));
        assertFalse(valueOfWithException("<Test></Test>").equalsDocument(valueOfWithException("<Test>N</Test>")));
        assertTrue(valueOfWithException("<Test>N</Test>").equalsDocument(valueOfWithException("<Test>N</Test>")));
        assertTrue(valueOfWithException("<Test>N </Test>").equalsDocument(valueOfWithException("<Test>   N</Test>")));
        assertTrue(valueOfWithException("<Test>N</Test>").equalsDocument(valueOfWithException("<Test>\nN</Test>")));
    }

    @Test
    public void testRemoveChild()
    {
        final XMLData root = XMLData.newInstance("root");
        final XMLData child = XMLData.newInstance("child");
        child.addData(XMLData.newInstance("grandChild"));
        root.getChildElements().forEachRemaining(root::removeChild);
        assertFalse(root.hasChildElements());
    }

    @Test
    public void testRemoveGrandChild()
    {
        final XMLData root = XMLData.newInstance("root");
        {
            final XMLData child = XMLData.newInstance("child");
            child.addData(XMLData.newInstance("grandChild1"));
            child.addData(XMLData.newInstance("grandChild2"));
            root.addData(child);
        }

        root
            .getChildElements().forEachRemaining(child -> child.getChildElements().forEachRemaining(c -> {
                if (c.getName().endsWith("2"))
                    child.removeChild(c);
            }));
        assertEquals("<root><child><grandChild1/></child></root>", root.stringValue(XMLData.FORMATTING_LEVEL_NONE, false));
    }

    // Protected methods
    
    protected XMLData ctor(String urlConfigInfoFile) { return new XMLData(urlConfigInfoFile); }
    
    protected XMLData ctor(Element el) { return new XMLData(el); }
    
    protected XMLData newInstance(String rootElementName) { return XMLData.newInstance(rootElementName); }
    
    protected XMLData valueOf(String s) { return XMLData.valueOf(s); }
    
    protected XMLData valueOfWithException(String s) throws XMLBadDataException 
    { return XMLData.valueOfWithException(s); }

// Private methods 
    
    private void parseUsingJREJAXPImpl(Path p) throws ParserConfigurationException, SAXException,
            IOException
    {
        DocumentBuilderFactory dbf = XMLImplemenation.createDocumentBuilderFactory();
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.parse(p.toFile());
    }
    
    private static void assertDOMInvariantsAreMet(XMLData xml)
    {
        Node documentRoot = xml.getDocumentRoot();
        Document ownerDocument = documentRoot.getOwnerDocument();
        assertNotNull("documentRoot's owner document must not be null", ownerDocument);
        assertNotNull("documentRoot's parentNode must not be null", documentRoot.getParentNode());
        assertEquals(ownerDocument, documentRoot.getParentNode());
        DocumentType doctype = ownerDocument.getDoctype();
        Node child = ownerDocument.getFirstChild();
        if (null != doctype)
            assertSame(doctype, child);
        else
        {
            // skip Comment nodes
            while (Node.COMMENT_NODE == child.getNodeType())
                child = child.getNextSibling();
            assertSame(documentRoot, child);
        }
    }
    
    private static void appendElementStart(StringBuilder sb, String elementName, List<String> attrNames, List<String> attrValues)
    {
        sb.append('<').append(elementName);
        for (int i = 0; i < attrNames.size(); i++)
            sb.append(' ')
                .append(attrNames.get(i))
                .append("=\"")
                .append(attrValues.get(i)).append('"')
                ;
    }
    
    private static String toHexCodes(String s)
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < s.length(); i++)
        {
            if (!first)
                sb.append(", ");
            else
                first = false;
            if (0 != i && 0 == i%10)
                sb.append('\n');
            sb.append(String.format("%04X", s.charAt(i) & 0xFFFFF));
        }
        return sb.toString();
    }

    static Map getUserData(Document origDoc) throws NoSuchFieldException, IllegalAccessException
    {
        return (Map) ReflectionUtils.getFieldValue(origDoc, "nodeUserData"); // com.sun.org.apache.xerces.internal.dom.CoreDocumentImpl.nodeUserData
    }

// Inner classes //////////////////////////////////
    private static class XMLDataTestHelper // TODO refactor to encapsulate set of encodings
    {
        final Charset cs_;
        private final String rootElementName_;
        private final String childElementName_;
        private final String childElementAtrr_;
        private final String childElementAttrValue_;
        private final String textElementName_;
        private final String textNodeValue_;
        private final String commentNodeValue_;

        XMLDataTestHelper(Charset cs)
        {
            System.out.println("testing using " + cs.name() + "...");
            cs_ = cs;
            rootElementName_ = "\u0434\u043e\u043a\u0443\u043c\u0435\u043d\u0442";
            childElementName_ = "\u0430\u0432\u0442\u043e\u0440";
            childElementAtrr_ = "\u0438\u043c\u044f";
            String childElementAttrValue = "\u0412\u0430\u0441\u044f";
            textElementName_ = "\u0442\u0435\u043a\u0441\u0442";
            char dash = '\u2013'; // non-ASCII, https://services.inforeach.biz:9443/browse/MAIN-16071
            String textNodeValue = "\u0417\u043d\u0430\u043d\u0438\u0435 " + dash
                    + " \u0441\u0438\u043b\u0430";
            String commentNodeValue = textNodeValue;
            String surrogatePair = "\uD801\uDC00"; // Deseret character LONG I, U+10400
            // http://docs.oracle.com/javase/tutorial/i18n/text/supplementaryChars.html
            if (cs.newEncoder().canEncode(surrogatePair)) // e.g. windows-1251 cannot
            {
                // we don't append U+10400 to comment as it causes the following error on parsing: 
                // org.xml.sax.SAXParseException; lineNumber: 1; columnNumber: 133; An invalid XML character (Unicode: 0xd801) was found in the comment.
                //    at com.sun.org.apache.xerces.internal.parsers.AbstractSAXParser.parse(AbstractSAXParser.java:1239)
                //    at com.sun.org.apache.xerces.internal.jaxp.SAXParserImpl$JAXPSAXParser.parse(SAXParserImpl.java:649)
                // which seems to violate XML spec (because of iterating over Java characters, not Unicode characters/codepoints):
                // Comment ::=      '<!--' ((Char - '-') | ('-' (Char - '-')))* '-->'
                // Char    ::=      #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]              
                textNodeValue += (" " + surrogatePair);
                childElementAttrValue += (" " + surrogatePair);
            }
            textNodeValue_ = textNodeValue;
            childElementAttrValue_ = childElementAttrValue;
            
            commentNodeValue_ = " comment: " + commentNodeValue + " "; // to check extra spaces do not get trimmed
        }
        
        XMLData buildInMemory()
        {
            XMLData result = XMLData.newInstance(rootElementName_); // XXX what about XMLDtdData?
            
            XMLData child1 = new XMLData(result.createNewElement(childElementName_));
            child1.setAttributeValue(childElementAtrr_, childElementAttrValue_);
            
            Element child2Element = result.createNewElement(textElementName_);
            Text textNode = child2Element.getOwnerDocument().createTextNode(textNodeValue_);
            child2Element.appendChild(textNode);
            XMLData child2 = new XMLData(child2Element);
            result.addData(child1);
            result.addData(child2);
            result.appendComment(commentNodeValue_);
            return result;
        }
        
        String getXMLStr()
        {
            StringBuilder b = new StringBuilder();
            b.append("<?xml version='1.0' encoding='" + cs_.name() + "'?>");
            b.append("<" + rootElementName_ + ">");
            b.append("\t<" + childElementName_ + " " + childElementAtrr_ + "='"
                    + childElementAttrValue_ + "'/>");
            b.append("\t<" + textElementName_ + ">" + textNodeValue_ + "</" + textElementName_ + ">");
            b.append("<!--" + commentNodeValue_ + "-->");
            // TODO add a CDATA section
            b.append("</" + rootElementName_ + ">");
            return b.toString();
        }

        void checkAssertions(XMLData xml) throws Exception
        {
            assertDOMInvariantsAreMet(xml);
            {
                XMLData childElement = xml.getDocumentPart(childElementName_, false);
                assertNotNull("<" + childElementName_ + ".../> not found in " + xml,
                        childElement);
                assertEquals("'" + childElementAtrr_ + "' attribute value is wrong in" + xml,
                        childElementAttrValue_,
                        childElement.getAttributeStringValue(childElementAtrr_));
            }
            {
                XMLData textElement = xml.getDocumentPart(textElementName_, false);
                assertNotNull("<" + textElementName_ + ".../> not found in " + xml,
                        textElement);
                assertEquals("text inside <" + textElementName_ + ">...</" + textElementName_ + "> of " + xml,
                        textNodeValue_,
                        textElement.getTextValueOfNode());
                // the same, but using DOM                
                assertEquals("text inside <" + textElementName_ + ">...</" + textElementName_ + "> of " + xml,
                        textNodeValue_,
                        textElement.getDocumentRoot().getFirstChild().getNodeValue());
                // check comment text
                Node commentNode = textElement.getDocumentRoot().getNextSibling();
                if (Node.TEXT_NODE == commentNode.getNodeType() 
                        && XMLDataUtil.isSpace(commentNode.getNodeValue()))
                    commentNode = commentNode.getNextSibling();  // skip "pretty output" whitespace(s)
                assertEquals(Node.COMMENT_NODE, commentNode.getNodeType());
                assertEquals(commentNodeValue_, commentNode.getNodeValue());                
            }
        }
    }
}