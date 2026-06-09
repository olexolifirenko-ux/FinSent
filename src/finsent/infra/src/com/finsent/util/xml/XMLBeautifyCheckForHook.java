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

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Dmytro.Sheyko
 */
public class XMLBeautifyCheckForHook
{
    public static final String Mode_BeautifyInPlace = "BeautifyInPlace";
    public static final String Mode_CheckForBeauty = "CheckForBeauty";
    public static final String Mode_CheckForWellFormedness = "CheckForWellFormedness";

    boolean outputOnSuccess_ = false;
    boolean outputOnFailure_ = true;
    boolean pedantic_ = false;
    String mode_ = Mode_CheckForBeauty;
    PrintStream out_ = System.out;

    public static void main(String... args)
    {
        XMLBeautifyCheckForHook main = new XMLBeautifyCheckForHook();
        main.setOutputOnSuccess(Boolean.parseBoolean(System.getProperty("output.on.success", "" + main.isOutputOnSuccess())));
        main.setOutputOnFailure(Boolean.parseBoolean(System.getProperty("output.on.failure", "" + main.isOutputOnFailure())));
        main.setPedantic(Boolean.parseBoolean(System.getProperty("pedantic", "" + main.isPedantic())));
        main.setMode(System.getProperty("mode", main.getMode()));
        main.setOut(System.out);
        int errorCode = main.run(args);
        System.exit(errorCode);
    }

    public boolean isOutputOnSuccess()
    {
        return outputOnSuccess_;
    }

    public void setOutputOnSuccess(boolean outputOnSuccess)
    {
        outputOnSuccess_ = outputOnSuccess;
    }

    public boolean isOutputOnFailure()
    {
        return outputOnFailure_;
    }

    public void setOutputOnFailure(boolean outputOnFailure)
    {
        outputOnFailure_ = outputOnFailure;
    }

    public boolean isPedantic()
    {
        return pedantic_;
    }

    public void setPedantic(boolean pedantic)
    {
        pedantic_ = pedantic;
    }

    public String getMode()
    {
        return mode_;
    }

    public void setMode(String mode)
    {
        mode_ = mode;
    }

    public PrintStream getOut()
    {
        return out_;
    }

    public void setOut(PrintStream out)
    {
        out_ = out;
    }

    public int run(String... args)
    {
        int errorCode = 0;
        IMode mode;
        PrintStream out = getOut();
        switch (mode_)
        {
            case Mode_CheckForWellFormedness:
                mode = this::doCheckForWellFormedness;
                break;
            case Mode_CheckForBeauty:
                mode = this::doCheckForBeauty;
                break;
            case Mode_BeautifyInPlace:
                mode = this::doBeautifyInPlace;
                break;
            default:
                out.println("Unsupported Mode: [" + mode_ + "]");
                return 2;
        }
        for (String arg : args)
        {
            try (BufferedReader reader = newBufferedReader(arg))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#"))
                    {
                        String fname = line;
                        String message = "";
                        String encoding = "";
                        int p = line.indexOf('\t');
                        if (p >= 0)
                        {
                            fname = line.substring(0, p);
                            message = line.substring(p + 1);
                            p = message.indexOf('\t');
                            if (p >= 0)
                            {
                                encoding = message.substring(p + 1);
                                message = message.substring(0, p);
                            }
                        }
                        if (message.isEmpty())
                        {
                            message = fname;
                        }
                        if (encoding.isEmpty())
                        {
                            encoding = XMLData.DEFAULT_ENCODING.name();
                        }
                        try
                        {
                            Path path = Paths.get(fname);
                            String actual = readFileAsString(path, encoding);
                            XMLBeautify.ValidatingXMLData validatingXMLData = new XMLBeautify.ValidatingXMLData(actual, null, false, false);
                            String expected = validatingXMLData.stringValue(true);
                            if (!XMLBeautifyUtil.hasNoFormatCheck(expected))
                            {
                                if (!mode.work(path, message, expected, actual))
                                {
                                    errorCode = 1;
                                }
                            }
                            else
                            {
                                if (isOutputOnSuccess())
                                {
                                    out.println(message + ": NO FORMAT CHECK");
                                }
                            }
                        }
                        catch (BadCharacterException e)
                        {
                            if (isOutputOnFailure())
                            {
                                out.println(message + ": (" + e.getLineNumber() + ") Bad Characters in positions " + e.getColumnPositions() + " for " + encoding);
                                out.println("\t= " + q(e.getLine()));
                                out.println("\t=  " + pointPositions(e.getColumnPositions()));
                            }
                            errorCode = 1;
                        }
                        catch (Throwable e)
                        {
                            if (isOutputOnFailure())
                            {
                                out.println(message + ": " + toString(e));
                            }
                            errorCode = 1;
                        }
                    }
                }
            }
            catch (Throwable e)
            {
                if (isOutputOnFailure())
                {
                    out.println(toString(e));
                }
                errorCode = 1;
            }
        }
        return errorCode;
    }

    private static String pointPositions(List<Integer> columnPositions)
    {
        StringBuilder builder = new StringBuilder();
        int prev = -1;
        for (int curr : columnPositions)
        {
            for (int i = 0, ii = curr - prev - 1; i < ii; i += 1)
            {
                builder.append('-');
            }
            builder.append('^');
            prev = curr;
        }
        return builder.toString();
    }

    @Nonnull
    private static String readFileAsString(Path path, String encoding) throws IOException, BadCharacterException
    {
        final char REPLACEMENT_CHAR = '\0';
        final String REPLACEMENT_STR = "" + REPLACEMENT_CHAR;
        Charset charset = Charset.forName(encoding);
        byte[] bytes = Files.readAllBytes(path);
        try
        {
            return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        }
        catch (CharacterCodingException e)
        {
            CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(REPLACEMENT_STR);
            try (LineNumberReader reader = new LineNumberReader(new InputStreamReader(new ByteArrayInputStream(bytes), decoder)))
            {
                List<Integer> positions = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    int index = line.indexOf(REPLACEMENT_CHAR);
                    if (index >= 0)
                    {
                        positions.add(index);
                        while ((index = line.indexOf(REPLACEMENT_CHAR, index + 1)) >= 0)
                        {
                            positions.add(index);
                        }
                        line = line.replace(REPLACEMENT_CHAR, '?');
                        int lineNumber = reader.getLineNumber();
                        throw new BadCharacterException(e.toString(), lineNumber, positions, line, e);
                    }
                }
            }
            throw e;
        }
    }

    boolean doCheckForWellFormedness(Path path, String message, String expected, String actual) throws IOException
    {
        // If we reach this point, the xml has been successfully parsed.
        // Therefore, we just say everything is ok.
        if (isOutputOnSuccess())
        {
            getOut().println(message + ": OK");
        }
        return true;
    }

    boolean doCheckForBeauty(Path path, String message, String expected, String actual) throws IOException
    {
        PrintStream out = getOut();
        try (
            BufferedReader ereader = new BufferedReader(new StringReader(expected));
            BufferedReader areader = new BufferedReader(new StringReader(actual));
        )
        {
            XMLBeautifyUtil.BeautyComparisonResult result = XMLBeautifyUtil.compareBeautifiedContent(isPedantic(), ereader::readLine, areader::readLine, null);
            XMLBeautifyUtil.Difference difference = result.difference_;
            if (difference == null)
            {
                if (isOutputOnSuccess())
                {
                    out.println(message + ": OK");
                }
                return true;
            }
            else
            {
                if (isOutputOnFailure())
                {
                    out.println(message + ": (" + difference.lineNumber_ + ") Content is Different");
                    out.println("\t- " + q(difference.actualLine_));
                    out.println("\t+ " + q(difference.beautifiedLine_));
                }
                return false;
            }
        }
    }

    static String q(String line)
    {
        return line != null ? "[" + line + "]" : "EOF";
    }

    boolean doBeautifyInPlace(Path path, String message, String expected, String actual) throws IOException
    {
        PrintStream out = getOut();
        try (
            BufferedReader ereader = new BufferedReader(new StringReader(expected));
            BufferedReader areader = new BufferedReader(new StringReader(actual));
        )
        {
            List<String> lines = new ArrayList<>();
            XMLBeautifyUtil.BeautyComparisonResult result = XMLBeautifyUtil.compareBeautifiedContent(isPedantic(), ereader::readLine, areader::readLine, lines::add);
            if (!result.sameAsActual_)
            {
                XMLBeautifyUtil.writeLinesToFile(path, lines);
                if (isOutputOnSuccess())
                {
                    out.println(message + ": beautified");
                }
            }
            else
            {
                if (isOutputOnSuccess())
                {
                    out.println(message + ": OK");
                }
            }
        }
        return true;
    }

    static BufferedReader newBufferedReader(String arg) throws IOException
    {
        InputStream is = System.in;
        if (arg.compareTo("--stdin") != 0)
        {
            is = Files.newInputStream(new File(arg).toPath());
        }
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }

    static String toString(Throwable e)
    {
        String message = e.toString();
        int length = message.length();
        return message.substring(0, Math.min(norm(message.indexOf('\n'), length), norm(message.indexOf('\r'), length)));
    }

    static int norm(int i, int l)
    {
        return i >= 0 ? i : l;
    }

    interface IMode
    {
        boolean work(Path path, String message, String expected, String actual) throws IOException;
    }

    static class BadCharacterException extends Exception
    {
        private static final long serialVersionUID = -5096966554704190501L;
        private final int lineNumber_;
        private final List<Integer> columnPositions_;
        private final String line_;

        public BadCharacterException(String message, int lineNumber, List<Integer> columnPositions, String line, Throwable e)
        {
            super(message, e);
            lineNumber_ = lineNumber;
            columnPositions_ = Collections.unmodifiableList(columnPositions);
            line_ = line;
        }

        public int getLineNumber()
        {
            return lineNumber_;
        }

        public List<Integer> getColumnPositions()
        {
            return columnPositions_;
        }

        public String getLine()
        {
            return line_;
        }

        @Override
        public String toString()
        {
            return getLocalizedMessage() + " lineNumber=" + getLineNumber() + " columnPositions=" + getColumnPositions() + " line=[" + getLine() + "]";
        }
    }
}
