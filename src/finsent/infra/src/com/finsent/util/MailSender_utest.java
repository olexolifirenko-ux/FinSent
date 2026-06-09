package com.finsent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.mail.internet.InternetAddress;

import org.junit.Test;

/**
 * Verifies the pure, offline-testable bits of the ported {@link MailSender}: the {@code <html>}
 * body detection and the {@code MessageCache} duplicate-suppression (store + expiry). The actual
 * SMTP send is not unit-tested (needs a network/server).
 */
public class MailSender_utest
{
    @Test
    public void isHtmlDetectsLeadingHtmlTag()
    {
        assertTrue(MailSender.isHtml("<html><body>hi</body></html>"));
        assertTrue("leading whitespace + case-insensitive", MailSender.isHtml("  \n<HTML>x"));
        assertFalse(MailSender.isHtml("plain text body"));
        assertFalse("a different leading tag is not html", MailSender.isHtml("<p>not html</p>"));
        assertFalse(MailSender.isHtml(null));
        assertFalse(MailSender.isHtml(""));
    }

    @Test
    public void messageCacheSuppressesDuplicateRecipients() throws Exception
    {
        MailSender.MessageCache cache = new MailSender.MessageCache();
        InternetAddress a = new InternetAddress("a@x.com");
        InternetAddress c = new InternetAddress("c@x.com");

        // First send to [a] is new.
        assertFalse(cache.store(null, "subj", "body", new InternetAddress[] { a }, null, null).isEmpty());
        // Identical send to [a] is a duplicate (no new recipients).
        assertTrue(cache.store(null, "subj", "body", new InternetAddress[] { a }, null, null).isEmpty());
        // Same subject/body but a new recipient [c] yields only the new one.
        MailSender.MailToAddresses delta = cache.store(null, "subj", "body", new InternetAddress[] { a, c }, null, null);
        assertFalse(delta.isEmpty());
        assertEquals(1, delta.to_.size());
        assertEquals(c, delta.to_.get(0));
    }

    @Test
    public void messageCacheExpiryClearsHistory() throws Exception
    {
        MailSender.MessageCache cache = new MailSender.MessageCache();
        InternetAddress a = new InternetAddress("a@x.com");

        assertFalse(cache.store(null, "s", "b", new InternetAddress[] { a }, null, null).isEmpty());
        // A negative window puts the cutoff in the future, so every stored entry is evicted.
        cache.removeOldMessages(-1);
        // After eviction the same message is treated as new again (not a duplicate).
        assertFalse(cache.store(null, "s", "b", new InternetAddress[] { a }, null, null).isEmpty());
    }
}
