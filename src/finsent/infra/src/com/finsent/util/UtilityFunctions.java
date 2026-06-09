package com.finsent.util;

import com.finsent.util.dataloader.Tokenizer;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;

public class UtilityFunctions
{
    public static String stringFromReader(Reader reader) throws IOException
    {
        StringBuffer buf = new StringBuffer();
        char[] chars = new char[1024];
        int numRead;
        while ((numRead = reader.read(chars, 0, chars.length)) > -1)
        {
            buf.append(chars, 0, numRead);
        }
        return buf.toString();
    }

    public static String arrayToString(String[] strArray, String delimiter)
    {
        return arrayToString(delimiter, false, strArray);
    }

    public static String arrayToStringWithoutEmptyElements(String delimiter, String... strArray)
    {
        return arrayToString(delimiter, true, strArray);
    }

    /**
     * @author Alexander Olifirenko
     */
    public static String arrayToString(String delimiter, boolean skipEmptyElements, String... strArray)
    {
        return arrayToString(delimiter, skipEmptyElements, false, strArray);
    }

    public static String arrayToString(String delimiter, boolean skipEmptyElements, boolean useQuotes, String... strArray)
    {
        if (strArray == null)
        {
            return null;
        }
        StringBuilder buffer = new StringBuilder();
        boolean firstElement = true;
        for (int i = 0; i < strArray.length; i++)
        {
            if (skipEmptyElements && (strArray[i] == null || strArray[i].length() == 0))
            {
                continue;
            }
            if (firstElement)
                firstElement = false;
            else
                buffer.append(delimiter);

            if(useQuotes)
            {
                buffer.append('\'');
            }

            buffer.append(strArray[i]);

            if(useQuotes)
            {
                buffer.append('\'');
            }
        }
        return buffer.toString();
    }

    /**
     * Performs Thread.sleep() for given amount of time.
     * @param sleepDuration how long to sleep, ms.
     * @return how much time has actually passed, ms.
     */
    public static long sleep(long sleepDuration)
    {
        return sleep(sleepDuration, false);
    }

    public static long sleep(long sleepDuration, boolean printException)
    {
        return sleep(sleepDuration, Math.max(sleepDuration/10, 1), printException);
    }

    /**
     *
     * @param sleepDuration how long to sleep in total, ms.
     * @param sleepQuantum how long to sleep on each step, ms.
     * @return how much time has actually passed, ms.
     */
    public static long sleep(long sleepDuration, long sleepQuantum, boolean printException)
    {
        long startTime = System.currentTimeMillis();
        long wakeUpTime = startTime+sleepDuration; // When to stop waiting
        long currentTime;
        do
        {
            try
            {
                Thread.sleep(sleepQuantum); // don't wait too long in one sleep() call - its duration is not well predictable
            }
            catch (InterruptedException ex)
            {
                final Thread currentThread = Thread.currentThread();
                if (printException)
                {
                    System.err.println(String.format(
                            "Exception in thread: %s [group: %s]. %s",
                            currentThread.getName(),
                            currentThread.getThreadGroup().getName(),
                            exceptionToString(ex)));
                }
                currentThread.interrupt();
                currentTime = System.currentTimeMillis();
                return currentTime - startTime;
            }
            currentTime = System.currentTimeMillis();
        } while (currentTime < wakeUpTime);
        return currentTime-startTime;
    }

    public static String exceptionToString(Throwable t)
    {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * @author Bogdan Vaskov
     */
    public static void writeln(Writer writer, String message, boolean autoFlush)
    {
        if(writer != null)
        {
            try
            {
                writer.write(message + GlobalDefs.EOL);
                if(autoFlush) writer.flush();
            }
            catch(IOException e) {}
        }
    }

    public static void writeln(Writer writer, String message)
    {
        writeln(writer, message, true);
    }

    public static void writeln(Writer writer, String message, int indent)
    {
        writeln(writer, addSpaceIndent(message, indent));
    }

    public static String addSpaceIndent(String text, int indent)
    {
        String indentStr = " ".repeat(indent);
        return indentStr + text.replaceAll("\n", "\n" + indentStr);
    }

    public static boolean isEmpty(String str)
    {
        return (str == null) || str.trim().isEmpty();
    }

    public static boolean isEmpty(Object[] array)
    {
        return (array == null) || (array.length == 0);
    }

    public static boolean isEmpty(Collection<?> collection)
    {
        return (collection == null) || collection.isEmpty();
    }

    public static boolean isEmpty(Map<?, ?> map)
    {
        return (map == null) || map.isEmpty();
    }

    // ---------------------------------------------------------------------
    // Methods ported from the full InfoReach UtilityFunctions, needed by the
    // XML subsystem.
    // ---------------------------------------------------------------------

    // Don't use EMPTY_STRING.equals(str) to check if string is empty. Instead, use str.isEmpty().
    public static final String EMPTY_STRING = "";

    // strings with length <= INTERN_THRESHOLD will be interned during deserialization
    private static final int INTERN_THRESHOLD = Integer.getInteger("readStringInternThreshold", 14).intValue();

    public static <T extends Comparable<T>> boolean eq(T o1, T o2)
    {
        return Objects.equals(o1, o2);
    }

    public static String defaultToString(Object obj)
    {
        if (null == obj)
            return "null";
        return obj.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(obj));
    }

    /**
     * Makes the union of two arrays. If both arrays have the same component type
     * the result is of that type; otherwise it is an {@code Object[]}.
     */
    public static <T> T[] uniteArrays(T[] array1, T[] array2)
    {
        if (array1 == null || array1.length == 0)
            return array2;
        if (array2 == null || array2.length == 0)
            return array1;

        int count = array1.length + array2.length;

        Class<?> array1Type = array1.getClass().getComponentType();
        Class<?> array2Type = array2.getClass().getComponentType();
        @SuppressWarnings("unchecked")
        T[] union = (array1Type.equals(array2Type))
            ? (T[]) Array.newInstance(array1Type, count)
            : (T[]) new Object[count];

        System.arraycopy(array1, 0, union, 0, array1.length);
        System.arraycopy(array2, 0, union, array1.length, array2.length);
        return union;
    }

    /**
     * Converts bytes to a String using the charset resolved from a leading BOM,
     * falling back to {@link StandardCharsets#UTF_8}.
     */
    public static String bytesToString(byte[] bytes)
    {
        return bytesToString(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Converts bytes to a String trying to use charset resolved by {@link #resolveCharsetByBOM},
     * using the provided {@code defaultCharset} when none is found.
     *
     * @author Andrey Aleshnikov
     */
    public static String bytesToString(byte[] bytes, Charset defaultCharset)
    {
        Charset c = resolveCharsetByBOM(bytes);
        if (null == c)
            c = defaultCharset;
        else // need to strip BOM
        {
            final String bomChar = "﻿";
            final int BOMsize = bomChar.getBytes(c).length;
            byte[] bytes2 = new byte[bytes.length - BOMsize];
            System.arraycopy(bytes, BOMsize, bytes2, 0, bytes2.length);
            bytes = bytes2;
        }
        return new String(bytes, c);
    }

    /**
     * Resolves charset according to a leading Byte Order Mark (BOM), or {@code null}.
     *
     * @author Andrey Aleshnikov
     */
    public static Charset resolveCharsetByBOM(byte[] bytes)
    {
        Charset result = null;
        if (bytes.length > 1)
        {
            if ((byte) 0xFE == bytes[0])
            {
                if ((byte) 0xFF == bytes[1])
                    result = StandardCharsets.UTF_16BE;
            }
            else if ((byte) 0xFF == bytes[0])
            {
                if ((byte) 0xFE == bytes[1])
                {
                    if (bytes.length > 3 && (byte) 0x00 == bytes[2] && (byte) 0x00 == bytes[3])
                        result = Charset.forName("UTF-32LE");
                    else
                        result = StandardCharsets.UTF_16LE;
                }
            }
            else if (bytes.length > 2)
            {
                if ((byte) 0xEF == bytes[0])
                {
                    if ((byte) 0xBB == bytes[1] && (byte) 0xBF == bytes[2])
                        result = StandardCharsets.UTF_8;
                }
                else if (bytes.length > 3 &&
                        (byte) 0x00 == bytes[0] && (byte) 0x00 == bytes[1] && (byte) 0xFE == bytes[2] && (byte) 0xFF == bytes[3])
                    result = Charset.forName("UTF-32BE");
            }
        }
        return result;
    }

    public static byte[] bytesFromStream(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int numRead;
        while ((numRead = inputStream.read(data, 0, data.length)) > -1)
        {
            buf.write(data, 0, numRead);
        }
        buf.flush();
        return buf.toByteArray();
    }

    public static String[] getTokens(String tokenString, String delimiters)
    {
        return getTokens(tokenString, delimiters, false); // without trimming by default.
    }

    public static String[] getTokens(String tokenString, String delimiters, boolean doTrimBlanks)
    {
        return getTokens(tokenString, delimiters, doTrimBlanks, true);
    }

    /**
     * Divides tokenString to tokens by the delimiters. Each token can be trimmed.
     * When {@code skipEmpty} is false, empty tokens between two delimiters are returned.
     */
    public static String[] getTokens(String tokenString, String delimiters, boolean doTrimBlanks, boolean skipEmpty)
    {
        if (tokenString == null || delimiters == null ||
            (tokenString.trim().isEmpty()) || delimiters.isEmpty())
        {
            return EmptyArrays.STRING_ARRAY;
        }

        if (skipEmpty)
        {
            StringTokenizer st = new StringTokenizer(tokenString, delimiters);
            String[] tokens = new String[st.countTokens()];
            int i = 0;
            while (st.hasMoreElements())
            {
                tokens[i] = (String) st.nextElement();
                if (doTrimBlanks)
                {
                    tokens[i] = tokens[i].trim();
                }
                i++;
            }
            return tokens;
        }
        else
        {
            Tokenizer st = new Tokenizer(tokenString, delimiters, false, doTrimBlanks);
            String[] tokens = new String[st.countTokens()];
            int i = 0;
            while (st.hasMoreTokens())
            {
                tokens[i] = st.nextToken();
                i++;
            }
            return tokens;
        }
    }

    private static Collection<String> extractTokensToCollection(Collection<String> collectionToReturn, String tokenString, String delimiters, boolean skipEmpty)
    {
        if (tokenString == null || delimiters == null ||
            (tokenString.trim().length() == 0) || delimiters.length() == 0)
        {
            return collectionToReturn;
        }

        if (skipEmpty)
        {
            StringTokenizer st = new StringTokenizer(tokenString, delimiters);
            while (st.hasMoreElements())
            {
                String s = (String) st.nextElement();
                collectionToReturn.add(s.trim());
            }
        }
        else
        {
            Tokenizer st = new Tokenizer(tokenString, delimiters);
            while (st.hasMoreTokens())
            {
                String s = st.nextToken();
                collectionToReturn.add(s.trim());
            }
        }
        return collectionToReturn;
    }

    public static Set<String> getTokensAsSet(String tokenString, String delimiters, boolean skipEmpty)
    {
        return (Set<String>) extractTokensToCollection(new HashSet<>(), tokenString, delimiters, skipEmpty);
    }

    public static List<String> getTokensAsList(String tokenString, String delimiters, boolean skipEmpty)
    {
        return (List<String>) extractTokensToCollection(new ArrayList<>(), tokenString, delimiters, skipEmpty);
    }

    public static void writeString(DataOutput out, String data) throws IOException
    {
        writeString(out, data, (null == data) ? -1 : data.length());
    }

    public static void writeString(DataOutput out, String data, int length) throws IOException
    {
        out.writeInt(length);
        if (0 < length)
        {
            for (int i = 0; i < length; i++)
                out.writeChar(data.charAt(i));
        }
    }

    public static String readString(DataInput inStream, StringBuilder reusableBuffer) throws IOException
    {
        return readString(inStream, reusableBuffer, inStream.readInt());
    }

    public static String readString(DataInput inStream, StringBuilder reusableBuffer, int length) throws IOException
    {
        if (0 < length)
        {
            try
            {
                reusableBuffer.setLength(0);
                reusableBuffer.ensureCapacity(length);
                for (int i = 0; i < length; i++)
                    reusableBuffer.append(inStream.readChar());
                return length <= INTERN_THRESHOLD ? reusableBuffer.toString().intern() : reusableBuffer.toString();
            }
            catch (OutOfMemoryError e)
            {
                GlobalSystem.getLogFacility().error().write("OutOfMemoryError while instantiating string with length " + length);
                throw e;
            }
        }
        else
        {
            return 0 == length ? EMPTY_STRING : null;
        }
    }

    public static String substituteEnvironmentVariables(String str) throws ParseException
    {
        return substituteEnvironmentVariables(str, "%", "%");
    }

    /**
     * Finds environment variables delimited by {@code prefix}/{@code suffix} in the
     * string and resolves them.
     *
     * <p>This is a minimal variant of the original: a variable spec may list
     * several {@code '|'}-separated names (first resolvable wins) and an optional
     * {@code '?'}-separated literal fallback; names are resolved against system
     * properties then environment variables. The original's security system
     * variables, config properties and user-ticket expansion are intentionally
     * not supported.
     */
    public static String substituteEnvironmentVariables(String str, String prefix, String suffix) throws ParseException
    {
        if (str == null)
            return null;
        int beginIndex = str.indexOf(prefix);
        if (beginIndex == -1)
            return str; // no env vars - return original string

        StringBuilder result = new StringBuilder();
        int copiedUpTo = 0;
        while (beginIndex != -1)
        {
            int endIndex = str.indexOf(suffix, beginIndex + prefix.length());
            if (endIndex == -1)
                throw new ParseException("No closing '" + suffix + "' found in '" + str + "'; errorOffset=" + beginIndex, beginIndex);
            result.append(str, copiedUpTo, beginIndex);
            result.append(resolveEnvironmentVariable(str.substring(beginIndex + prefix.length(), endIndex)));
            copiedUpTo = endIndex + suffix.length();
            beginIndex = str.indexOf(prefix, copiedUpTo);
        }
        result.append(str, copiedUpTo, str.length());
        return result.toString();
    }

    private static String resolveEnvironmentVariable(String spec)
    {
        String names = spec;
        String literalFallback = "";
        int q = spec.indexOf('?');
        if (q != -1)
        {
            names = spec.substring(0, q);
            literalFallback = spec.substring(q + 1);
        }
        for (String name : names.split("\\|", -1))
        {
            String value = System.getProperty(name);
            if (value == null)
                value = System.getenv(name);
            if (value != null)
                return value;
        }
        return literalFallback;
    }

    /**
     * Finds first location of an object in an array.
     * @return index of the object in the array, or -1 if it wasn't found.
     */
    public static int indexOfInArray(Object objToFind, Object[] anArray)
    {
        for (int i = 0; i < anArray.length; i++)
            if (objToFind == anArray[i] || objToFind != null && objToFind.equals(anArray[i]))
                return i;
        return -1;
    }

    /**
     * Instantiates an object of the given class using its public no-argument
     * constructor. Returns {@code null} (logging the failure) if the class can
     * not be found or instantiated.
     */
    @SuppressWarnings("unchecked")
    public static <T> T instantiateObject(String className)
    {
        try
        {
            return (T) Class.forName(className).getConstructor().newInstance();
        }
        catch (ReflectiveOperationException ex)
        {
            GlobalSystem.getLogFacility().error().write("Unable to instantiate " + className + " with empty parameters.");
            GlobalSystem.getLogFacility().error().write(ex);
        }
        return null;
    }

    public static String bytesToHexString(byte[] bytes)
    {
        int length = bytes.length;
        StringBuilder buffer = new StringBuilder(2*length);

        for (int i = 0; i < length; i++)
        {
            String byteStr = Integer.toHexString(bytes[i]);
            if (byteStr.length() == 1)
            {
                byteStr = "0".concat(byteStr);
            }
            buffer.append(byteStr.substring(byteStr.length() -2));
        }
        return buffer.toString();
    }

}
