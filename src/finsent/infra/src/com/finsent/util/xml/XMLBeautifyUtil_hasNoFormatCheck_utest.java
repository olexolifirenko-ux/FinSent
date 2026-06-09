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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class XMLBeautifyUtil_hasNoFormatCheck_utest
{
    @Parameterized.Parameters(name = "{index}: {0} {1}")
    public static Collection<Object[]> getParameters()
    {
        return Arrays.asList(new Object[][] {
            { false, "<?xml version='1.0'?><Root/>" },
            {  true, "<?xml version='1.0'?>\n<!-- NO FORMAT CHECK-->\n<Root/>" },
            {  true, "<?xml version='1.0'?>\n<!-- NO FORMAT CHECK -->\n<Root/>" },
            {  true, "<?xml version='1.0'?>\n<!--NOFORMATCHECK-->\n<Root/>" },
            {  true, "<?xml version='1.0'?>\n<!--\nNO\nFORMAT\nCHECK\n-->\n<Root/>" },
            {  true, "<?xml version='1.0'?>\n<!--    NO    FORMAT    CHECK    -->\n<Root/>" },
            { false, "<?xml version='1.0'?><!-- no format check--><Root/>" },
            { false, "<?xml version='1.0'?><!-- NO FORMAT CHECKS--><Root/>" },
        });
    }

    @Parameterized.Parameter(value = 0) public boolean expected_;
    @Parameterized.Parameter(value = 1) public String content_;

    @Test
    public void test()
    {
        Assert.assertEquals(expected_, XMLBeautifyUtil.hasNoFormatCheck(content_));
    }
}
