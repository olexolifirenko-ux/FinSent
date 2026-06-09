package com.finsent.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpClient;

import org.junit.Test;

import com.finsent.core.Http.Channel;

/**
 * Verifies the urgent-lane isolation: {@link Http.Channel#URGENT} resolves to a different
 * {@link HttpClient} (hence a different NIO selector) than {@link Http.Channel#SHARED}, so the urgent
 * poller's connects are not starved by the regular collection's burst on the shared selector.
 */
public class Http_utest
{
    @Test
    public void urgentChannelUsesAnIsolatedClient()
    {
        HttpClient shared = Http.clientFor(Channel.SHARED);
        HttpClient urgent = Http.clientFor(Channel.URGENT);

        assertNotNull(shared);
        assertNotNull(urgent);
        assertNotSame("urgent lane must not share the regular selector", shared, urgent);
        assertSame("the shared client is stable across lookups", shared, Http.clientFor(Channel.SHARED));
        assertSame("the urgent client is stable across lookups", urgent, Http.clientFor(Channel.URGENT));
    }

    @Test
    public void responseNotModifiedReflectsStatus()
    {
        assertTrue("304 is not-modified", new Http.Response(304, null, "", "").notModified());
        assertFalse("200 is a normal modified response", new Http.Response(200, "body", "\"e\"", "").notModified());
    }
}
