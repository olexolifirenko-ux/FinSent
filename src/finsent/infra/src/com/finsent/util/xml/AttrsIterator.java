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

import com.finsent.util.ISizedIterator;
import com.finsent.util.xml.XMLData.AttrIndexUserData;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * @see com.finsent.util.xml.XMLData#ATTR_INDEX
 * @author Andrey Aleshnikov
 */
public class AttrsIterator implements ISizedIterator<Attr>
{
    private final int size_;
    private final PriorityQueue<Attr> attrsWithIndexInfo_;
    private final PriorityQueue<Attr> attrsWithoutIndexInfo_;
    
    
    public AttrsIterator(Element element)
    {
        NamedNodeMap attributes = element.getAttributes();
        size_ = attributes.getLength();
        if (0 == size_)
        {
            attrsWithIndexInfo_ = null;
            attrsWithoutIndexInfo_ = null;
        }
        else
        {
            attrsWithIndexInfo_ = new PriorityQueue<>(size_, new AttrsByIndexComparator());
            attrsWithoutIndexInfo_ = new PriorityQueue<>(size_, new AttrsByNameComparator());
            for (int i = 0; i < size_; i++)
            {
                Attr attr = (Attr) attributes.item(i);
                Integer loadOrder = AttrIndexUserData.Instance_.get(attr);
                if (null != loadOrder)
                    attrsWithIndexInfo_.add(attr);
                else
                    attrsWithoutIndexInfo_.add(attr);
            }            
         }
    }
    
    @Override
    public boolean hasNext()
    {
        return 0 != size_ && (!attrsWithIndexInfo_.isEmpty() || !attrsWithoutIndexInfo_.isEmpty());
    }

    @Override
    public Attr next()
    {
        if (0 == size_) throw new NoSuchElementException("empty");
        return !attrsWithIndexInfo_.isEmpty() ? attrsWithIndexInfo_.poll() : attrsWithoutIndexInfo_.poll();
    }

    @Override
    public void remove() { throw new UnsupportedOperationException(); }

    @Override
    public int size() { return size_; }

// Inner classer
    
    /**
     * Sort attributes by {@link XMLData#ATTR_INDEX}
     * @author Andrey Aleshnikov
     */
    private static class AttrsByIndexComparator implements Comparator<Attr>
    {
        @Override
        public int compare(Attr a1, Attr a2)
        {         
            return AttrIndexUserData.Instance_.get(a1).compareTo(AttrIndexUserData.Instance_.get(a2));
        }
    }

    
    /**
     * @author Andrey Aleshnikov
     */
    static class AttrsByNameComparator implements Comparator<Attr>
    {
        @Override
        public int compare(Attr a1, Attr a2)
        {
            String namespace1 = a1.getNamespaceURI();
            String namespace2 = a2.getNamespaceURI();
            // Attrs w/o namespace should go first
            if (namespace1 == null && namespace2 != null) return -1;
            if (namespace1 != null && namespace2 == null) return 1;
            if (namespace1 != namespace2) return namespace1.compareTo(namespace2);
            
            // then compare by local name
            String localName1 = a1.getLocalName();
            String localName2 = a2.getLocalName();
            if (localName1 == null && localName2 != null) return -1;
            if (localName1 != null && localName2 == null) return 1;
            if (localName1 != localName2) return localName1.compareTo(localName2);
            
            // then compare by qualified name
            String qname1 = a1.getName();
            String qname2 = a2.getName();
            if (qname1 == null && qname2 != null) return -1;
            if (qname1 != null && qname2 == null) return 1;
            if (qname1 != qname2) return qname1.compareTo(qname2);
            
            return 0;
        }
    }
}
