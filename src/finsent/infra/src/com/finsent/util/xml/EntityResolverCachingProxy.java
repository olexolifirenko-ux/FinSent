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
package com.finsent.util.xml;

import com.finsent.util.UtilityFunctions;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Note it only caches {@code InputSource} instances that have byte/character stream.
 * 
 * @author Andrey Aleshnikov
 */
public class EntityResolverCachingProxy implements EntityResolver
{
    private static final CacheEntry NULL = new CacheEntry(null, null);
    private final Map<String, CacheEntry> cacheByPublicId_ = new HashMap<String, CacheEntry>();
    private final Map<String, CacheEntry> cacheBySystemId_ = new HashMap<String, CacheEntry>();
    private final EntityResolver orig_;
    
    public EntityResolverCachingProxy(EntityResolver orig) { orig_ = orig; }
    
    @Override
    public InputSource resolveEntity(String publicId, String systemId) throws SAXException,
            IOException
    {
        Map<String, CacheEntry> cache;
        String key;
        if (null != publicId)
        {
            cache = cacheByPublicId_;
            key = publicId;
        }
        else if (null != systemId)
        {
            cache = cacheBySystemId_;
            key = systemId;
        }
        else // do not cache as something is wrong and NPE will be thrown anyway
            return orig_.resolveEntity(publicId, systemId);
        
        CacheEntry ce = cache.get(key);
        if (null == ce) // resolve, cache
        {
            InputSource is = orig_.resolveEntity(publicId, systemId);
            if (null == is)
                ce = NULL;
            else
            {
                Reader r = is.getCharacterStream();
                if (null != r)
                    ce = new CacheEntry(null, UtilityFunctions.stringFromReader(r));
                else
                {
                    InputStream istream = is.getByteStream();
                    if (null != istream)
                        ce = new CacheEntry(UtilityFunctions.bytesFromStream(is.getByteStream()), null);
                    else  // do not cache as something is wrong and NPE will be thrown anyway
                        return is;
                }
            }
            cache.put(key, ce);
        }
        
        if (NULL == ce)
            return null;
        InputSource is = null != ce.str_
                ? new InputSource(new StringReader(ce.str_))
                : new InputSource(new ByteArrayInputStream(ce.bytes_));
        is.setPublicId(publicId);
        is.setSystemId(systemId);
        return is;        
    }
    
// Inner classes
    
    private static class CacheEntry
    {
        final byte[] bytes_;
        final String str_;
        
        CacheEntry(byte[] bytes, String str) { bytes_ = bytes; str_ = str; }
    }
}