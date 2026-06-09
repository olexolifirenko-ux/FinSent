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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class XMLBeautifyUtil
{
    private static final Pattern NO_FORMAT_CHECK = Pattern.compile("<!--\\s*NO\\s*FORMAT\\s*CHECK\\s*-->");
    private static final Pattern TRAILING_SPACE = Pattern.compile("(\\s)(?=/?>)");

    public static boolean hasNoFormatCheck(String content)
    {
        return NO_FORMAT_CHECK.matcher(content).find();
    }

    public static boolean isAcceptable(boolean pedantic, String expected, String actual)
    {
        // pedantic mode requires absolute identity
        return
            expected.equals(actual) ||
            !pedantic && isAcceptableNonPedantic(expected, actual);
    }

    private static boolean isAcceptableNonPedantic(String expected, String actual)
    {
        // for non-pedantic mode it's acceptable to have one extra space
        // at the end of attribute list in StartTag or EmptyElementTag.
        // i.e. these variants are both ok:
        //  <Element attribute="value">
        //  <Element attribute="value" >
        //  --------------------------^
        String corrected = TRAILING_SPACE.matcher(actual).replaceAll("");
        return expected.equals(corrected);
    }

    public static void cutOffTrailingEmptyLines(List<String> lines)
    {
        int lastIndex;
        while ((lastIndex = lines.size() - 1) >= 0 && lines.get(lastIndex).isEmpty())
        {
            lines.remove(lastIndex);
        }
    }

    public static void writeLinesToFile(Path path, Iterable<String> lines) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path, XMLData.DEFAULT_ENCODING))
        {
            for (String line : lines)
            {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    public static <E extends Throwable> BeautyComparisonResult compareBeautifiedContent(
        boolean pedantic,
        SupplierWithException<? extends String, ? extends E> beautified,
        SupplierWithException<? extends String, ? extends E> actual,
        Consumer<? super String> result
    ) throws E
    {
        int linenum = 0;
        boolean sameAsBeautified = true;
        while (true)
        {
            String bline = beautified.get();
            String aline = actual.get();
            linenum += 1;
            if (aline == null)
            {
                if (bline == null)
                {
                    return new BeautyComparisonResult(sameAsBeautified, true, null);
                }
                else
                {
                    return drainBeautifiedAndCreateResult(bline, aline, linenum, sameAsBeautified, beautified, result);
                }
            }
            else
            {
                if (bline == null)
                {
                    return drainBeautifiedAndCreateResult(bline, aline, linenum, sameAsBeautified, beautified, result);
                }
                else
                {
                    if (bline.equals(aline))
                    {
                        if (result != null)
                        {
                            result.accept(bline);
                        }
                    }
                    else if (!pedantic && isAcceptableNonPedantic(bline, aline))
                    {
                        sameAsBeautified = false;
                        if (result != null)
                        {
                            result.accept(aline);
                        }
                    }
                    else
                    {
                        return drainBeautifiedAndCreateResult(bline, aline, linenum, sameAsBeautified, beautified, result);
                    }
                }
            }
        }
    }

    private static <E extends Throwable> BeautyComparisonResult drainBeautifiedAndCreateResult(
        String bline, String aline, int linenum, boolean sameAsBeautified,
        SupplierWithException<? extends String, ? extends E> beautified,
        Consumer<? super String> result
    ) throws E
    {
        Difference difference = new Difference(bline, aline, linenum);
        if (result != null && bline != null)
        {
            result.accept(bline);
            drain(beautified, result);
        }
        return new BeautyComparisonResult(sameAsBeautified, false, difference);
    }

    private static <T, E extends Throwable> void drain(SupplierWithException<? extends T, ? extends E> supplier, Consumer<? super T> consumer) throws E
    {
        T item;
        while ((item = supplier.get()) != null)
        {
            consumer.accept(item);
        }
    }

    public static class BeautyComparisonResult
    {
        public final boolean sameAsBeautified_;
        public final boolean sameAsActual_;
        public final Difference difference_;

        public BeautyComparisonResult(boolean sameAsBeautified, boolean sameAsActual, Difference difference)
        {
            sameAsBeautified_ = sameAsBeautified;
            sameAsActual_ = sameAsActual;
            difference_ = difference;
        }
    }

    public static class Difference
    {
        public final String beautifiedLine_;
        public final String actualLine_;
        public final int lineNumber_;

        public Difference(String beautifiedLine, String actualLine, int lineNumber)
        {
            beautifiedLine_ = beautifiedLine;
            actualLine_ = actualLine;
            lineNumber_ = lineNumber;
        }
    }

    public interface SupplierWithException<T, E extends Throwable>
    {
        T get() throws E;
    }
}
