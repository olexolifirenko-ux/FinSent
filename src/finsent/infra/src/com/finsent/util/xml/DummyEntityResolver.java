package com.finsent.util.xml;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;

/**
 * @author Andrey Aleshnikov
 */
public class DummyEntityResolver implements EntityResolver
{
    private static final String DummyData_ =  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    
    @Override
    public InputSource resolveEntity(String publicId, String systemId) throws SAXException,
            IOException
    {
        return new InputSource(new StringReader(DummyData_));
    }
}
