package com.finsent.util.xml;

import org.junit.Test;

import static org.junit.Assert.*;

public class XMLDataPathElement_utest
{
    @Test
    public void testToXPATHLikeString()
    {
        XMLDataPathElement xpe = new XMLDataPathElement("tag");
        assertEquals("/tag", xpe.toXPATHLikeString());
        
        xpe = new XMLDataPathElement(null);
        assertEquals("/*", xpe.toXPATHLikeString());
        
        xpe = new XMLDataPathElement("tag", "attr", "value");
        assertEquals("/tag[@attr='value']", xpe.toXPATHLikeString());
        
        xpe = new XMLDataPathElement(null, "attr", "value");
        assertEquals("/*[@attr='value']", xpe.toXPATHLikeString());
    }
}
