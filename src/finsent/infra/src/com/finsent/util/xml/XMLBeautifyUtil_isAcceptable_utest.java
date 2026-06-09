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
public class XMLBeautifyUtil_isAcceptable_utest
{
    @Parameterized.Parameters(name = "{index} {0} {1} {3}")
    public static Collection<Object[]> getParameters()
    {
        return Arrays.asList(new Object[][] {
            {  true,  true, "<Element attribute=\"value\">", "<Element attribute=\"value\">", },
            {  true, false, "<Element attribute=\"value\">", "<Element attribute=\"value\" >", },
            { false, false, "<Element attribute=\"value\">", "<Element attribute=\"value\"  >", },
            { false, false, "<Element attribute=\"value\">", "<Element attribute=\"value\" > ", },
            { false, false, "<Element attribute=\"value\">", "<Element attribute='value'>", },
            { false, false, "<Element attribute=\"value\">", "<Element  attribute=\"value\">", },

            {  true,  true, "<Element attribute=\"value\"/>", "<Element attribute=\"value\"/>", },
            {  true, false, "<Element attribute=\"value\"/>", "<Element attribute=\"value\" />", },
            { false, false, "<Element attribute=\"value\"/>", "<Element attribute=\"value\"  />", },
            { false, false, "<Element attribute=\"value\"/>", "<Element attribute=\"value\" /> ", },
            { false, false, "<Element attribute=\"value\"/>", "<Element attribute='value'/>", },
            { false, false, "<Element attribute=\"value\"/>", "<Element  attribute=\"value\"/>", },

            {  true,  true, "<Element", "<Element", },
            { false, false, "<Element", "<Element ", },

            {  true, false,
                "<Set field=\"TargetListWithRFQ#ENTITY_ID#IsBestQuoteInMarketRange\" value=\"Y\"/>\\",
                "<Set field=\"TargetListWithRFQ#ENTITY_ID#IsBestQuoteInMarketRange\" value=\"Y\" />\\",
            },
            {  true, false,
                "<Field name=\"UnderlyingPx\" removeFIXTag=\"true\">For options, lists the price of the {@link Underlying} security.</Field>",
                "<Field name=\"UnderlyingPx\" removeFIXTag=\"true\" >For options, lists the price of the {@link Underlying} security.</Field>",
            },
            {  true, false,
                "<B><A/></B><C c=\"c\"><D d=\"d\"/></C><E e=\"e\" f=\"f\"><F e=\"e\" f=\"f\"/></E>",
                "<B ><A /></B><C c=\"c\" ><D d=\"d\" /></C><E e=\"e\" f=\"f\" ><F e=\"e\" f=\"f\" /></E>",
            },
        });
    }

    @Parameterized.Parameter(value = 0) public boolean expectedOrdinary_;
    @Parameterized.Parameter(value = 1) public boolean expectedPedantic_;
    @Parameterized.Parameter(value = 2) public String beautified_;
    @Parameterized.Parameter(value = 3) public String actual_;

    @Test
    public void testPedantic()
    {
        Assert.assertEquals(expectedPedantic_, XMLBeautifyUtil.isAcceptable(true, beautified_, actual_));
    }

    @Test
    public void testOrdinary()
    {
        Assert.assertEquals(expectedOrdinary_, XMLBeautifyUtil.isAcceptable(false, beautified_, actual_));
    }
}
