package com.finsent.analyse.notify;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import com.finsent.util.GlobalSystem;
import com.finsent.util.IMailSender;

/**
 * Sends alert emails through the infra {@link IMailSender} (ports Python {@code send_email}). Builds
 * the from/to {@link InternetAddress}es and delegates to the fire-and-forget {@code send} overload,
 * which queues the message and logs (never throws) on SMTP failure. A null mail sender or empty
 * recipient means email is not configured and {@link #send} is a no-op.
 */
public final class EmailNotifier
{
    private static final String NAME = "EmailNotifier";

    private final IMailSender mailSender_;
    private final String from_;
    private final String to_;

    public EmailNotifier(IMailSender mailSender, String from, String to)
    {
        mailSender_ = mailSender;
        from_ = from;
        to_ = to;
    }

    /** Whether a mail sender and a recipient are configured. */
    public boolean isConfigured()
    {
        return mailSender_ != null && !to_.isEmpty();
    }

    /** Send a plain-text email; logs and swallows an invalid-address error. */
    public void send(String subject, String body, String intervalKey)
    {
        if (isConfigured())
        {
            String tag = intervalKey.isEmpty() ? "" : "[" + intervalKey + "] ";
            try
            {
                InternetAddress from = new InternetAddress(from_);
                InternetAddress[] to = InternetAddress.parse(to_);
                mailSender_.send(from, subject, body, to, false);
                GlobalSystem.info().writes(NAME, tag + "Email notification queued to " + to_ + ".");
            }
            catch (AddressException badAddress)
            {
                GlobalSystem.warning().writes(NAME, tag + "Invalid email address", badAddress);
            }
        }
    }
}
