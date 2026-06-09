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
 */


package com.finsent.util.xml.dtd;

import com.finsent.util.GlobalDefs;
import com.finsent.util.xml.XMLDataUtil;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.ext.LexicalHandler;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author Alexander Prozor
 */
public class XMLDtd implements LexicalHandler, DeclHandler 
{
// class constants

//
    public XMLDtd()
    {
        // ap pending - should be used primery numbers for
        // initial capacity of hashmap
        attListDecls = new LinkedHashMap<>();
        elementDecls = new LinkedHashMap<>();
    }
/*
<! DOCTYPE journal [
<!ELEMENT journal (contacts, issues, authors)>
...
]>
*/
    /**
     * Returns string presentation of DTD
     * @author Alexander Prozor
     */
    public String stringValue(boolean doctype)
    {
        // external dtd
        // String buffer = "<?xml encoding=\"UTF-8\"?>";
        // internal dtd
        StringBuilder buffer = new StringBuilder();
        if (doctype)
            buffer.append("<!DOCTYPE ").append(rootName_).append(" [");
        appendTextOfDTD(buffer);
        if (doctype)
            buffer.append("]>");
        return buffer.toString();
    }


//<!ELEMENT EventBrokerManager (Servers, EventBrokers)>

    /**
     * Makes and returns string presetation of DTD for all elements and
     * its attributes
     * @author Alexander Prozor
     */
    private void appendTextOfDTD(StringBuilder buffer)
    {
        Set<String> keySet = elementDecls.keySet();
        for (String currentElementName : keySet)
        {
            appendElementDeclText(currentElementName,buffer);
            buffer.append(GlobalDefs.EOL);
            appendAllAttributesDeclText(currentElementName, buffer);
            buffer.append(GlobalDefs.EOL);
        }
    }
    /**
     * Makes and returns string presetation of DTD for all elements and
     * its attributes
     * @author Alexander Prozor
     */
    private void appendElementDeclText(String elementName, StringBuilder buffer)
    {
        buffer.append("<!ELEMENT ");
        XMLDtdElementDecl elementDecl = elementDecls.get(elementName);
        buffer.append(elementDecl.getElementName());
        buffer.append(" ");
        buffer.append(elementDecl.getContentModel());
        buffer.append(">");
    }
    /**
     * Returns of DTD for all attributes of specified element
     * @author Alexander Prozor
     */
    private void appendAllAttributesDeclText(String elementName, StringBuilder buffer)
    {
        LinkedHashMap<String, XMLDtdAttributeDecl> atthash = attListDecls.get(elementName);
        if (atthash != null)// if element has attributes
        {
            buffer.append("<!ATTLIST ").append(elementName).append(GlobalDefs.EOL);
            for (XMLDtdAttributeDecl attrDecl : atthash.values())
            {
                buffer.append("   ");
                appendAttributeDeclText(attrDecl, buffer);
                buffer.append(GlobalDefs.EOL);
            }
            buffer.append(">");
        }
    }
//<!ATTLIST Server
//	      communicationMethod  ( CORBA | DCOM | INPROC | SOCKETS) #REQUIRED
//        persistentRef CDATA #IMPLIED
//        transientRef CDATA #IMPLIED
//>

    /**
     * Returns of DTD for specified attribute
     * @author Alexander Prozor
     */
    private static void appendAttributeDeclText(XMLDtdAttributeDecl attrDecl, StringBuilder buffer)
    {
        buffer.append(attrDecl.getAttributeName()).append(' ');
        // eather type of options are equals ""
        String attrType = attrDecl.getAttributeType();
        if ("ENUMERATION".equals(attrType))
        {
            buffer.append('(');
            String[] options = attrDecl.getOptions();
            int optionCount = options.length;
            for(int i=0; i<optionCount; i++)
            {
                XMLDataUtil.appendWithEscaping(options[i], buffer);
                // if current option is not last
                if(i < optionCount-1)
                    buffer.append("|");
            }
            buffer.append(")");
        }
        else
        {
            buffer.append(attrType);
        }

        if(attrDecl.isFixed())
        {
            buffer.append(" #FIXED");
        }
        String defaultValue = attrDecl.getDefaultValue();
        if(defaultValue != null && !defaultValue.isEmpty())
        {
            buffer.append(" \"");
            XMLDataUtil.appendWithEscaping(defaultValue, buffer);
            buffer.append("\"");
        }
        else if(attrDecl.isRequired())
        {
            buffer.append(" #REQUIRED");
        }
        else // implied
        {
            buffer.append(" #IMPLIED");
        }
    }

    /**
     * Builds XMLDtd class from DTD document
     * @author Alexander Prozor
     */
    public static XMLDtd valueOf(String buffer)
    {
        // add dummy xml info
        // replace doctapy with dummy
        int doctypePos = buffer.indexOf("DOCTYPE");
        int endOfRootElementDecl = buffer.indexOf('[');
        // string dtd always has internal doctype declaration
        int endOfDTD = buffer.indexOf("]>");
        if(doctypePos != -1)
            buffer = buffer.substring(0, doctypePos) + "DOCTYPE DummyRootNode "+buffer.substring(endOfRootElementDecl);
        else
            buffer = "<!DOCTYPE DummyRootNode ["+buffer;
        // insert to the begin of xml file dummy xml code
        buffer = "<?xml version=\"1.0\"?>"+buffer;

        if(endOfDTD != -1)
        {
            buffer = buffer.substring(0, endOfDTD);
            buffer += "<!ELEMENT DummyRootNode EMPTY > ]>";
        }    
        else
            buffer += "<!ELEMENT DummyRootNode EMPTY > ]>";

        buffer += "<DummyRootNode />";

        XMLDtdData data = (XMLDtdData)XMLDtdData.valueOf(buffer);
        XMLDtd dtd = data.getDtd();
        dtd.elementDecls.remove("DummyRootNode");
        return dtd;
    }

    /**
     * Makes list of all elements , presents in this document
     * @author Alexander Prozor
     */
    public Iterator<String> makeElementList()
    {
        return elementDecls.keySet().iterator();
    }
    /**
     * Return an <var>AttDef</var> instance that matches the specified <var>elementName</var>
     * and <var>attributeName</var> in this DTD's internal and external subsets. 
     * @param   elementName   The Element name to match in the internal and external DTD subsets.
     * @param   attributeName The Attribute name to match in <var>elementName</var>.
     * @return                The matching attribute definition, or <var>null</var> if no match.
     * @see #addElement
     * @see #getAttributeDeclarations
     * @see #isAttributeDeclared
     */
    public XMLDtdAttributeDecl getAttributeDeclaration(String elementName, String attributeName) {
        Map<String, XMLDtdAttributeDecl> atthash = attListDecls.get(elementName);
        if (null == atthash)  return null;
        return atthash.get(attributeName);
    }
    /**
     * Gets all attributes name for specified element
     * @author Alexander Prozor
     */
    public Iterator<String> getAttributeList(String elementName)
    {
        Map<String, ?> atthash = attListDecls.get(elementName);
        if (null == atthash)  return null;
        return atthash.keySet().iterator();
    }

    /**
     * Return whether an attribute definition exists that matches the specified <var>elementName</var>
     * and <var>attributeName</var> in this DTD's internal and external subsets.
     * @param   elementName   The Element name to match in the internal and external DTD subsets.
     * @param   attributeName The Attribute name to match in <var>elementName</var>.
     * @return                =true if the attribute definition exists; otherwise, =false.
     * @see #addElement
     * @see #getAttributeDeclarations
     * @see #getAttributeDeclaration
     */
    public boolean isAttributeDeclared(String elementName, String attributeName) {
        return null != getAttributeDeclaration(elementName, attributeName);
    }
    /**
     * Return an Enumeration instance of all element declarations in this DTD's internal
     * and external subsets.
     * @return              An enumeration of all element declarations.
     * @see #addElement
     * @see #getElementDeclaration
     * @see #isElementDeclared
     * @see #makeContentElementList
     *
    public Enumeration getElementDeclarations() {
        return this.elementDecls.elements();
    } */

    /**
     * Return an <var>ElementDecl</var> instance that matches the specified <var>elementName</var>
     * in this DTD's internal and external subsets.
     * @param   elementName   The Element name to match in the internal and external DTD subsets.
     * @return                The matching element definition, or <var>null</var> if no match.
     * @see #addElement
     * @see #getElementDeclarations
     * @see #isElementDeclared
     * @see #makeContentElementList
     */
    public XMLDtdElementDecl getElementDeclaration(String elementName)
    {
        return (XMLDtdElementDecl)elementDecls.get(elementName);
    }

    /**
     * Return whether an element definition exists that matches the specified <var>elementName</var>
     * in this DTD's internal and external subsets.
     * @param   elementName   The Element name to match in the internal and external DTD subsets.
     * @return                =true if the element definition exists; otherwise, =false.
     * @see #addElement
     * @see #getElementDeclarations
     * @see #getElementDeclaration
     * @see #makeContentElementList
     */
    public boolean isElementDeclared(String elementName) {
        return elementDecls.containsKey(elementName);
    }

    // instance mutators
    public void addElementDecl(String elementName, String contentModel)
    {
        elementDecls.put(elementName, new XMLDtdElementDecl(elementName, contentModel));
    }
    
    public void attributeDecl(
            String eName, 
            String aName, 
            String type,
            String mode,
            String value) throws SAXException
    {
//        <!ELEMENT AdvSide EMPTY>
//        <!ATTLIST AdvSide
//           Alias    CDATA #FIXED "AdvSide"
//           FIXTag   CDATA #FIXED "4"
//           DataType CDATA #FIXED "char"
//           Validation CDATA #FIXED "list"
//           Value (B|S|X|T) #REQUIRED
//           ValueDesc CDATA #FIXED "B=Buy;S=Sell;X=Cross;T=Trade"
//        >
        boolean isFixed = "#FIXED".equals(mode);
        boolean isRequired = !isFixed && "#REQUIRED".equals(mode); // TODO check spec: both fixed and required?
        String[] options = parseEnumeration(type);
        XMLDtdAttributeDecl attrDecl = new XMLDtdAttributeDecl(eName, aName,
                null != options ? "ENUMERATION" : type,
                options, value, isFixed, isRequired);

        LinkedHashMap<String, XMLDtdAttributeDecl> atthash = attListDecls.get(eName);
        if (null == atthash)
            atthash = new LinkedHashMap<String, XMLDtdAttributeDecl>();
        atthash.put(aName, attrDecl);
        attListDecls.put(eName, atthash);
    }
    

    /**
     * @see http://www.w3.org/TR/REC-xml/#NT-Enumeration
     * @author Andrey Aleshnikov
     */
    private String[] parseEnumeration(final String type) // TODO optimize
    {
        if (null == type || '(' != type.charAt(0))
            return null;
        int enumerationEnd = type.lastIndexOf(')');
        if (-1 == enumerationEnd)
            throw new IllegalArgumentException("'" + type
                    + "' is not a part of well-formed doctype enumeration");
        String toParse = type.substring(1, enumerationEnd);
        String[] result = toParse.split("\\|");
        for (int i = 0; i < result.length; i++)
            result[i] = result[i].trim().intern();

        return result;
    }
    public void addAttributeDecl(
        String		elementName,
        String		attributeName,
        String		attributeType,
        String		options [],
        String		defaultValue,
        boolean		isFixed,
        boolean		isRequired
                                )
    {
        XMLDtdAttributeDecl attrDecl = new XMLDtdAttributeDecl(
                                        elementName,
                                        attributeName,
                                        attributeType,
                                        options,
                                        defaultValue,
                                        isFixed,
                                        isRequired
                                                  );

        LinkedHashMap<String, XMLDtdAttributeDecl> atthash = attListDecls.get(elementName);
        if (null == atthash)
            atthash = new LinkedHashMap<>();
        atthash.put(attributeName, attrDecl);
        attListDecls.put(elementName, atthash);
    }
    // listener's part


    @Override
    public void elementDecl (String elementName, String contentModel)
    throws SAXException
    {
        this.addElementDecl(elementName, contentModel);
    }


    @Override
    public void startDTD(String name, String publicId, String systemId) throws SAXException
    {
        rootName_ = name;
        publicID_ = publicId;
        systemID_ = systemId;
    }

    // TODO doc
    public String getRootName() { return rootName_; }
    public String getSystemID() { return systemID_; }
    public String getPublicID() { return publicID_; }
    
    

// instance variables
    Map<String, LinkedHashMap<String, XMLDtdAttributeDecl>> attListDecls;
    Map<String, XMLDtdElementDecl> elementDecls;
    /**
     * The name of element, from which we began to parse XML tree
     * @author Alexander Prozor
     */
    String rootName_;
    /**
     * system ID of DTD
     * @author Alexander Prozor
     */
    String systemID_;
    /**
     * public ID of DTD
     * @author Alexander Prozor
     */
    String publicID_;
    
    @Override
    public void internalEntityDecl(String name, String value) throws SAXException  { }
    
    @Override
    public void externalEntityDecl(String name, String publicId, String systemId) throws SAXException { }
    
    @Override
    public void endDTD() throws SAXException { }
    
    @Override
    public void startEntity(String name) throws SAXException { }
    
    @Override
    public void endEntity(String name) throws SAXException { }
    
    @Override
    public void startCDATA() throws SAXException { }
    
    @Override
    public void endCDATA() throws SAXException { }
    @Override
    public void comment(char[] ch, int start, int length) throws SAXException { }
}