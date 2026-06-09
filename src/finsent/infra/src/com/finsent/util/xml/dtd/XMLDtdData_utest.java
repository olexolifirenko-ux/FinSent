package com.finsent.util.xml.dtd;

import com.finsent.directory.DirectorySystem;
import com.finsent.util.ISizedIterator;
import com.finsent.util.xml.XMLBadDataException;
import com.finsent.util.xml.XMLData;
import com.finsent.util.xml.XMLData_utest;
import org.junit.Ignore;
import org.junit.Test;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class XMLDtdData_utest extends XMLData_utest
{
    @Test
    public void testGetDtd()
    {
        XMLDtdData xmlDTD = new XMLDtdData(new File(dirRoot_, "ElTrader/FIXTMSMetaData/FIX.4.2-Fields.xml").getPath());
        XMLDtd dtd = xmlDTD.getDtd();
        assertNotNull(dtd);
        assertNotNull(dtd.getElementDeclaration("AdvSide"));
        {
            XMLDtdAttributeDecl advSideFIXTag = dtd.getAttributeDeclaration("AdvSide", "FIXTag");
            assertTrue(advSideFIXTag.isFixed());
            assertFalse(advSideFIXTag.isRequired());
            assertEquals("FIXTag", advSideFIXTag.getAttributeName());
            assertEquals("4", advSideFIXTag.getDefaultValue());
            assertEquals("CDATA", advSideFIXTag.getAttributeType());
            assertNull(advSideFIXTag.getOptions());
            assertEquals("AdvSide", advSideFIXTag.getOwnerElementName());
        }
        {
            XMLDtdAttributeDecl advSideValue = dtd.getAttributeDeclaration("AdvSide", "Value");
            assertFalse(advSideValue.isFixed());
            assertTrue(advSideValue.isRequired());
            assertEquals("Value", advSideValue.getAttributeName());
            assertNull(advSideValue.getDefaultValue());
            assertEquals("ENUMERATION", advSideValue.getAttributeType());
            assertEquals(Arrays.asList("B", "S", "X", "T"), 
                    Arrays.asList(advSideValue.getOptions()));
            assertEquals("AdvSide", advSideValue.getOwnerElementName());
        }
        int elementsCount = 0;
        for (Iterator<String> elementList = dtd.makeElementList(); elementList.hasNext();)
        {
            elementList.next();
            elementsCount++;
        }
        assertEquals(408, elementsCount);
        assertEquals("directory://ElTrader/FIXTMSMetaData/FIX.4.2-Fields.dtd", xmlDTD.getSystemDtdID());
    }
    
    @Test
    public void DTDStringShouldBeInterned() throws IOException
    {
        XMLDtdData xmlDTD = new XMLDtdData(new File(dirRoot_, "ElTrader/FIXTMSMetaData/FIX.4.2-Fields.xml").getPath());
        XMLDtd dtd = xmlDTD.getDtd();
        XMLDtdAttributeDecl advSideFIXTag = dtd.getAttributeDeclaration("AdvSide", "FIXTag");
        assertSame("AdvSide".intern(), advSideFIXTag.getOwnerElementName());
        assertSame("FIXTag".intern(), advSideFIXTag.getAttributeName());
        XMLDtdAttributeDecl advSideValue = dtd.getAttributeDeclaration("AdvSide", "Value");
        String[] options = advSideValue.getOptions();
        assertSame("B".intern(), options[0]);
        assertSame("S".intern(), options[1]);
        assertSame("X".intern(), options[2]);
        assertSame("T".intern(), options[3]);
    }
    
    @Test
    public void testDtdStringRoundtrip() throws IOException
    {
        XMLDtdData xmlDtdData = new XMLDtdData(new File(dirRoot_, "ElTrader/FIXTMSMetaData/FIX.4.2-Fields.xml").getPath());
        XMLDtd dtd = xmlDtdData.getDtd();
        
        // write a copy of XML+DTD pair
        Path newDTDFile = Files.createTempFile("FIX.4.2-Fields", ".dtd");
        Path newXMLFile = Files.createTempFile("FIX.4.2-Fields", ".xml");
        try
        {
            Files.write(newDTDFile, dtd.stringValue(false).getBytes());        
            xmlDtdData.cloneUsingCustomSystemDtdID(newDTDFile.toUri().toASCIIString());
            XMLData newXMLData =  xmlDtdData.cloneUsingCustomSystemDtdID(newDTDFile.toUri().toASCIIString());
            Files.write(newXMLFile, newXMLData.stringValue(true).getBytes());
            
            XMLDtdData loadedXmlDtdData = new XMLDtdData(newXMLFile.toFile().getPath());
            XMLDtd loadedDtd = loadedXmlDtdData.getDtd();
            
            Iterator<String> origDTDElements = dtd.makeElementList();
            Iterator<String> loadedDTDElements = loadedDtd.makeElementList();
            
            while (origDTDElements.hasNext() || loadedDTDElements.hasNext())
            {
                String origElement = origDTDElements.next();
                String loadedElement = loadedDTDElements.next();
                assertEquals(origElement, loadedElement);
                // TODO more checks
            }
        }
        finally
        {
            Files.deleteIfExists(newDTDFile);
            Files.deleteIfExists(newXMLFile);
        }
    }
    
    @Test
    public void testValidation()
    {
        // well-formed,  valid
        String xmlDTDStr = "<?xml version='1.0'?>"
                + "<!DOCTYPE FixFieldsMetaData SYSTEM 'directory://ElTrader/FIXTMSMetaData/FIX.4.2-Fields.dtd'>"
                + "<FixFieldsMetaData baseMetaDataResource='abracadabra.xml'/>";
        final List<SAXParseException> parserExceptions = new ArrayList<>();
        ErrorHandler errorHandler = new ErrorHandler()
        {
            @Override
            public void warning(SAXParseException exception) throws SAXException
            { parserExceptions.add(exception); }

            @Override
            public void error(SAXParseException exception) throws SAXException
            { parserExceptions.add(exception); }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException
            { parserExceptions.add(exception); }
        };
       new XMLDtdData(new StringReader(xmlDTDStr), errorHandler);
       if (!parserExceptions.isEmpty()) 
       {
           parserExceptions.get(0).printStackTrace();
           fail(parserExceptions.get(0).getMessage());
       }

       // well-formed,  not valid
       xmlDTDStr = "<?xml version='1.0'?>"
               + "<!DOCTYPE FixFieldsMetaData SYSTEM 'directory://ElTrader/FIXTMSMetaData/FIX.4.2-Fields.dtd'>"
               + "<FixFieldsMetaData baseMetaDataResourceXXX='abracadabra.xml'/>";
       parserExceptions.clear();
       new XMLDtdData(new StringReader(xmlDTDStr), errorHandler);
       if (parserExceptions.isEmpty())  //FIXME add a test to make sure nothing is logged?
       {
           fail("should have complained about invalid XML");
       }
    }
    
    @Test
    public void ensureFileCtorWorks() throws IOException
    {
        File f = new File(dirRoot_, "ElTrader/FIXTMSMetaData/FIX.4.2-Fields.xml");
        XMLDtdData xmlDTD = new XMLDtdData(f);
        assertNotNull(xmlDTD.getDtd());
    }
    
    @Test
    public void test_getDocumentPartsByTagName() throws Exception
    {
        String xmlStr = 
                "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE Root [ <!ELEMENT Root (Child)>"
                + "<!ELEMENT Child (Grandchild)?>"
                + "<!ELEMENT Grandchild (GrandGrandchild)?>"
                + "]>"
                + "<Root>" 
                + "   <Child>"
                + "      <Grandchild/>"
                + "   </Child>"
                + "</Root>";
        XMLData xml = valueOf(xmlStr);
        ISizedIterator<XMLData> it = xml.getDocumentPartsByTagName("Root");
        assertEquals(0, it.size());
        
        it = xml.getDocumentPartsByTagName("Child");
        assertEquals(1, it.size());
        assertTrue(XMLDtdData.class.isAssignableFrom(it.next().getClass()));
        
        it = xml.getDocumentPartsByTagName("Grandchild");
        assertEquals(1, it.size());
        assertTrue(XMLDtdData.class.isAssignableFrom(it.next().getClass()));
    }
    
    @Test
    public void testExternalDtdSpecifiedByRelativePath_fileSystemXmls() throws Exception
    {
        
        String dtdStr = "<!ELEMENT Root (Child?)>"
                + "<!ELEMENT Child (Grandchild)?>"
                + "<!ELEMENT Grandchild (GrandGrandchild)?>";
        Path dtd = Files.createTempFile(getClass().getSimpleName(), ".dtd");
        Files.write(dtd, Collections.singleton(dtdStr), StandardCharsets.US_ASCII);
        String xmlStr = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE Root SYSTEM '" + dtd.getFileName() + "'>"
                                + "<Root/>";
        Path xml = Files.createTempFile(getClass().getSimpleName(), ".xml");
        Files.write(xml, Collections.singleton(xmlStr), StandardCharsets.US_ASCII);
        try
        {
            XMLDtdData dtdData = new XMLDtdData(xml.toAbsolutePath().toFile().getCanonicalPath());
            assertNotNull(dtdData.getDtd().getElementDeclaration("Grandchild"));
        }
        finally
        {
            Files.delete(dtd);
            Files.delete(xml);
        }
    }
    
    @Ignore("the feature was never implemented")
    @Test
    public void testExternalDtdSpecifiedByRelativePath_directoryXmls() throws Exception
    {
        
        String dtdStr = "<!ELEMENT Root (Child?)>"
                + "<!ELEMENT Child (Grandchild)?>"
                + "<!ELEMENT Grandchild (GrandGrandchild)?>";
        Path dtd = Files.createTempFile(getClass().getSimpleName(), ".dtd");
        Files.write(dtd, Collections.singleton(dtdStr), StandardCharsets.US_ASCII);
        String xmlStr = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE Root SYSTEM '" + dtd.getFileName() + "'>"
                                + "<Root/>";
        Path xml = Files.createTempFile(getClass().getSimpleName(), ".xml");
        Files.write(xml, Collections.singleton(xmlStr), StandardCharsets.US_ASCII);
        try
        {
            Path dirRoot = xml.getParent();
            if (null == dirRoot)
                throw new AssertionError(xml.toString());
            DirectorySystem.setDirectory(null); // initialize will fail otherwise
            DirectorySystem.initializeDefault(dirRoot.toFile().getCanonicalPath());
            XMLDtdData dtdData = new XMLDtdData("directory://" + xml.getFileName());
            assertNotNull(dtdData.getDtd().getElementDeclaration("Grandchild"));
        }
        finally
        {
            DirectorySystem.setDirectory(null); // initialize will fail otherwise
            DirectorySystem.initializeDefault(dirRoot_.getCanonicalPath());
            Files.delete(dtd);
            Files.delete(xml);
        }
    }    
    
// Protected methods
    
    @Override
    protected XMLData ctor(String urlConfigInfoFile) { return new XMLDtdData(urlConfigInfoFile); }
    
    @Override
    protected XMLData ctor(Element el) { return new XMLDtdData(el); }
    
    @Override
    protected XMLData newInstance(String rootElementName) { return XMLDtdData.newInstance(rootElementName); }
    
    @Override
    protected XMLData valueOf(String s) { return XMLDtdData.valueOf(s); }
    
    @Override
    protected XMLData valueOfWithException(String s) throws XMLBadDataException 
    { return XMLDtdData.valueOfWithException(s); }
}
