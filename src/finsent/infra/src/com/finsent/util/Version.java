/*
 * Copyright (c) 1997-2000 InfoReach, Inc. All Rights Reserved.
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

package com.finsent.util;

import java.io.Serializable;
import java.util.Objects;

/**
 *
 * @author Oleg Minukhin
 */
public final class Version implements Serializable
{
    public static final Version NULL = new Version("0.0", "0", "none", "");
    private static final long serialVersionUID = 5490682318874867792L;
    private final String specVersion_;
    private final String datetime_;
    private final String revtype_;
    private final String revision_;

    public Version(String specVersion, String datetime, String revtype, String revision)
    {
        Objects.requireNonNull(specVersion, "specVersion");
        Objects.requireNonNull(datetime, "datetime");
        Objects.requireNonNull(revtype, "revtype");
        Objects.requireNonNull(revision, "revision");
        specVersion_ = specVersion;
        datetime_ = datetime;
        revtype_ = revtype;
        revision_ = revision;
    }

    public boolean isNull()
    {
        return equals(NULL);
    }

    public String getSpecificationVersion()
    {
        return specVersion_;
    }

    public String getDatetime()
    {
        return datetime_;
    }

    public String getRevType()
    {
        return revtype_;
    }

    public String getRevision()
    {
        return revision_;
    }

    public String getImplementationVersion()
    {
        return datetime_;
    }

    public Version withSpecificationVersion(String specVersion)
    {
        return new Version(specVersion, getDatetime(), getRevType(), getRevision());
    }

    @Override
    public int hashCode()
    {
        return revision_.hashCode();
    }

    public boolean equalTo(Version that)
    {
        return revision_.equals(that.revision_)
            && revtype_.equals(that.revtype_)
            && datetime_.equals(that.datetime_)
            && specVersion_.equals(that.specVersion_)
            ;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj instanceof Version)
        {
            return equalTo((Version) obj);
        }
        return false;
    }

    @Override
    public String toString()
    {
        return specVersion_ + "." + datetime_ + ":" + "(" + revtype_ + ")" + revision_;
    }

    public String toShortString()
    {
        return specVersion_ + "." + datetime_;
    }
}
