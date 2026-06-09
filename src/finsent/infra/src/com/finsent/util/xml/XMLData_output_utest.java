/*
 * Copyright (c) 2015 InfoReach, Inc. All Rights Reserved.
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
import com.finsent.util.RunningProfile;
import com.finsent.util.test.JUnitRunningProfileControl;
import com.finsent.util.test.TestUtils;
import org.junit.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;

/**
 * @author Andrey Aleshnikov
 */
public class XMLData_output_utest extends Assert
{
    private static File dirRoot_;
    
    private static final PrintStream origStdOut_ = System.out;
    private static final PrintStream origStdErr_ = System.err;
    private static final ByteArrayOutputStream stdOut_ = new ByteArrayOutputStream();
    private static final ByteArrayOutputStream stdErr_ = new ByteArrayOutputStream();
    
    @BeforeClass
    public static void setUp() throws Exception
    {
        // we must to substitute STDERR/STDOUT before calling any INFOREACH
        // code as it may invoke our log facility methods that would
        // init the facility with current (default) STDERR/STDOUT
        System.setOut(new PrintStream(stdOut_));
        System.setErr(new PrintStream(stdErr_));
        
        JUnitRunningProfileControl.initialize(false); 
        System.setProperty("runningProfile", RunningProfile.UAT.toString());
                
        dirRoot_ = new File (TestUtils.findTMSRoot(), "release/backend/Directory");
        DirectorySystem.initializeDefault(dirRoot_.getCanonicalPath());
        
        // Reset the STDERR/STDOUT as DirectorySystem might have logged something
        // into them. We don't care about this output as during invoking XMLData
        // methods from TMS the DirectorySystem is initialized already
        stdOut_.reset();
        stdErr_.reset();
    }
    
    @AfterClass
    public static void tearDownClass() 
    {
        System.setOut(origStdOut_);
        System.setErr(origStdErr_);
    }
    
    @After
    public void tearDown()
    {
        stdOut_.reset();
        stdErr_.reset();
    }

    @Test
    public void valueOfWithException_Quiet() throws Exception
    {
        try
        {
            XMLData.valueOfWithException("<intentionallyBrokenXML", true);
            fail("expected exception");
        }
        catch (XMLBadDataException ex)
        {
            // OK
        }
        assertNoOutput();
    }
    
    @Test
    public void valueOf() throws Exception
    {
        XMLData xml = XMLData.valueOf("<root");
        assertTrue(xml.isDummyRoot());
        assertSomeOutput();
    }
    
    @Test
    public void tmp() throws Exception
    {
    }

// Private methods
    
    private void assertNoOutput()
    {
        String stdOut = stdOut_.toString();
        assertTrue(stdOut, stdOut.isEmpty());
        String stdErr = stdErr_.toString();
        assertTrue(stdErr, stdErr.isEmpty());
    }
    
    private void assertSomeOutput()
    {
        String stdOut = stdOut_.toString();
        String stdErr = stdErr_.toString();
        assertTrue(!stdOut.isEmpty() || !stdErr.isEmpty() );
    } 
}