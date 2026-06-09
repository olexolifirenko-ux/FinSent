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

import com.finsent.util.GlobalSystem;
import com.finsent.util.xml.XMLDataUtil;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/* xml spec
    3.2 Element Type Declarations
    The element structure of an XML document may, for validation purposes, be constrained using element type and attribute-list declarations. An element type declaration constrains the element's content.

    Element type declarations often constrain which element types can appear as children of the element. At user option, an XML processor may issue a warning when a declaration mentions an element type for which no declaration is provided, but this is not an error.

    An element type declaration takes the form:

    [46]  contentspec ::=  'EMPTY' | 'ANY' | Mixed | children


    where the Name gives the element type being declared.

    Validity Constraint: Unique Element Type Declaration
    No element type may be declared more than once.

    Examples of element type declarations:

    <!ELEMENT br EMPTY>
    <!ELEMENT p (#PCDATA|emph)* >
    <!ELEMENT %name.para; %content.para; >
    <!ELEMENT container ANY>


    3.2.1 Element Content
    An element type has element content when elements of that type must contain only child elements (no character data), optionally separated by white space (characters matching the nonterminal S). In this case, the constraint includes a content model, a simple grammar governing the allowed types of the child elements and the order in which they are allowed to appear. The grammar is built on content particles (cps), which consist of names, choice lists of content particles, or sequence lists of content particles:

    Element-content Models
    [47]  children ::=  (choice | seq) ('?' | '*' | '+')?
    [48]  cp ::=  (Name | choice | seq) ('?' | '*' | '+')?
    [49]  choice ::=  '(' S? cp ( S? '|' S? cp )* S? ')' [  VC: Proper Group/PE Nesting ]
    [50]  seq ::=  '(' S? cp ( S? ',' S? cp )* S? ')' [  VC: Proper Group/PE Nesting ]


    where each Name is the type of an element which may appear as a child. Any content particle in a choice list may appear in the element content at the location where the choice list appears in the grammar; content particles occurring in a sequence list must each appear in the element content in the order given in the list. The optional character following a name or list governs whether the element or the content particles in the list may occur one or more (+), zero or more (*), or zero or one times (?). The absence of such an operator means that the element or content particle must appear exactly once. This syntax and meaning are identical to those used in the productions in this specification.

    The content of an element matches a content model if and only if it is possible to trace out a path through the content model, obeying the sequence, choice, and repetition operators and matching each element in the content against an element type in the content model. For compatibility, it is an error if an element in the document can match more than one occurrence of an element type in the content model. For more information, see "E. Deterministic Content Models".

    Validity Constraint: Proper Group/PE Nesting
    Parameter-entity replacement text must be properly nested with parenthetized groups. That is to say, if either of the opening or closing parentheses in a choice, seq, or Mixed construct is contained in the replacement text for a parameter entity, both must be contained in the same replacement text. For interoperability, if a parameter-entity reference appears in a choice, seq, or Mixed construct, its replacement text should not be empty, and neither the first nor last non-blank character of the replacement text should be a connector (| or ,).

    Examples of element-content models:

    <!ELEMENT spec (front, body, back?)>
    <!ELEMENT div1 (head, (p | list | note)*, div2*)>
    <!ELEMENT dictionary-body (%div.mix; | %dict.mix;)*>


    3.2.2 Mixed Content
    An element type has mixed content when elements of that type may contain character data, optionally interspersed with child elements. In this case, the types of the child elements may be constrained, but not their order or their number of occurrences:

    Mixed-content Declaration
    [51]  Mixed ::=  '(' S? '#PCDATA' (S? '|' S? Name)* S? ')*'
       | '(' S? '#PCDATA' S? ')'  [  VC: Proper Group/PE Nesting ]
        [  VC: No Duplicate Types ]


    where the Names give the types of elements that may appear as children.

    Validity Constraint: No Duplicate Types
    The same name must not appear more than once in a single mixed-content declaration.

    Examples of mixed content declarations:

    <!ELEMENT p (#PCDATA|a|ul|b|i|em)*>
    <!ELEMENT p (#PCDATA | %font; | %phrase; | %special; | %form;)* >
    <!ELEMENT b (#PCDATA)>

   This class expand from element content model all information and build DTD tree.

   @author Alexander Prozor
*/

public class XMLDtdElementContent
{
    static final char ONCE_OR_MORE = '+';
    static final char ZERO_OR_MORE = '*';
    static final char ZERO_OR_ONCE = '?';
    static final char ONLY_ONCE = 0;

    static final char[] EMPTY_KEYWORD  = {'E','M','P','T','Y'};
    static final char[] ANY_KEYWORD    = {'A','N','Y'};
    static final char[] PCDATA_KEYWORD = {'#','P','C','D','A','T','A'};

    static final char  SPACE_CHAR = ' ';

    public static final int CONTENT_TYPE_EMPTY     = 0;
    public static final int CONTENT_TYPE_ANY       = 1;
    public static final int CONTENT_TYPE_MIXED     = 2;
    public static final int CONTENT_TYPE_CHILDREN  = 3;

    protected int contentType_;
    protected Object content_;

    // (elem?, elem+)*+
    XMLDtdElementContent(String contentspec)
    {
        // prepare PushbackReader
        content = new PushbackReader(new StringReader(contentspec));
        char ch = readChar();

        if(Character.toUpperCase(ch) == ANY_KEYWORD[0])
        {
            proceedAnyStructure();
        }
        else if(Character.toUpperCase(ch) == EMPTY_KEYWORD[0])
        {
            proceedEmptyStructure();
        }
        else // mixed or childer content
        {
            unreadChar(ch);
            proceedMixedOrChildrenStructure();
        }
    }

    void proceedAnyStructure()
    {
        // check whole word
        for(int i=1; i<ANY_KEYWORD.length; i++)
        {
            char ch = readChar();
            if(Character.toUpperCase(ch) != ANY_KEYWORD[i])
                GlobalSystem.getLogFacility().debug().write("expect ANY");
        }
        //...
        contentType_ = CONTENT_TYPE_ANY;
    }
    void proceedEmptyStructure()
    {
        // check whole word
        for(int i=1; i<EMPTY_KEYWORD.length; i++)
        {
            char ch = readChar();
            if(Character.toUpperCase(ch) != EMPTY_KEYWORD[i])
                GlobalSystem.getLogFacility().debug().write("expect EMPTY");
        }
        //...
        contentType_ = CONTENT_TYPE_EMPTY;
    }
    void proceedMixedOrChildrenStructure()
    {
        char ch = readChar();
        // expect '(S?'
        if(ch != '(')
            GlobalSystem.getLogFacility().debug().write("expect ''('' char in element's content");
        // check S?
        exctractS();
        ch = readChar();
        // detect - if we have mixed or children content
        if(ch == PCDATA_KEYWORD[0])
        {
            // read PCDATA
            for(int i=1; i<PCDATA_KEYWORD.length; i++)
            {
                ch = readChar();
                if(Character.toUpperCase(ch) != PCDATA_KEYWORD[i])
                    GlobalSystem.getLogFacility().debug().write("expect ''#PCDATA'' in element's content");
            }
            //
            proceedMixedStructure();
        }
        else
        {
            // path without first '('
            unreadChar(ch);
            proceedChildrenStructure();
        }
    }

    void proceedMixedStructure()
    {
        contentType_ = CONTENT_TYPE_MIXED;
        // prepare content's information
        content_ = new MixedModel();
        // expect S?
        exctractS();
        // expect '|' or ')'
        char ch = readChar();
        if(ch == ')')
        {// go to the end

        }
        else if(ch == '|')
        {
            // expect S?
            exctractS();
            // expect Name
            String name = proceedName();
            ((MixedModel)content_).addName(name);
            // expect S?
            exctractS();
            ch = readChar();
            while(ch != ')')
            {
                if(ch != '|')
                    GlobalSystem.getLogFacility().debug().write("expect ''|'' char in element's content ");
                // expect S?
                exctractS();
                // expect Name
                name = proceedName();
                ((MixedModel)content_).addName(name);
                // expect S?
                exctractS();
                ch = readChar();
            }
            ch = readChar();
            if(ch != '*')
                GlobalSystem.getLogFacility().debug().write("expect ''*'' char in element's content ");
        }
        else
        {
            GlobalSystem.getLogFacility().debug().write("expect '')'' or ''|'' char in element's content ");
        }

    }
    void proceedChildrenStructure()
    {
        // prepare child contents
        contentType_ = CONTENT_TYPE_CHILDREN;
        content_ = new ChildrenModel();
        //-
        // proceed Choise or Seq
        Child childContent = proceedChoiseOrSeqStructure();
        ((ChildrenModel)content_).setChild(childContent);
        char ch = readChar();

        if(ch == ZERO_OR_MORE)
        {
            ((ChildrenModel)content_).setRepeatability(ch);
        }
        else if(ch == ONCE_OR_MORE)
        {
            ((ChildrenModel)content_).setRepeatability(ch);
        }
        else if(ch == ZERO_OR_ONCE)
        {
            ((ChildrenModel)content_).setRepeatability(ch);
        }
        // by default repeatability - ONLY_ONCE

    }

    /**
     * Choise is different from Seq only with one char ('|' instead of ',')
     * so I proceed both of this structure in one function
     *
     */
    Child proceedChoiseOrSeqStructure()
    {
        // content info
        Child content = null;
        CpContent cpElement = null;
//        char ch = readChar();
//        if(ch != '(')
//            GlobalSystem.getLogFacility().debug().write("expect ''('' char in the begin of choise or seq structure");
        exctractS();
        CpContent firstCPElement = proceedCpStructure();
        exctractS();
        char ch = readChar();
        //if child contents only one name
        // doesn't metter what type of content ( seq or choise we have)
        // so I create seq contains
        if(ch == ')')
        {
            content = new SeqContent();
            content.addCPElement(firstCPElement);
        }
        while(ch != ')') // end of the structure
        {
            if(ch == ',') // we have seq
            {
                // content info
                if(content == null)
                {
                    content = new SeqContent();
                    content.addCPElement(firstCPElement);
                }
                exctractS();
                cpElement = proceedCpStructure();
                content.addCPElement(cpElement);
            }
            else if(ch == '|') // we have choise
            {
                // content info
                if(content == null)
                {
                    content = new ChoiseContent();
                    content.addCPElement(firstCPElement);
                }
                exctractS();
                cpElement = proceedCpStructure();
                content.addCPElement(cpElement);
            }
            else
            {
                GlobalSystem.getLogFacility().debug().write("error in the choise or seq structure");
            }
            exctractS();
            ch = readChar();
        }
        return content;
    }

    CpContent proceedCpStructure()
    {
        // content info
        CpContent content = new CpContent();
        Object childContent = null;
        // expect: name, choise, seq
        char ch = readChar();
        if(ch == '(') // we have choise or seq
        {
            childContent = proceedChoiseOrSeqStructure();
        }
        else // we have name
        {
            unreadChar(ch);
            childContent = proceedName();
        }
        ((CpContent)content).setContent(childContent);
        ch = readChar();
        if( ch == 0 | ch == -1) // we got end
        {
        }
        else if(ch == '*')
        {
            ((CpContent)content).setRepeatability(ch);
        }
        else if(ch == '+')
        {
            ((CpContent)content).setRepeatability(ch);
        }
        else if(ch == '?')
        {
            ((CpContent)content).setRepeatability(ch);
        }
        else
        {
            // we got '|' or ',' or ')'
            unreadChar(ch);
        }
        return content;
    }

    /**
     * Return index of first non white_space char in the content
     * @aurhor Alexander Prozor
     */
    String proceedName()
    {
        String name = "";
        char ch = readChar();
        if(ch != '_' && ch != ':' && !XMLDataUtil.isNameStartChar(ch))
            GlobalSystem.getLogFacility().debug().write("incorrect first char in the name");
        do
        {
            name += ch;
            ch = readChar();
        }
        while(XMLDataUtil.isNameChar(ch));
        unreadChar(ch);
        return name;
    }
    /**
     * Return index of first non white_space char in the content
     * @aurhor Alexander Prozor
     */
    protected void exctractS()
    {
        char ch;
        do
        {
            ch = readChar();
        }
        while(XMLDataUtil.isSpace(ch));
        unreadChar(ch);
    }
    /**
     * Return index of first non white_space char in the content
     * @aurhor Alexander Prozor
     */
    protected char readChar()
    {
        int ret=0;
        char tmp[] = new char[1];
        try
        {
            ret=content.read(tmp,0,1);
        }
        catch(IOException ex)
        {
            GlobalSystem.getLogFacility().debug().write(new Object[]
            {
                "error while reading content ",
                ex.getMessage()
            });
        }
        /* bsv if(ret == -1)
            GlobalSystem.getLogFacility().debug().write("error while reading content. the end of the stream has been reached");*/
        return tmp[0];
    }
    /**
     * Pushback one character
     * @aurhor Alexander Prozor
     */
    protected void unreadChar(char ch)
    {
        try
        {
            char tmp[] = new char[1];
            tmp[0] = ch;
            content.unread(tmp);
        }
        catch(IOException ex)
        {
            GlobalSystem.getLogFacility().debug().write(new Object[]
            {
                "error while reading content ",
                ex.getMessage()
            });
        }
    }

    /**
     * Return tree of name child elements
     * and tree of required attributs
     *
     *  List:
     *  [String]
     *  [ List ]->[String]
     *  [String]  [String]
     *  [String]  [ List ]->[String]
     *  [String]  [String]  [String]
     *
     * @author Sergey Bulakh
     */
    List getChildElements(List isRequiredAttrList, List isRepeatingAttrList)
    {
        ArrayList list = null;
        if (content_ instanceof ChildrenModel)
        {
            list = new ArrayList();
            Object content = ((ChildrenModel)content_).getChildContent();
            addElementToListChildElements(content, list, isRequiredAttrList, isRepeatingAttrList);
        }
        /* bsv else if (content_ instanceof MixedModel)
        {
        }*/
        return list;
    }

    /**
     *
     * Add one element to child list
     * @author Sergey Bulakh
     */
    protected void addElementToListChildElements(Object content, ArrayList list, List isRequiredAttrList, List isRepeatingAttrList)
    {
        if (content instanceof String)
        {
            list.add(content);
        }
        else if (content instanceof CpContent)
        {
            if ((isRepeatingAttrList != null)&&(((CpContent)content).getContent() instanceof String))
            {
                isRepeatingAttrList.add(Boolean.valueOf(((CpContent)content).isRepeating()));
            }
            if ((isRequiredAttrList != null)&&(((CpContent)content).getContent() instanceof String))
            {
                isRequiredAttrList.add(Boolean.valueOf(((CpContent)content).isRequired()));
            }
            addElementToListChildElements(((CpContent)content).getContent(), list, isRequiredAttrList, isRepeatingAttrList);
        }
        else if (content instanceof SeqContent)
        {
            ArrayList listElements = ((Child)content).getCPElementList();
            for (int i=0; i<listElements.size(); i++)
            {
                addElementToListChildElements(listElements.get(i), list, isRequiredAttrList, isRepeatingAttrList);
            }
        }
        else if (content instanceof ChoiseContent)
        {
            ArrayList listElements = ((Child)content).getCPElementList();
            ArrayList tmpList = new ArrayList();
            ArrayList tmpRequiredAttrList = new ArrayList();
            ArrayList tmpRepeatingAttrList = new ArrayList();
            for (int i=0; i<listElements.size(); i++)
            {
                addElementToListChildElements(listElements.get(i), tmpList, tmpRequiredAttrList, tmpRepeatingAttrList);
            }
            list.add(tmpList);
            if (isRequiredAttrList != null)
            {
                isRequiredAttrList.add(tmpRequiredAttrList);
            }
            if (isRepeatingAttrList != null)
            {
                isRepeatingAttrList.add(tmpRepeatingAttrList);
            }
        }
    }

     Object getContent()
    {
        return content_;
    }
    /**
     * Return content's type (CONTENT_TYPE_ANY ... )
     * @author Alexander Prozor
     */
    public int getContentType()
    {
        return contentType_;
    }

    public static void main (String[] args)
    {
        //String testModel = "((aa|bb  )+,cc?,dd,(gg|ee|ff))*";
        String testModel = "(#PCDATA|a|b|c)*";
        //String testModel = "(test|both)";
        XMLDtdElementContent model = new XMLDtdElementContent(testModel);
        System.out.println("");
    }

    // instance variables
    PushbackReader content;

    // inner classes
    /**
     * Represents mixed (pcdata and names) content of element
     *
     */
    static class MixedModel
    {
        // can contains zero or more names
        ArrayList nameList;
        MixedModel()
        {
            nameList = new ArrayList();
        }

        void addName(String name)
        {
            nameList.add(name);
        }
        List getNameList()
        {
            return nameList;
        }
    }
    /**
     * Represents children content of element
     *
     */
    static class ChildrenModel
    {
        // contains choise or seq element
        private Child content;
        /**
         * ?, +, * or empty
         *
         */
        private char repeatability = ONLY_ONCE;

        void setRepeatability(char ch)
        {
            repeatability = ch;
        }
        void setChild(Child child)
        {
            content = child;
        }
        Child getChildContent()
        {
            return content;
        }
    }
    /**
     * Represents content of children content model
     * (choise of seq)
     */
    static class Child
    {
        // contains at least one cp element
        ArrayList content = new ArrayList();

        void addCPElement(CpContent content)
        {
            this.content.add(content);
        }
        ArrayList getCPElementList()
        {
            return content;
        }
    }

    /**
     * Represents choise content
     *
     */
    static class ChoiseContent extends Child
    {
    }

    /**
     * Represents seq content
     *
     */
    static class SeqContent extends Child
    {
    }

    static class CpContent
    {
        /**
         * Can contains name, seq or choise
         *
         */
        Object content;
        /**
         * ?, +, * or empty
         *
         */
        char repeatability = ONLY_ONCE;

        /**
         * It is possible to set name, choise or seq
         * @author Alexander Prozor
         */
        void setContent(Object content)
        {
            this.content = content;
        }
        Object getContent()
        {
            return content;
        }
        void setRepeatability(char ch)
        {
            repeatability = ch;
        }

        boolean isRepeating()
        {
            boolean retValue = false;

            if (repeatability == ONCE_OR_MORE ||
                repeatability == ZERO_OR_MORE) // SB:06122001
            {
                retValue = true;
            }

            return retValue;
        }

        boolean isRequired()
        {
            switch (repeatability)
            {
                case ONLY_ONCE:
                case ONCE_OR_MORE:
                    return true;
            }
            return false;
        }
    }
}
