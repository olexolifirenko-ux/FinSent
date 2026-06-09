/*
 * Copyright (c) InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 */

package com.finsent.util.test;

import java.io.File;

import org.junit.Assert;

import com.finsent.util.RunnableThrows;

import static com.finsent.util.GlobalSystem.info;

/**
 * Test helpers used by the inline {@code *_utest}/{@code *_test} unit tests.
 *
 * <p>This is a minimal subset of the full InfoReach {@code TestUtils}, carrying
 * only {@link #expectException} and {@link #findTMSRoot} (the members the ported
 * tests use); the appadmin / back-test / time helpers of the original are
 * omitted to keep the dependency surface small.
 */
public class TestUtils
{
    public static File findTMSRoot()
    {
        File suggestedTMSRoot = new File(".").getAbsoluteFile();

        while (null != suggestedTMSRoot)
        {
            if (new File(suggestedTMSRoot.getAbsolutePath() + File.separator + "release" + File.separator + "backend").exists())
            {
                break;
            }
            suggestedTMSRoot = suggestedTMSRoot.getParentFile();
        }

        return suggestedTMSRoot;
    }

    public static void expectException(Class<?> exceptionClass, RunnableThrows runnable)
    {
        expectException(exceptionClass, runnable, true, false);
    }

    public static void expectException(Class<?> exceptionClass, RunnableThrows runnable, boolean printException, boolean printStack)
    {
        try
        {
            runnable.run();
        }
        catch (AssertionError e)
        {
            throw e; // this is the exception for "Assert.fail()"
        }
        catch (Throwable t)
        {
            if (exceptionClass == t.getClass())
            {
                if (printException)
                {
                    if (printStack)
                    {
                        info().write("Got expected exception, good", t);
                    }
                    else
                    {
                        String message = t.getClass().getName() + "/" + t.getMessage();
                        info().write("Got expected exception, good", message);
                    }
                }
                return;
            }
        }
        Assert.fail("Did not get the expected exception " + exceptionClass.getName());
    }

    private TestUtils() {}
}
