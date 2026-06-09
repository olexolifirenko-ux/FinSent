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
 * @author Alexander Prozor
 *
 */


package com.finsent.util.xml;

import com.finsent.properties.PropertyUtils;
import com.finsent.util.*;
import com.finsent.util.text.LinesIterator;
import com.finsent.util.xml.wrapper.ElementWrapper;
import org.w3c.dom.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class XMLDataUtil
{
    // SAX2 Standard Feature Flags and SAX2 Standard Handler and Property IDs
    // are described here: http://docs.oracle.com/javase/7/docs/api/org/xml/sax/package-summary.html
    public static final String SAX_FEAUTURE_STRING_INTERNING = "http://xml.org/sax/features/string-interning";
    public static final String SAX_FEAUTURE_VALIDATION = "http://xml.org/sax/features/validation";
    public static final String SAX_PROPERTY_DECLARATION_HANDLER = "http://xml.org/sax/properties/declaration-handler";
    public static final String SAX_PROPERTY_LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler";
    
    /**
     * Searches for the <code>boolean</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. If the key is not found in
     * the default property list, it throws an exception
     * <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     attributeName the key.
     * @return        the <code>boolean</code> value in this property list
     *                with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public static final boolean getAttributeBooleanValue(Node node, String attributeName) throws NumberFormatException
    {
        String value = getAttributeStringValue(node, attributeName);
        return Boolean.parseBoolean(value);
    }

    /**
     * Searches for the <code>boolean</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. The method returns the
     * default value argument if the property is not found.
     *
     * @param  attributeName          the key.
     * @param  defaultValue the default value.
     * @return              the <code>boolean</code> value in this property list
     *                      with the specified key value or
     *                      <code>defaultValue</code>, if key wasn't set.
     */
    public static final boolean getAttributeBooleanValue(Node node, String attributeName, boolean defaultValue)
    {
        String value = getAttributeStringValue(node, attributeName, null);
        return (null == value ? defaultValue : Boolean.parseBoolean(value));
    }


    /**
     * Searches for the <code>double</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. If the key is not found in
     * the default property list, it throws an exception
     * <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     attributeName the key.
     * @return        the <code>double</code> value in this property list
     *                with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public static final double getAttributeDoubleValue(Node node, String attributeName)
        throws NumberFormatException
    {
        String value = getAttributeStringValue(node, attributeName);
        return Double.parseDouble(value);
    }

    /**
     * Searches for the <code>double</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. The method returns the
     * default value argument if the property is not found.
     *
     * @param  attributeName          the key.
     * @param  defaultValue the default value.
     * @return              the <code>double</code> value in this property list
     *                      with the specified key value or
     *                      <code>defaultValue</code>, if key wasn't set.
     */
    public static final double getAttributeDoubleValue(Node node, String attributeName, double defaultValue)
    {
        String value = getAttributeStringValue(node, attributeName, null);
        return (null == value ? defaultValue : Double.parseDouble(value));
    }
    /**
     * Searches for the <code>int</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. If the key is not found in
     * the default property list, it throws an exception
     * <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     attributeName the key.
     * @return        the <code>int</code> value in this property list
     *                with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public static final int getAttributeIntValue(Node node, String attributeName)
        throws NumberFormatException
    { return getAttributeIntegerValue(node, attributeName); }

    /**
     * Searches for the <code>int</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. The method returns the
     * default value argument if the property is not found.
     *
     * @param  attributeName          the key.
     * @param  defaultValue the default value.
     * @return              the <code>int</code> value in this property list
     *                      with the specified key value or
     *                      <code>defaultValue</code>, if key wasn't set.
     */
    public static final int getAttributeIntValue(Node node, String attributeName, int defaultValue)
    {
        return getAttributeIntegerValue(node, attributeName, defaultValue);
    }
    /**
     * Searches for the <code>long</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. If the key is not found in
     * the default property list, it throws an exception
     * <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     attributeName the key.
     * @return        the <code>long</code> value in this property list
     *                with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public static final long getAttributeLongValue(Node node, String attributeName)
        throws NumberFormatException
    {
        return PropertyUtils.getLong(attributeName, () -> getAttributeStringValue(node, attributeName));
    }

    /**
     * Searches for the <code>long</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. The method returns the
     * default value argument if the property is not found.
     *
     * @param  attributeName          the key.
     * @param  defaultValue the default value.
     * @return              the <code>long</code> value in this property list
     *                      with the specified key value or
     *                      <code>defaultValue</code>, if key wasn't set.
     */
    public static final long getAttributeLongValue(Node node, String attributeName, long defaultValue)
    {
        return PropertyUtils.getLong(attributeName, () -> getAttributeStringValue(node, attributeName), defaultValue);
    }
    /**
     * Searches for the <code>int</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. If the key is not found in
     * the default property list, it throws an exception
     * <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     attributeName the key.
     * @return        the <code>int</code> value in this property list
     *                with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public static final int getAttributeIntegerValue(Node node, String attributeName)
        throws NumberFormatException
    {
        return PropertyUtils.getInt(attributeName, () -> getAttributeStringValue(node, attributeName));
    }

    /**
     * Searches for the <code>int</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. The method returns the
     * default value argument if the property is not found.
     *
     * @param  attributeName          the key.
     * @param  defaultValue the default value.
     * @return              the <code>int</code> value in this property list
     *                      with the specified key value or
     *                      <code>defaultValue</code>, if key wasn't set.
     */
    public static final int getAttributeIntegerValue(Node node, String attributeName, int defaultValue)
    {
        return PropertyUtils.getInt(attributeName, () -> getAttributeStringValue(node, attributeName), defaultValue);
    }
    /**
     * Searches for the <code>String</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. If the key is not found in
     * the default property list, it throws an exception
     * <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     attributeName the key.
     * @return        the <code>String</code> value in this property list
     *                with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public static final String getAttributeStringValue(Node node, String attributeName)
    {
        String retValue = null;
        try
        {
            if (node instanceof Element)
            {
                Attr attr = ((Element) node).getAttributeNode(attributeName);
                if (null == attr)
                {
                    retValue = "<not found>";
                    throw new XMLNoSuchAttributeException(attributeName);
                }
                else
                {
                    retValue = attr.getValue();
                }
            }
            // else , if node instance of Node or Document - returns null
            return retValue;
        }
        finally
        {
            XMLData.checkAttributeAccessExt(attributeName, retValue);
        }
    }
    /**
     * Searches for the <code>int</code> property with the specified key
     * in this property list. If the key is not found in this property list,
     * the default property list is then checked. If the key is not found in
     * the default property list, it throws an exception
     * <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     defaultValue the key.
     * @return        the <code>int</code> value in this property list
     *                with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     */
    public static final String getAttributeStringValue(Node node, String attributeName, String defaultValue)
    {
        String retValue = null;
        if(node instanceof Element)
        {
            Attr attr = ((Element)node).getAttributeNode(attributeName);
            if (null == attr)
            {
                retValue = defaultValue;
            }
            else
            {
                retValue = attr.getValue();
            }
        }
        // else , if node instance of Node or Document - returns null
        return retValue;
    }
//array
    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this property list. If the key is not found
     * in this property list, the default property list is then checked.
     * If the key is not found in the default property list,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key the key.
     * @return        the array of <code>String</code> value
     *                in this property list with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     * @see       com.finsent.util.Configurator#getPropertyStringValue(String)
     */
    public static final
    String[] getAttributeArrayValue(Node node, String key )
    {
        return getAttributeArrayValue(node, key, XMLData.DEFAULT_DELIMETERS, false);
    } // Configurator.getPropertyArrayValue()

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this property list. If the key is not found
     * in this property list, the default property list is then checked.
     * If the key is not found in the default property list,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key the key.
     * @param     delimeters string contains delimeters
     * @return        the array of <code>String</code> value
     *                in this property list with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     * @see       com.finsent.util.Configurator#getPropertyStringValue(String)
     */
    public static final
    String[] getAttributeArrayValue(Node node, String key, String delimeters )
    {
        return getAttributeArrayValue(node, key, delimeters, false);
    } // Configurator.getPropertyArrayValue()

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this property list. If the key is not found
     * in this property list, the default property list is then checked.
     * If the key is not found in the default property list,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key the key.
     * @param     trimBlanks trim spaces from both ends of the strings
     * @return        the array of <code>String</code> value
     *                in this property list with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     * @see       com.finsent.util.Configurator#getPropertyStringValue(String)
     */
    public static final
    String[] getAttributeArrayValue(Node node, String key, boolean trimBlanks )
    {
        return getAttributeArrayValue(node, key, XMLData.DEFAULT_DELIMETERS, trimBlanks);
    } // Configurator.getPropertyArrayValue()

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this property list. If the key is not found
     * in this property list, the default property list is then checked.
     * If the key is not found in the default property list,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     attributeName the key.
     * @param     delimiters string contains delimiters
     * @param     trimBlanks trim spaces from both ends of the strings
     * @return        the array of <code>String</code> value
     *                in this property list with the specified key.
     * @exception XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     * @see       com.finsent.util.Configurator#getPropertyStringValue(String)
     */
    public static final
    String[] getAttributeArrayValue(Node node, String attributeName, String delimiters, boolean trimBlanks )
    {
        String str = getAttributeStringValue(node, attributeName);
        return UtilityFunctions.getTokens(str, delimiters, trimBlanks);
    } // Configurator.getPropertyArrayValue()
    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this property list. If the key is not found
     * in this property list, the default property list is then checked.
     * If the key is not found in the default property list,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key          the key.
     * @param     defaultValue the default value.
     * @return    the array of <code>String</code> value
     *            in this property list with the specified key.
     * @throws    XMLNoSuchAttributeException when no property was found for
     *                                    this key.
     * @see       com.finsent.util.Configurator#getPropertyStringValue(String)
     */
    public static final
    String[] getAttributeArrayValue(Node node, String key, String[] defaultValue )
    {
        return getAttributeArrayValue(node, key, XMLData.DEFAULT_DELIMETERS, false, defaultValue);
    } // Configurator.getPropertyArrayValue()

    /**
     * Searches for the property in form:
     * <pre>
     * String1|String2|String3|...
     * </pre>
     * with the specified key in this property list. If the key is not found
     * in this property list, the default property list is then checked.
     * If the key is not found in the default property list,
     * it throws an exception <code>XMLNoSuchAttributeException</code> with key name.
     *
     * @param     key          the key.
     * @param     delimiters string contains delimiters.
     * @param     trimBlanks is trom spaces from both ends of the strings
     * @param     defaultValue the default value.
     * @return    the array of <code>String</code> value
     *            in this property list with the specified key.
     * @throws    XMLNoSuchAttributeException throws when no property was found for
     *                                    this key.
     * @see       com.finsent.util.Configurator#getPropertyStringValue(String)
     */
    public static final
    String[] getAttributeArrayValue(Node node, String key, String delimiters,
                                    boolean trimBlanks, String[] defaultValue )
    {
        if(node instanceof Element)
        {
            String str = ((Element)node).getAttribute(key);
            if ( str.length() ==0)
            {
                return defaultValue;
            }
            return UtilityFunctions.getTokens(str, delimiters, trimBlanks);
        }
        else
        {
            return new String[0];
        }
    } // Configurator.getPropertyArrayValue()

    public static void setNodeValue(Node node, String value)
    {
        if (value != null && node != null)
        {
            boolean isSet = false;
            for (Node child = node.getFirstChild(); null != child; child = child.getNextSibling())
            {
                if (child.getNodeType() == Node.TEXT_NODE)
                {
                    child.setNodeValue(value);
                    isSet = true;
                    break;
                }
            }
            if (!isSet)
            {
                Node newNode = node.getOwnerDocument().createTextNode(value);
                node.appendChild(newNode);
            }
        }
    }

    public static String getNodeValue(Node node)
    {
        String retValue = null;
        if (node != null)
        {
            for (Node child = node.getFirstChild(); null != child; child = child.getNextSibling())
                if (child.getNodeType() == Node.TEXT_NODE)
                {
                    retValue = child.getNodeValue();
                    break;
                }
        }
        return retValue;
    }

    /**
     * Return true if specified String matches to 'Name Token' definition
     * and, thus, can be used as valid value for some attribute
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-Nmtoken">http://www.w3.org/TR/REC-xml/#NT-Nmtoken</a>
     * @author Paul Gerber
     * @author Andrey Aleshnikov
     */
    public static boolean isNameToken(String s)
    {
        if (null == s) return false;
        int length = s.length();
        if (0 == length) return false;
        for (int offset = 0; offset < length;)
        {
            int codepoint = s.codePointAt(offset);
            if (!isNameChar(codepoint))
                return false;
            offset += Character.charCount(codepoint);
        }
        return true;
    }

    /**
     * Returns true if the name is a legal XML name
     *
     * @param s the string being tested
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-Name">http://www.w3.org/TR/REC-xml/#NT-Nam</a>
     * @author Paul Gerber
     * @author Andrey Aleshnikov
     */
    public static boolean isXmlName(String s)
    {
        if (null == s) return false;
        int length = s.length();
        if (0 == length) return false;
        for (int offset = 0; offset < length;)
        {
            int codepoint = s.codePointAt(offset);
            if (0 == offset && !isNameStartChar(codepoint))
                return false;
            else if (!isNameChar(codepoint))
                return false;
            offset += Character.charCount(codepoint);
        }
        return true;
    }

    /**
     *
     * @author Alexander Lozitsky
     */
    public static String replaceWithEscaping(String input)
    {
        StringBuilder buffer = new StringBuilder(input.length() * 2);
        appendWithEscaping(input, buffer);
        return buffer.toString();
    }

    public static void appendWithEscaping(String input, StringBuilder result)
    {
        for (int i = 0; i < input.length(); i++)
        {
            char c = input.charAt(i);
            switch (c)
            {
                case '<':
                    result.append("&lt;");
                    break;
                case '>':
                    result.append("&gt;");
                    break;
                case '"':
                    result.append("&quot;");
                    break;
                case '&':
                    result.append("&amp;");
                    break;
                case '%':
                    result.append("&#37;");
                    break;
                default:
                    result.append(c);
            }
        }
    }

    /**
    *
    * @author Vladimir Gadyatskiy
    */
   public static String replaceWithEscapingWithoutRepeating(String input)
   {
       StringBuilder result = new StringBuilder(input.length()*2);
       char c;
       for (int i = 0; i < input.length(); i++)
       {
           c = input.charAt(i);
           switch (c)
           {
               case '<':
                   result.append("&lt;");
                   break;
               case '>':
                   result.append("&gt;");
                   break;
               case '"':
                   result.append("&quot;");
                   break;
               case '&':
                   if (input.indexOf("&amp;", i) != i)
                   {
                       result.append("&amp;");
                   }
                else
                {
                    result.append("&");
                }
                   break;
               default:
                   result.append(c);
           }
       }

       return result.toString();
   }

   /**
   *
   * @author Vladimir Gadyatskiy
   */
  public static String replaceWithEscapingWithoutRepeatingExceptQuotes(String input)
  {
      StringBuffer result = new StringBuffer(input.length()*2);
      char c;
      for (int i = 0; i < input.length(); i++)
      {
          c = input.charAt(i);
          switch (c)
          {
              case '<':
                  result.append("&lt;");
                  break;
              case '>':
                  result.append("&gt;");
                  break;
              case '&':
                  if (input.indexOf("&amp;", i) != i)
                  {
                      result.append("&amp;");
                  }
                else
                {
                    result.append("&");
                }
                  break;
              default:
                  result.append(c);
          }
      }

      return result.toString();
  }

   /**
   *
   * @author Vladimir Gadyatskiy
   */
  public static String replaceAmpersandsWithoutRepeating(String input)
  {
      StringBuffer result = new StringBuffer(input.length()*2);
      char c;
      for (int i = 0; i < input.length(); i++)
      {
          c = input.charAt(i);

          if (c == '&' && input.indexOf("&amp;", i) != i
                  && input.indexOf("&lt;", i) != i
                  && input.indexOf("&gt;", i) != i
                  && input.indexOf("&quot;", i) != i)
          {
              result.append("&amp;");
          }
        else
        {
            result.append(c);
        }

      }

      return result.toString();
  }

    /**
     * Writers string to writer and escapes special simblol if exsists.
     * @author Alexander Dolgin
     */
    public static void writeWithEscaping(String str, java.io.Writer writer) throws java.io.IOException
    {
        char c;
        for (int i = 0, count = str.length(); i < count; i++)
        {
            c = str.charAt(i);
            switch (c)
            {
                case '<':
                    writer.write("&lt;");
                    break;
                case '>':
                    writer.write("&gt;");
                    break;
                case '"':
                    writer.write("&quot;");
                    break;
                case '&':
                    writer.write("&amp;");
                    break;
                case 0:
                    writer.write("&#0000;");
                    break;
                default:
                    writer.write(c);
            }
        }
    }

    /**
     * Creates xml container from speficied resource.
     */
    public static XMLData createXmlDataFromResource(String resourcePath)
        throws BadResourceException, XMLBadDataException
    {
        byte[] bytes;
        try
        {
            bytes = ResourceLoader.load(resourcePath);
            XMLData result = XMLData.valueOfWithException(new String(bytes));
            return result;
        }
        catch(ResourceLoaderException ex)
        {
            throw new BadResourceException(ex);
        }
    }

    private static final String TEMPLATE_LIST = "AttributeTemplateList";
    private static final String TEMPLATE = "AttributeTemplate";
    private static final String NAME = "attributeTemplateName";
    private static final String VALUE = "attributeValue";

    public static Map loadAttributeTemplateList(String path)
        throws BadResourceException, XMLBadDataException
    {
        XMLData xml = createXmlDataFromResource(path);
        Iterator it = xml.getDocumentPartsByTagName(TEMPLATE);
        Map attributes = new HashMap();
        while (it.hasNext())
        {
            XMLData xmlAttr = (XMLData)it.next();
            String name = xmlAttr.getAttributeStringValue(NAME), value = xmlAttr.getAttributeStringValue(VALUE);
            String oldValue = (String)attributes.put(name, value);
            if (oldValue != null)
            {
                GlobalSystem.getLogFacility().warning().write("More than one " + TEMPLATE + " for " + NAME + " '" + name + "'");
            }
        }
        return attributes;
    }

    public static void adjustXMLValidCharacters()
    {
        String[] xmlCharClasses = {
            "com.sun.org.apache.xml.internal.utils.XMLChar",
            "com.sun.org.apache.xerces.internal.util.XMLChar",
            "com.sun.xml.internal.fastinfoset.org.apache.xerces.util.XMLChar",
            "org.apache.xerces.util.XMLChar"
        };

        // Sorted array of control characters 1..32 (was: new IntegerRanges("1-32").toSortedArray()).
        int[] validCharacters = new int[32];
        for (int i = 0; i < validCharacters.length; i++)
            validCharacters[i] = i + 1;
        int numberOfSuccessfulAdjusts = 0;

        for (String xmlCharClass : xmlCharClasses)
        {
            try
            {
                Class clazz = Class.forName(xmlCharClass);
                Field field = ReflectionUtils.getField(clazz, "CHARS");
                byte[] chars = (byte[]) field.get(null);
                for (int validChar : validCharacters)
                 {
                    chars[validChar] |= 0x01; // Valid character mask
                }
                numberOfSuccessfulAdjusts++;
            }
            catch (Exception e)
            {
                // nothing, it's OK
                //e.printStackTrace();
            }
        }

        if (numberOfSuccessfulAdjusts < 3)
        {
            GlobalSystem.getLogFacility().warning().write("For some reason not all parsers were adjusted");
        }
    }

    /**
     * @author Eugeny Schava
     */
    public static String getTextValueOfNode(Node node)
    {
        Node firstChild = node.getFirstChild();
        if(firstChild == null)
        {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        getTextValueOfNodeImpl(firstChild, sb);
        return sb.toString();
    }

    /**
     * @author Eugeny Schava
     * @author Andrey Aleshnikov
     */
    private static void getTextValueOfNodeImpl(Node node, StringBuilder sb)
    {
        while (node != null)
        {
            if (node.getNodeType() == Node.TEXT_NODE
                    || node.getNodeType() == Node.CDATA_SECTION_NODE)
            {
                sb.append(node.getNodeValue());
            }
            else if (node.getNodeType() != Node.ELEMENT_NODE &&
                node.getNodeType() != Node.COMMENT_NODE)
            {
                getTextValueOfNodeImpl(node.getFirstChild(), sb);
            }
            node = node.getNextSibling();
        }
    }

    public static String getRootToNodePath(Node node)
    {
        if(node == null)
        {
            return "";
        }
        else
        {
            return getRootToNodePath(node.getParentNode()) + "/" + node.getNodeName();
        }
    }  

    /**
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-NameStartChar">http://www.w3.org/TR/REC-xml/#NT-NameStartChar</a>
     * @author Andrey Aleshnikov
     */
    public static boolean isNameStartChar(int codepoint)
    {
        if (':' == codepoint) return true;
        if ('A' <= codepoint && codepoint <= 'Z') return true; 
        if ('_' == codepoint) return true; 
        if ('a' <= codepoint && codepoint <= 'z') return true; 
        if ('\u00C0' <= codepoint && codepoint <= '\u00D6') return true; 
        if ('\u00D8' <= codepoint && codepoint <= '\u00F6') return true; 
        if ('\u00F8' <= codepoint && codepoint <= '\u02FF') return true; 
        if ('\u0370' <= codepoint && codepoint <= '\u037D') return true; 
        if ('\u037F' <= codepoint && codepoint <= '\u1FFF') return true; 
        if ('\u200C' <= codepoint && codepoint <= '\u200D') return true; 
        if ('\u2070' <= codepoint && codepoint <= '\u218F') return true; 
        if ('\u2C00' <= codepoint && codepoint <= '\u2FEF') return true; 
        if ('\u3001' <= codepoint && codepoint <= '\uD7FF') return true; 
        if ('\uF900' <= codepoint && codepoint <= '\uFDCF') return true; 
        if ('\uFDF0' <= codepoint && codepoint <= '\uFFFD') return true; 
        if (65536 <= codepoint && codepoint <= 983039) return true; // [#x10000-#xEFFFF]
        return false;
    }

    /**
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-NameChar">http://www.w3.org/TR/REC-xml/#NT-NameChar</a>
     * @author Andrey Aleshnikov
     */
    public static boolean isNameChar(int codepoint)
    {
        return isNameStartChar(codepoint) || '-' == codepoint || '.' == codepoint || '0' <= codepoint && codepoint <= '9' ||'\u00B7' == codepoint || '\u0300' <= codepoint && codepoint <= '\u036F' || '\u203F' <= codepoint && codepoint <= '\u2040';
    }
    
    /**
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-Char">http://www.w3.org/TR/REC-xml/#NT-Char</a>
     * @author Andrey Aleshnikov
     */
    public static boolean isChar(int codepoint)
    {
        // any Unicode character, excluding the surrogate blocks, FFFE, and FFFF
        if (0x0009 == codepoint) return true;
        if (0x000A == codepoint) return true;
        if (0x000D == codepoint) return true;
        if (codepoint <= 0xD7FF) return 0x0020 <= codepoint;
        if (codepoint <= 0xFFFD) return 0xE000 <= codepoint ;        
        if (codepoint <= 0xEFFFF) return 0x10000 <= codepoint ;
        return false;
    }
    
    
    /**
     * Checks if given codepoint matches this productions from XML 1.1 spec:
     * <br>
     * RestrictedChar ::= [#x1-#x8] | [#xB-#xC] | [#xE-#x1F] | [#x7F-#x84] |
     * [#x86-#x9F]
     * 
     * @see <a href="http://www.w3.org/TR/xml11/#NT-RestrictedChar">http://www.w3.org/TR/xml11/#NT-RestrictedChar</a>
     * @author Andrey Aleshnikov
     */
    public static boolean isXML11RestrictedChar(int codepoint)
    {
        if (codepoint <= 0x0008) return 0x0001 <= codepoint;
        if (codepoint == 0x000B) return true;
        if (codepoint == 0x000C) return true;
        if (codepoint <= 0x001F) return 0x000E <= codepoint;
        if (codepoint <= 0x0084) return 0x007F <= codepoint;
        if (codepoint <= 0x009F) return 0x0086 <= codepoint;
        return false;
    }

    public static boolean isEndOfLine(char c)
    {
        return c == '\n' || c == '\r';
    }

    /**
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-S">http://www.w3.org/TR/REC-xml/#NT-S</a>
     * @author Andrey Aleshnikov
     */
    public static boolean isSpace(char c)
    {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
    
    /**
     * @see <a href="http://www.w3.org/TR/REC-xml/#NT-S">http://www.w3.org/TR/REC-xml/#NT-S</a>
     * @author Andrey Aleshnikov
     */
    public static boolean isSpace(String s)
    {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++)
            if (!isSpace(s.charAt(i))) return false;
        return true;
    }
    
    /**
     * @see XMLPrettyOutputBuilder#EOL
     */
    public static void appendStringWithFixedEOLs(String s, StringBuilder sb)
    {
        LinesIterator it = new LinesIterator(s);
        boolean first = true;
        while (it.hasNext())
        {
            if (first)
                first = false;
            else
                sb.append(XMLPrettyOutputBuilder.EOL);
            sb.append(it.next());
        }
    }
    
    /**
     * Obtains an XPATH-like path to current node from owning document root.
     * 
     * @author Andrey Aleshnikov
     */
    public static String getXPATHLikeNodePath(Node n)
    {
        StringBuilder result = new StringBuilder();
        List<Element> nodePath = getNodePath(n);
        for (Element el : nodePath)
            result.append('/').append(el.getTagName());
        return result.toString();
    }
    
    
    /**
     * Performs a recursive depth-first search for an element with given name. 
     * Root is not matched. Effectively identical to this code:
     * <pre>
     *   NodeList children = treeRoot.getElementsByTagName(tagName);
     *   return 0 == children.getLength() ? null : (Element)children.item(0);
     * </pre>
     * This is created because (as of Apache Xerces implementation used in Java 7/8/...):
     * <ul>
     *   <li>
     *    {@code treeRoot.getElementsByTagName(tagName).getLength()} is
     *    pretty expensive and involves finding ALL matching elements in
     *    the subtree; 
     *   </li>
     *   <li>
     *    the {@code org.w3c.dom.NodeList} implementation involved in the
     *    above code performs additional concurrent-modification-related
     *    book-keeping which may introduce an unreasonable overhead.
     *   </li>
     * </ul>
     *      
     * Does not use recursion. Does not create any objects.
     * Thread-safe for reads, not writes.
     *  
     * @param tagName use "*" to match any element.
     * 
     * @author Andrey Aleshnikov
     */
    public static Element findElement(
            final Element treeRoot,
            final String tagName)
    {
        for (Node currentNodeToTest = treeRoot.getFirstChild();
                null != currentNodeToTest && treeRoot != currentNodeToTest; )
        {
            // 1. Test search condition
            if (Node.ELEMENT_NODE == currentNodeToTest.getNodeType()
                    && ("*".equals(tagName) || currentNodeToTest.getNodeName().equals(tagName)))
                    return (Element)currentNodeToTest;
            
            // 2. Select proper next node for testing search condition
            Node tmp;
            if (null != (tmp = currentNodeToTest.getFirstChild()))
                currentNodeToTest = tmp; // depth-first                
            else // we have reached bottom of current tree 
            {
                // return back to initial tree root step by step until we find
                // first non-empty sibling sub-tree
                while (treeRoot != currentNodeToTest)  // do not escape initial subtree
                {
                    if (null == (tmp = currentNodeToTest.getNextSibling()))
                        currentNodeToTest = currentNodeToTest.getParentNode();
                    else
                    {
                        currentNodeToTest = tmp;
                        break;
                    }
                }
            }         
        }
        
        return null;
    }
    
    /**
     * Obtains a path to current node from owning document root.
     * 
     * @author Andrey Aleshnikov
     */
    private static List<Element> getNodePath(Node n)
    {
        List<Element> result = new ArrayList<>();
        Document doc = n.getOwnerDocument();
        do
        {
            result.add((Element) n);
            n = n.getParentNode();
        } while (null != n && doc != n);
        Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    public static long getTimeIntervalMillis(XMLData config, String attr, long defaultValue)
    {
        return getTimeInterval(config, attr, TimeUnit.MILLISECONDS, defaultValue);
    }

    public static long getTimeIntervalSeconds(XMLData config, String attr, long defaultValue)
    {
        return getTimeInterval(config, attr, TimeUnit.SECONDS, defaultValue);
    }

    public static long getTimeIntervalNanos(XMLData config, String attr, long defaultValue)
    {
        return getTimeInterval(config, attr, TimeUnit.NANOSECONDS, defaultValue);
    }

    public static long getTimeInterval(XMLData config, String attr, TimeUnit returnType, long defaultValue)
    {
        String stringValue = config.getAttributeStringValue(attr, null);
        if (stringValue == null)
            return defaultValue;

        return TimeUnitUtil.valueOf(stringValue, returnType);
    }

    public static long getTimeIntervalMillis(XMLData config, String attr, String defaultValue)
    {
        return getTimeInterval(config, attr,TimeUnit.MILLISECONDS, defaultValue);
    }

    public static long getTimeIntervalNanos(XMLData config, String attr, String defaultValue)
    {
        return getTimeInterval(config, attr,TimeUnit.NANOSECONDS, defaultValue);
    }

    public static long getTimeIntervalSeconds(XMLData config, String attr, String defaultValue)
    {
        return getTimeInterval(config, attr,TimeUnit.SECONDS, defaultValue);
    }

    public static long getTimeInterval(XMLData config, String attr, TimeUnit returnType, String defaultValue)
    {
        String stringValue = config.getAttributeStringValue(attr, null);
        if (stringValue == null)
            stringValue = defaultValue;

        return TimeUnitUtil.valueOf(stringValue, returnType);
    }

    public static int[] getIntArray(XMLData config, String attr)
    {
        return getIntArray(config, attr, EmptyArrays.INT_ARRAY);
    }

    public static int[] getIntArray(XMLData config, String attr, int[] defaultValue)
    {
        String values = config.getAttributeStringValue(attr, null);
        if (values == null)
            return defaultValue;

        ArrayList<Integer> intList = new ArrayList();
        for (String value : values.split(","))
        {
            value = value.trim();
            intList.add(Integer.valueOf(value));
        }
        return intList.stream().mapToInt(i -> i.intValue()).toArray();
    }

    public static XMLData prepareForUnusedAttributes(XMLData xml)
    {
        return new XMLData(new UnusedAttributesElementWrapper(xml.getDocumentRoot()));
    }

    public static Set<String> getUnusedAttributes(XMLData xml)
    {
        Element element = xml.getDocumentRoot();
        if (!(element instanceof UnusedAttributesElementWrapper))
            throw new IllegalStateException("XMLData object should be created by XMLDataUtil.prepareForUnusedAttributes method");
        return new HashSet<>(((UnusedAttributesElementWrapper) element).getUnusedAttributes());
    }

    private static class UnusedAttributesElementWrapper implements ElementWrapper
    {
        private final Element element_;
        private Set<String> unusedAttributes_;

        public UnusedAttributesElementWrapper(Element element)
        {
            element_ = element;
            NamedNodeMap attributes = element.getAttributes();
            unusedAttributes_ = IntStream.range(0, attributes.getLength())
                .mapToObj(attributes::item)
                .map(Node::getNodeName)
                .collect(Collectors.toSet());
        }

        public Set<String> getUnusedAttributes()
        {
            return unusedAttributes_;
        }

        @Override
        public Element getDelegate()
        {
            return element_;
        }

        @Override
        public String getAttribute(String name)
        {
            unusedAttributes_.remove(name);
            return ElementWrapper.super.getAttribute(name);
        }

        @Override
        public Attr getAttributeNode(String name)
        {
            unusedAttributes_.remove(name);
            return ElementWrapper.super.getAttributeNode(name);
        }
    }
}
