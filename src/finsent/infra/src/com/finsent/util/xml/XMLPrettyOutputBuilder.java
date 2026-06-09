/*
 * Copyright (c) 1997-2002 InfoReach, Inc. All Rights Reserved.
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

package com.finsent.util.xml;

import com.finsent.util.xml.parser.IXMLDataBuilder;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Builds pretty-formatted xml string.
 *
 * E.g.
 * <Element1
 *     attr1a="value1a"
 *     attr1b="value1b"
 * >
 *     <Element1.1
 *         attr1.1a="value1.1a"
 *     />
 *
 *     <Element1.2
 *         attr1.2a="value1.2a"
 *     >
 *         <Element1.2.1>
 *             <Element1.2.1.1/>
 *         </Element1.2.1>
 *     </Element1.2>
 * </Element1>
 *
 * @author Bogdan Vaskov
 * @author Vadim Hmelik
 */
@NotThreadSafe
class XMLPrettyOutputBuilder implements IXMLDataBuilder
{
    protected final static String ESC_SYMBOL_DOUBLE_QUOTES = "&quot;";
    protected final static String ESC_SYMBOL_APOSTROPHE    = "&apos;";
    protected final static String ESC_SYMBOL_AMPERSAND     = "&amp;";
    protected final static String ESC_SYMBOL_LESS          = "&lt;";
    protected final static String ESC_SYMBOL_GREATER       = "&gt;";

    protected final static String SYMBOL_DEFAULT_EOL   = "\n";
    protected final static char SYMBOL_TAB           = '\t';
    protected final static char SYMBOL_LESS          = '<';
    protected final static char SYMBOL_GREATER       = '>';
    protected final static char SYMBOL_EQUAL         = '=';
    protected final static char SYMBOL_FWD_SLASH     = '/';
    protected final static char SYMBOL_DOUBLE_QUOTES = '"';
    protected final static char SYMBOL_SPACE         = ' ';

    protected final static int SPACES_IN_TAB  = XMLBeautify.SPACES_IN_TAB;

    protected final static int SYMBOLS_IN_LINE = XMLBeautify.SYMBOLS_IN_LINE;
    
    /**
     * Note that <a href="http://www.w3.org/TR/REC-xml/#sec-line-ends">End-of-Line Handling</a> implies
     * that \r\n and \r must be converted to \n. That's why our results may
     * differ from most other XML-to-String conversion techniques.
     */
    public final static String EOL;
    
    static
    {
        String eol = SYMBOL_DEFAULT_EOL;
        try
        {
            eol = System.getProperty("line.separator", SYMBOL_DEFAULT_EOL);
        }
        catch (SecurityException _ex) { }
        EOL = eol;
    }

    private Writer writer_;
    private int indent_;
    private int formattingLevel_;
    private int indentLevel_ = 0;
    private boolean newLevel_ = false;
    private boolean childrenFound_ = false;
    private List<String> attrNames = new ArrayList<>(15);
    private List<String> attrValues = new ArrayList<>(15);
    private int elementLengthInSymbols_ = 0;
    private TextType lastTextType_ = TextType.Empty;
    private boolean multiline_;
    private StringBuilder buffer_;
    private boolean hasXML11RestrictedChars_;
    private boolean checkForXML11RestrictedChars_;

    protected XMLPrettyOutputBuilder() {}


    public XMLPrettyOutputBuilder(StringBuilder buffer, int indent, int formattingLevel)
    {
        this((Writer)null, indent, formattingLevel);
        buffer_ = buffer;
    }

    /**
     * This constructor creates parser what produces
     * indents with TAB characters if <code>pretty</code> option
     * was set to <code>true</code>.
     * @author Vadim Hmelik
     */
    public XMLPrettyOutputBuilder(Writer writer, int formattingLevel)
    {
        this(writer, -1, formattingLevel);
    }

    /**
     * Constructs instance of class.
     * @param writer Instanse of java.io.Writer
     * @param indent count of space symbols to indentate children nodes and attributes
     * @param formattingLevel formatting level
     *
     * @author Bogdan Vaskov
     */
    public XMLPrettyOutputBuilder(Writer writer, int indent, int formattingLevel)
    {
        writer_ = writer;
        indent_ = indent;
        formattingLevel_ = formattingLevel;
    }

    public void applyXMLBeautifyBackwardCompatWorkaround()
    {
        write(EOL);
    }

// IXMLDataBuilder

    @Override
    public void elementStartMet(String name, boolean multiline)
    {
        childrenFound_ = false;
        if(newLevel_)
            newLevel_ = false;

        if(lastTextType_.startNewLine_ && formattingLevel_ > XMLData.FORMATTING_LEVEL_NONE)
            printIndent();

        write(SYMBOL_LESS);
        lastTextType_ = TextType.Empty;
        write(name);
        multiline_ = multiline;
        elementLengthInSymbols_ = name.length(); 
    }

    private void closeElementStartTag(boolean sublevelsFound)
    {
        if (attrNames.size() > 0)
        {
            // Cases when attributes will be written in one line:
            //  - "one-line" format selected
            //  - "smart" format selected
            //     - whole element with leadind tabs fits in line
            //     - whole element length is less then half line
            //     - element has only one attribute

//for backward XMLBeautify compatibility
//            boolean writeAttributesInOneLine =
//                    formattingLevel_ <= XMLData.FORMATTING_LEVEL_ONE_LINE ||
//                    (formattingLevel_ == XMLData.FORMATTING_LEVEL_SMART &&
//                     (attrNames.size() == 1 ||
//                      elementLengthInSymbols_ + getIndentLength() <= SYMBOLS_IN_LINE ||
//                      elementLengthInSymbols_ <= SYMBOLS_IN_LINE/2));

            elementLengthInSymbols_ += 1 + /*node.getNodeName().length() +*/ 1 + /*element.length()*/(attrNames.size()-1) +
                (!sublevelsFound? 3: 2);                    
            boolean writeAttributesInOneLine =
                formattingLevel_ <= XMLData.FORMATTING_LEVEL_ONE_LINE ||
                (formattingLevel_ == XMLData.FORMATTING_LEVEL_SMART &&
                !multiline_ &&
                (attrNames.size() == 1 || 
                getIndentLength() + elementLengthInSymbols_ <= SYMBOLS_IN_LINE || 
                elementLengthInSymbols_ <= SYMBOLS_IN_LINE/2));

            for (int i = 0; i < attrNames.size(); i++)
            {
                if (!writeAttributesInOneLine)
                {
                    indentLevel_++;
                    printIndent();
                    indentLevel_--;
                }
                else
                    write(SYMBOL_SPACE);

                write(attrNames.get(i));
                write(SYMBOL_EQUAL);
                write(SYMBOL_DOUBLE_QUOTES);
                writeEsc(attrValues.get(i), true);
                write(SYMBOL_DOUBLE_QUOTES);
            }
            
            if(!writeAttributesInOneLine)
                printIndent();

            attrNames.clear();
            attrValues.clear();
        }

        if (!sublevelsFound)
        {
            write(SYMBOL_FWD_SLASH);
        }
        write(SYMBOL_GREATER);
        elementLengthInSymbols_ = 0;
        lastTextType_ = TextType.Empty;
    }

    @Override
    public void elementEndMet(String name)
    {
        if (!childrenFound_)
            closeElementStartTag(false);
        else
        {
            // write 'element end' tag
            if (lastTextType_.startNewLine_ && formattingLevel_ > XMLData.FORMATTING_LEVEL_NONE)
                printIndent();
            write(SYMBOL_LESS);
            write(SYMBOL_FWD_SLASH);
            write(name);
            write(SYMBOL_GREATER);
        }
        childrenFound_ = true;
        lastTextType_ = TextType.Empty;
    }

    @Override
    public void subLevel()
    {
        closeElementStartTag(true);

        indentLevel_ ++;
        newLevel_ = true;
        childrenFound_ = true;
    }

    @Override
    public void superLevel()
    {
        indentLevel_ --;
    }
    
    public void setCheckForXML11RestrictedChars(boolean checkForXML11RestrictedChars)
    {
        checkForXML11RestrictedChars_ = checkForXML11RestrictedChars;
    }

    @Override
    public void attributeMet(String element, String name, String value)
    {
        attrNames.add(name);
        attrValues.add(value);
        elementLengthInSymbols_ += name.length() + 2 + getAttrValueLength(value) + 1; 
    }
    
    /**
     * Return true in case any attribute value or text node has XML 1.1 restricted chars.
     * 
     * @see XMLDataUtil#isXML11RestrictedChar(int)
     */
    public boolean hasXML11RestrictedChars()
    {
        if (!checkForXML11RestrictedChars_)
            throw new IllegalStateException("not in 'check for XML 1.0 compatibility' mode!");
        return hasXML11RestrictedChars_;
    }

    @Override
    public void textValueMet(String text)
    {
        TextType type = TextType.type(text);
        if (type == TextType.Sense)
        {
            writeEsc(getTextWithFixedEOLs(text), false);
        }
        lastTextType_ = lastTextType_.combine(type);
    }

    @Override
    public void commentMet(String comment)
    {
        if (lastTextType_.startNewLine_ && formattingLevel_ > XMLData.FORMATTING_LEVEL_NONE)
            printIndent(lastTextType_.indentComment_);
        lastTextType_ = TextType.Empty;

        String text = "<!--" + getTextWithFixedEOLs(comment) + "-->";
        elementLengthInSymbols_ += text.length();
        write(text);
    }
    
    @Override
    public void otherNodeMet(String markupText)
    {
        markupText = markupText.trim();
        if (!markupText.isEmpty())
        {
            if (lastTextType_.startNewLine_ && formattingLevel_ > XMLData.FORMATTING_LEVEL_NONE)
                printIndent();
            lastTextType_ = TextType.Empty;

            markupText = getTextWithFixedEOLs(markupText);
            elementLengthInSymbols_ += markupText.length();
            write(markupText);
        }
    }
    

    // Avoids usage separate \n and \r symboles as EOL
    private String getTextWithFixedEOLs(String text)
    {
        StringBuilder buf = new StringBuilder();
        XMLDataUtil.appendStringWithFixedEOLs(text, buf);
        return buf.toString();
    }            

    private int getIndentLength()
    {
        return SPACES_IN_TAB*indentLevel_;
    }

    private void printIndent(boolean doIndent)
    {
        if (doIndent)
        {
            printIndent();
        }
        else
        {
            justStartNewLine();
        }
    }

    private void justStartNewLine()
    {
        write(EOL);
    }

    private void printIndent()
    {
        write(EOL);
        char[] indentBuf;
        if (indent_ < 0)
        {
            indentBuf = new char[indentLevel_];
            Arrays.fill(indentBuf, SYMBOL_TAB);
        }
        else
        {
            int indentSpacesNum = getIndentLength();
            int tabsNum = indentSpacesNum / SPACES_IN_TAB;
            int spacesNum = indentSpacesNum % SPACES_IN_TAB;
            indentBuf = new char[tabsNum + spacesNum];
            Arrays.fill(indentBuf, 0, tabsNum, SYMBOL_TAB);
            Arrays.fill(indentBuf, tabsNum, indentBuf.length, SYMBOL_SPACE);
        }

        if (writer_ != null)
        {
            try
            {
                writer_.write(indentBuf);
            }
            catch (IOException ignored) {}
        }
        else
        {
            buffer_.append(indentBuf);
        }
    }

    private void write(String string)
    {
        if (writer_ != null)
        {
            try
            {
                //unfortunately this in no way to check java.io.Writer length so we cannot avoid printing EOL into empty writer_
                writer_.write(string);
            }
            catch (IOException ignored) {}
        }
        else if (!string.equals(EOL) || buffer_.length() > 0)
        {
            buffer_.append(string);
        }
    }

    private void write(char c)
    {
        if (writer_ != null)
        {
            try
            {
                writer_.write(c);
            }
            catch (IOException ignored) {}
        }
        else
        {
            buffer_.append(c);
        }
    }
    
    private int getAttrValueLength(String s)
    {
        StringBuilder reusableBuffer = null;
        int length = 0;
        for(int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch(c)
            {
                case 60: // '<'
                    length += ESC_SYMBOL_LESS.length();
                    break;
                case 38: // '&'
                    length += ESC_SYMBOL_AMPERSAND.length();
                    break;
                case 34: // '"'
                    length += ESC_SYMBOL_DOUBLE_QUOTES.length();
                    break;
                case 62: // '>'
                    length += ESC_SYMBOL_GREATER.length(); // not necessary if isInsideString is true
                    break;
                default:
                    // VH: if character is outside of ASCII
                    //     write the numeric value;
                    //     it is needed for #8596 and for
                    //     special characters displayed by renderers
                    if(c < ' ' || c > 127)
                    {
                        if (null == reusableBuffer) 
                            reusableBuffer = new StringBuilder();
                        else
                            reusableBuffer.setLength(0);
                        reusableBuffer.append("&#").append(c + 0).append(';');
                        length += reusableBuffer.length();
                    }
                    else
                        length++;

                    // VH: And for help - below are the codes of characters
                    //     that are not allowed to be written
                    //     according to XML specification (they have to be escaped):
                    //     [x0000...x0008]
                    //     [x000b...x000c]
                    //     [x000e...x001f]
                    //     [xd800...xdfff]
                    //     [xfffe...xffff]

                break;
            }
        }
        return length;
    }

    /**
     * @param s - string to write.
     * @param isInsideString - if true, some characters will not be escaped (since they are inside double-quotes).
     */
    private void writeEsc(String s, boolean isInsideString)
    {
        StringBuilder reusableBuffer = null;
        for (int offset = 0; offset < s.length();)
        {
            int codepoint = s.codePointAt(offset);
            if (checkForXML11RestrictedChars_)
                hasXML11RestrictedChars_= hasXML11RestrictedChars_ || XMLDataUtil.isXML11RestrictedChar(codepoint);
            if (codepoint < ' ' || codepoint > 127)
            {
                if (null == reusableBuffer) 
                    reusableBuffer = new StringBuilder();
                else
                    reusableBuffer.setLength(0);
                if (!isInsideString && codepoint <= Character.MAX_VALUE 
                        && XMLDataUtil.isSpace((char)codepoint))
                    write((char)codepoint); // no need to escape these in text nodes
                else
                {
                    reusableBuffer.append("&#").append(codepoint).append(';');
                    write(reusableBuffer.toString());
                }
            }
            else
            {
                switch (codepoint)
                {
                    case '<':
                        write(ESC_SYMBOL_LESS);
                        break;
                    case '>':
                        write(ESC_SYMBOL_GREATER); // not necessary if isInsideString is true
                        break;
                    case '&':
                        write(ESC_SYMBOL_AMPERSAND);
                        break;
                    case '"':
                        write(ESC_SYMBOL_DOUBLE_QUOTES);
                        break;
                    case '\'':
                        if (isInsideString)
                        {
                            // According to XML syntax, only symbols <&" should be escaped in attribute values.
                            write((char)codepoint);
                        }
                        else
                        {
                            write(ESC_SYMBOL_APOSTROPHE);
                        }
                        break;
                    default:
                        write((char)codepoint);
                        break;
                }
            }

            offset += Character.charCount(codepoint);
        }
    }

    enum TextType
    {
        Empty(true, true),
        Space(true, true),
        EndLn(true, false),
        Sense(false, false);

        public final boolean startNewLine_;
        public final boolean indentComment_;

        TextType(boolean startNewLine, boolean indentComment)
        {
            startNewLine_ = startNewLine;
            indentComment_ = indentComment;
        }

        static TextType type(String text)
        {
            Objects.requireNonNull(text);
            int n = text.length();
            if (n == 0)
            {
                return Empty;
            }
            for (int i = 0; i < n; i += 1)
            {
                if (!XMLDataUtil.isSpace(text.charAt(i)))
                {
                    return Sense;
                }
            }
            return (XMLDataUtil.isEndOfLine(text.charAt(n - 1))) ? EndLn : Space;
        }

        TextType combine(TextType next)
        {
            Objects.requireNonNull(next);
            switch (this)
            {
                case Sense: return this;
                case Empty: return next;
                case Space:
                case EndLn: return next == Empty ? this : next;
                default: throw new AssertionError(this);
            }
        }
    }
}