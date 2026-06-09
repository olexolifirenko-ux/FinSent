/*
 * Copyright (c) 1999-2000 InfoReach, Inc. All Rights Reserved.
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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * String Pools for XMLData (element names, attribute names and some attribute values)
 * 
 * @author dmytros
 */
public class XMLDataStringPools implements Cloneable
{
    public static final String PROP_ENABLED = "XMLDataStringPools.enabled";
    public static final String DFLT_ENABLED = "true";
    public static final String PROP_PATTERN = "XMLDataStringPools.pattern";
    public static final String DFLT_PATTERN = "[\\w$\\.]*"; // matches class names and simple identifier-like values
    public static final String PROP_INVERSE = "XMLDataStringPools.inverse";
    public static final String DFLT_INVERSE = "false";
    public static final String PROP_MAX_LEN = "XMLDataStringPools.max_len";
    public static final int    DFLT_MAX_LEN = 256;

    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(PROP_ENABLED, DFLT_ENABLED));
    private static final Pattern PATTERN = Pattern.compile(System.getProperty(PROP_PATTERN, DFLT_PATTERN));
    private static final boolean INVERSE = Boolean.parseBoolean(System.getProperty(PROP_INVERSE, DFLT_INVERSE));
    private static final int     MAX_LEN = Integer.getInteger(PROP_MAX_LEN, DFLT_MAX_LEN);

    private static final IStringPool DUMMY = new DummyPool();
    private static final IStringPool INTERNING = new TrimmingPool(new InternPool());
    private static final IStringPool TRIMMING = new TrimmingPool(DUMMY);
    private static XMLDataStringPools Instance_;

    private IStringPool elementNamePool_ = DUMMY;
    private IStringPool attributeNamePool_ = DUMMY;
    private IStringPool attributeValuePool_ = DUMMY;

    public IStringPool getElementNamePool()
    {
        return elementNamePool_;
    }

    public XMLDataStringPools withElementNamePool(IStringPool pool)
    {
        elementNamePool_ = Objects.requireNonNull(pool, "elementNamePool");
        return this;
    }

    public IStringPool getAttributeNamePool()
    {
        return attributeNamePool_;
    }

    public XMLDataStringPools withAttributeNamePool(IStringPool pool)
    {
        attributeNamePool_ = Objects.requireNonNull(pool, "attributeNamePool");
        return this;
    }

    public IStringPool getAttributeValuePool()
    {
        return attributeValuePool_;
    }

    public XMLDataStringPools withAttributeValuePool(IStringPool pool)
    {
        attributeValuePool_ = Objects.requireNonNull(pool, "attributeValuePool");
        return this;
    }

    public XMLDataStringPools copy()
    {
        try
        {
            return (XMLDataStringPools) clone();
        }
        catch (CloneNotSupportedException exc)
        {
            throw (Error) new InternalError().initCause(exc);
        }
    }

    public XMLDataStringPools local()
    {
        XMLDataStringPools result = this;
        if (ENABLED)
        {
            result = new XMLDataStringPools()
                .withElementNamePool(new CachingPool(getElementNamePool()))
                .withAttributeNamePool(new CachingPool(getAttributeNamePool()))
                .withAttributeValuePool(new ValueCachingPool(getAttributeValuePool(), TRIMMING, PATTERN, INVERSE, MAX_LEN))
                ;
        }
        return result;
    }

    public static XMLDataStringPools getDefaultInstance()
    {
        synchronized (XMLDataStringPools.class)
        {
            if (Instance_ == null)
            {
                Instance_ = new XMLDataStringPools();
                if (ENABLED)
                {
                    Instance_
                        .withElementNamePool(INTERNING)
                        .withAttributeNamePool(INTERNING)
                        .withAttributeValuePool(INTERNING)
                        ;
                }
            }
        }
        return Instance_.copy();
    }

    public interface IStringPool
    {
        String get(String str);
    }

    static class DummyPool implements IStringPool
    {
        public String get(String str)
        {
            return str;
        }
    }

    static class DelegatingPool implements IStringPool
    {
        private final IStringPool pool_;

        DelegatingPool(IStringPool pool)
        {
            pool_ = Objects.requireNonNull(pool, "pool");
        }

        public String get(String str)
        {
            return pool_.get(str);
        }
    }

    static class TrimmingPool extends DelegatingPool
    {
        TrimmingPool(IStringPool pool)
        {
            super(pool);
        }

        public String get(String str)
        {
            return super.get(str != null ? new String(str) : null);
        }
    }

    static class InternPool implements IStringPool
    {
        public String get(String str)
        {
            return str != null ? str.intern() : null;
        }
    }

    static class CachingPool extends DelegatingPool
    {
        final Map<String, String> map_ = new HashMap<String, String>();
        Thread bound_;

        CachingPool(IStringPool pool)
        {
            super(pool);
            bound_ = Thread.currentThread();
        }

        public String get(String str)
        {
            assert bound_ == Thread.currentThread() : "bound=" + bound_ + "; current=" + Thread.currentThread(); // because map is not thread safe

            String res = map_.get(str);
            if (res == null)
            {
                res = doGet(str);
                map_.put(res, res);
            }
            return res;
        }

        String doGet(String str)
        {
            return super.get(str);
        }
    }

    static class ValueCachingPool extends CachingPool
    {
        private final IStringPool altr_;
        private final Pattern pattern_;
        private final boolean inverse_;
        private final int     max_len_;

        ValueCachingPool(IStringPool pool, IStringPool altr, Pattern pattern, boolean inverse, int max_len)
        {
            super(pool);
            altr_ = Objects.requireNonNull(altr, "altr");
            pattern_ = Objects.requireNonNull(pattern, "pattern");
            inverse_ = inverse;
            max_len_ = max_len;
        }

        @Override
        String doGet(String s)
        {
            if (s.length() <= max_len_ && (pattern_.matcher(s).matches() ^ inverse_))
            {
                s = super.doGet(s);
            }
            else
            {
                s = altr_.get(s);
            }
            return s;
        }
    }
}
