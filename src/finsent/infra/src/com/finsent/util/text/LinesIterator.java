/*
 * Copyright (c) 2014 InfoReach, Inc. All Rights Reserved.
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


package com.finsent.util.text;

import java.util.Iterator;
import java.util.NoSuchElementException;


/**
 * Unlike {@link java.io.BufferedReader#readLine()}, {@link
 * java.util.regex.Pattern.split(CharSequence)} and
 * {@link java.util.StringTokenizer#nextToken()} does not skip empty lines.
 * 
 * @author Andrey Aleshnikov
 *
 */
public class LinesIterator implements Iterator<String>
{
    private final String s_;
    
    private int offset_ = 0;
    
    public LinesIterator(String s) { s_ = s; }

    @Override
    public boolean hasNext()
    {
        return !s_.isEmpty() && s_.length() + 1 != offset_;
    }

    @Override
    public String next()
    {
        if (!hasNext()) throw new NoSuchElementException();
        int oldOffset = offset_;
        for (int i = offset_; i < s_.length(); i++)
        {
            if ('\n' == s_.charAt(i))
            {
                offset_ = i+1;
                return s_.substring(oldOffset, i);
            }
            if ('\r' == s_.charAt(i))
            {
                if (s_.length() == i + 1 || '\n' != s_.charAt(i+1))
                {
                    offset_ = i+1;
                    return s_.substring(oldOffset, i);
                }
                offset_ = i+2;
                return s_.substring(oldOffset, i);
            }
        }
        offset_ = s_.length() + 1;
        return s_.substring(oldOffset);
    }

    @Override
    public void remove()
    { throw new UnsupportedOperationException(); }
}
