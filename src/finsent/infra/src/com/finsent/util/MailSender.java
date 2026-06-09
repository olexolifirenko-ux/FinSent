/*
 * Copyright (c) 1997-2018 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 *
 */
package com.finsent.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

/**
 * Sends mail over a configured SMTP server (optional authentication, STARTTLS). Ports the
 * InfoReach {@code com.inforeach.util.MailSender} to Jakarta Mail. Differences from the original:
 * password encryption (CryptUtilities) and {@code SystemTime} are dropped; the {@code EventQueue}
 * async-sending subsystem is replaced by a single daemon-thread {@link ExecutorService}; and the
 * {@code XMLData}-config constructor is dropped (FinSent builds it from {@code com.finsent.core.Config}).
 * Sample:
 * <pre>
 *     MailSender s = new MailSender("FinSent", "smtp.gmail.com", 587, "user", "pass", 0);
 *     s.send(new InternetAddress("from@x"), "subject", "body", InternetAddress.parse("to@y"), false);
 * </pre>
 *
 * @author Alexander Dolgin
 * @author Denis Kramarenko
 */
public class MailSender implements IMailSender
{
    private static final String NAME = "MailSender";
    private static final String DEFAULT_CHARSET = System.getProperty("MailSender.defaultCharset", "US-ASCII");
    private static final long QUEUE_DRAIN_TIMEOUT_SEC = 15;

    private final Session session_;
    private final ExecutorService sendingQueue_; // null => synchronous send
    private final long duplicatesCheckTimeoutInMillis_;
    private final MessageCache messageCache_;

    /**
     * @param name name of sender (for logging); optional, can be null
     * @param smtpLogin,smtpPassword login/password to the SMTP server; optional, can be null
     * @param duplicatesCheckTimeoutInSeconds if &gt; 0, suppress identical mails for this window
     */
    public MailSender(String name, String smtpHost, int smtpPort,
            final String smtpLogin, final String smtpPassword, long duplicatesCheckTimeoutInSeconds)
    {
        this(name, smtpHost, smtpPort, smtpLogin, smtpPassword, duplicatesCheckTimeoutInSeconds, true);
    }

    /**
     * @param useSendingQueue when true, e-mails are sent asynchronously on a daemon thread.
     */
    public MailSender(String name, String smtpHost, int smtpPort,
            final String smtpLogin, final String smtpPassword,
            long duplicatesCheckTimeoutInSeconds, boolean useSendingQueue)
    {
        duplicatesCheckTimeoutInMillis_ = duplicatesCheckTimeoutInSeconds * 1000;
        messageCache_ = duplicatesCheckTimeoutInMillis_ > 0 ? new MessageCache() : null;

        Properties props = new Properties();
        props.setProperty("mail.transport.default", "smtp");
        props.setProperty("mail.smtp.host", smtpHost);
        props.setProperty("mail.smtp.port", Integer.toString(smtpPort));
        props.put("mail.smtp.auth", String.valueOf(smtpLogin != null));
        props.put("mail.smtp.starttls.enable", "true");
        if (Boolean.getBoolean("java.mail.debug"))
        {
            props.setProperty("mail.debug", "true");
        }

        Authenticator authenticator = null;
        if (smtpLogin != null)
        {
            authenticator = new Authenticator()
            {
                @Override
                protected PasswordAuthentication getPasswordAuthentication()
                {
                    return new PasswordAuthentication(smtpLogin, smtpPassword);
                }
            };
        }
        session_ = createSession(props, authenticator);

        if (useSendingQueue)
        {
            sendingQueue_ = Executors.newSingleThreadExecutor(MailSender::newQueueThread);
            registerShutdown();
        }
        else
        {
            sendingQueue_ = null;
        }

        GlobalSystem.debug().writes(NAME, "Mail sender \"" + name + "\" initialized. SMTP host "
                + smtpHost + ", port " + smtpPort);
    }

    private static Thread newQueueThread(Runnable r)
    {
        Thread thread = new Thread(r, "MailSender.queue");
        thread.setDaemon(true);
        return thread;
    }

    /** Flush and stop the async sending queue (registered as a shutdown action). */
    private void registerShutdown()
    {
        if (GlobalSystem.isInitialized())
        {
            GlobalSystem.registerUninitializer(this::shutdownQueue);
        }
        else
        {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownQueue));
        }
    }

    private void shutdownQueue()
    {
        if (sendingQueue_ != null)
        {
            sendingQueue_.shutdown();
            try
            {
                sendingQueue_.awaitTermination(QUEUE_DRAIN_TIMEOUT_SEC, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void send(InternetAddress from, String subject, String body, InternetAddress[] to, Map<String, String> headers)
    {
        try
        {
            if (isHtml(body))
            {
                sendHtmlMessage(from, subject, body, to, headers);
            }
            else
            {
                sendPlainMessage(from, subject, body, to, headers);
            }
        }
        catch (MessagingException ex)
        {
            GlobalSystem.error().writes(NAME, "Cannot send e-mail message", ex);
        }
    }

    @Override
    public void send(InternetAddress from, String subject, String body, InternetAddress[] to, String[] filesToAttach, Map<String, String> headers)
    {
        try
        {
            sendMessageWithAttachments(from, subject, body, to, filesToAttach, headers);
        }
        catch (MessagingException ex)
        {
            GlobalSystem.error().writes(NAME, "Cannot send e-mail message", ex);
        }
    }

    @Override
    public void send(InternetAddress from, String subject, String body, InternetAddress[] to, InternetAddress[] cc, String[] filesToAttach, boolean highPriority)
    {
        try
        {
            sendMessageWithAttachments(from, subject, body, to, cc, null, filesToAttach, newPriorityHeaders(highPriority));
        }
        catch (MessagingException ex)
        {
            GlobalSystem.error().writes(NAME, "Cannot send e-mail message", ex);
        }
    }

    @Override
    public void sendPlainMessage(InternetAddress from, String subject, String body, InternetAddress[] to, Map<String, String> headers) throws MessagingException
    {
        sendPlainMessage(from, subject, body, to, null, null, headers);
    }

    @Override
    public void sendPlainMessage(
        InternetAddress from, String subject, String body,
        InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc, Map<String, String> headers)
        throws MessagingException
    {
        MimeMessage msg = createMessage(from, subject, body, to, cc, bcc, headers, false);
        if (msg != null)
        {
            msg.setText(body);
            sendMessage(msg);
        }
    }

    @Override
    public void sendHtmlMessage(InternetAddress from, String subject, String body, InternetAddress[] to, Map<String, String> headers)
        throws MessagingException
    {
        sendHtmlMessage(from, subject, body, to, null, null, headers);
    }

    @Override
    public void sendHtmlMessage(InternetAddress from, String subject, String body,
            InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc, Map<String, String> headers)
        throws MessagingException
    {
        MimeMessage msg = createMessage(from, subject, body, to, cc, bcc, headers, false);
        if (msg != null)
        {
            msg.setDataHandler(new DataHandler(new HtmlDataSource(body, DEFAULT_CHARSET)));
            sendMessage(msg);
        }
    }

    @Override
    public void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText, InternetAddress[] to, String[] resourcePaths, Map<String, String> headers)
        throws MessagingException
    {
        sendMessageWithAttachments(from, subject, bodyText, to, null, null, resourcePaths, headers);
    }

    @Override
    public void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText, InternetAddress[] to, InternetAddress[] cc,
        InternetAddress[] bcc, String[] resourcePaths, Map<String, String> headers)
        throws MessagingException
    {
        boolean hasAttachments = (resourcePaths != null) && (resourcePaths.length > 0);
        MessageFactory msgFactory = new MessageFactory(from, subject, bodyText, to, cc, bcc, headers, hasAttachments);
        if (!msgFactory.isEmpty())
        {
            if (resourcePaths != null)
            {
                for (String path : resourcePaths)
                {
                    msgFactory.addAttachment(path);
                }
            }
            sendMessage(msgFactory.construct());
        }
    }

    @Override
    public void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText,
        InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc,
        byte[][] attachmentData, String[] attachmentMimeTypes, String[] attachmentNames,
        Map<String, String> headers) throws MessagingException
    {
        boolean hasAttachments = (attachmentData != null) && (attachmentData.length > 0);
        MessageFactory msgFactory = new MessageFactory(from, subject, bodyText, to, cc, bcc, headers, hasAttachments);
        if (!msgFactory.isEmpty())
        {
            if (attachmentData != null)
            {
                for (int i = 0; i < attachmentData.length; i++)
                {
                    msgFactory.addAttachment(attachmentData[i], attachmentMimeTypes[i], attachmentNames[i]);
                }
            }
            sendMessage(msgFactory.construct());
        }
    }

    protected Session createSession(Properties props, Authenticator authenticator)
    {
        return Session.getInstance(props, authenticator);
    }

    protected void sendMessage(MimeMessage msg) throws MessagingException
    {
        if (msg != null)
        {
            if (sendingQueue_ != null)
            {
                GlobalSystem.debug().writes(NAME, "Queueing email message with Subject='" + msg.getSubject() + "'");
                sendingQueue_.submit(() -> sendQuietly(msg));
            }
            else
            {
                sendMessageImpl(msg);
            }
        }
    }

    private static void sendQuietly(Message msg)
    {
        try
        {
            sendMessageImpl(msg);
        }
        catch (MessagingException ex)
        {
            GlobalSystem.error().writes(NAME, "Cannot send e-mail message", ex);
        }
    }

    private static void sendMessageImpl(Message msg) throws MessagingException
    {
        GlobalSystem.debug().writes(NAME, "Sending email message with Subject='" + msg.getSubject() + "'");
        Transport.send(msg);
    }

    static boolean isHtml(String body)
    {
        boolean html = false;
        if (body != null)
        {
            int len = body.length();
            int st = 0;
            while ((st < len) && (body.charAt(st) <= ' '))
            {
                st++;
            }
            final String tag = "<html>";
            if (len - st >= tag.length())
            {
                html = true;
                for (int i = 0; i < tag.length(); i++)
                {
                    if (tag.charAt(i) != Character.toLowerCase(body.charAt(st + i)))
                    {
                        html = false;
                        break;
                    }
                }
            }
        }
        return html;
    }

    /**
     * @return null if the message would be a duplicate (and {@code forceCreation} is false).
     */
    private MimeMessage createMessage(
        InternetAddress from, String subject, String body, InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc, Map<String, String> headers, boolean forceCreation)
        throws MessagingException
    {
        if (duplicatesCheckTimeoutInMillis_ > 0)
        {
            messageCache_.removeOldMessages(duplicatesCheckTimeoutInMillis_);
            MailToAddresses addresses = messageCache_.store(from, subject, body, to, cc, bcc);
            if (!forceCreation)
            {
                if (addresses.isEmpty())
                {
                    return null; // no new recipients for an already-sent message; skip.
                }
                to = addresses.to_.toArray(new InternetAddress[0]);
                cc = addresses.cc_.toArray(new InternetAddress[0]);
                bcc = addresses.bcc_.toArray(new InternetAddress[0]);
            }
        }
        MimeMessage msg = new MimeMessage(session_);
        msg.setFrom(from);
        msg.setSentDate(new Date());
        if (subject != null)
        {
            msg.setSubject(subject);
        }
        if (to != null && to.length > 0)
        {
            msg.setRecipients(Message.RecipientType.TO, to);
        }
        if (cc != null && cc.length > 0)
        {
            msg.setRecipients(Message.RecipientType.CC, cc);
        }
        if (bcc != null && bcc.length > 0)
        {
            msg.setRecipients(Message.RecipientType.BCC, bcc);
        }
        for (Map.Entry<String, String> entry : headers.entrySet())
        {
            msg.setHeader(entry.getKey(), entry.getValue());
        }
        return msg;
    }

    static class MailToAddresses
    {
        List<InternetAddress> to_;
        List<InternetAddress> cc_;
        List<InternetAddress> bcc_;
        long lastSentTime_;

        MailToAddresses()
        {
            this(null, null, null);
        }

        MailToAddresses(InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc)
        {
            to_ = fromArray(to);
            cc_ = fromArray(cc);
            bcc_ = fromArray(bcc);
            lastSentTime_ = System.currentTimeMillis();
        }

        boolean isEmpty()
        {
            return to_.isEmpty() && cc_.isEmpty() && bcc_.isEmpty();
        }

        private List<InternetAddress> fromArray(InternetAddress[] addresses)
        {
            return (addresses == null) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(addresses));
        }
    }

    static class MessageCache
    {
        private static final String SEPARATOR = "<<<>>>";
        private final HashMap<String, MailToAddresses> cache_ = new HashMap<>();

        MailToAddresses store(InternetAddress from, String subject, String body, InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc)
        {
            String key = getKey(subject, body);
            synchronized (cache_)
            {
                MailToAddresses oldTo = cache_.get(key);
                MailToAddresses result;
                if (oldTo == null)
                {
                    result = new MailToAddresses(to, cc, bcc);
                    cache_.put(key, result);
                }
                else
                {
                    result = new MailToAddresses();
                    result.to_ = moveArray(oldTo.to_, to);
                    result.cc_ = moveArray(oldTo.cc_, cc);
                    result.bcc_ = moveArray(oldTo.bcc_, bcc);
                }
                return result;
            }
        }

        void removeOldMessages(long duplicatesCheckTimeoutInMillis)
        {
            long earliest = System.currentTimeMillis() - duplicatesCheckTimeoutInMillis;
            synchronized (cache_)
            {
                for (Iterator<Map.Entry<String, MailToAddresses>> iter = cache_.entrySet().iterator(); iter.hasNext(); )
                {
                    if (iter.next().getValue().lastSentTime_ < earliest)
                    {
                        iter.remove();
                    }
                }
            }
        }

        private String getKey(String subject, String body)
        {
            return new StringBuilder(subject.length() + body.length() + SEPARATOR.length())
                    .append(subject).append(SEPARATOR).append(body).toString();
        }

        private ArrayList<InternetAddress> moveArray(List<InternetAddress> oldTo, InternetAddress[] newTo)
        {
            ArrayList<InternetAddress> result = new ArrayList<>((newTo == null) ? 0 : newTo.length);
            if (newTo != null)
            {
                for (InternetAddress addr : newTo)
                {
                    if (!oldTo.contains(addr))
                    {
                        oldTo.add(addr);
                        result.add(addr);
                    }
                }
            }
            return result;
        }
    }

    /** Inspired by the JavaMail ByteArrayDataSource. */
    private static class ByteArrayDataSource implements DataSource
    {
        private final byte[] data_;
        private final String type_;

        ByteArrayDataSource(byte[] data, String mimeType)
        {
            data_ = data;
            type_ = mimeType;
        }

        @Override
        public String getContentType()
        {
            return type_;
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            if (data_ == null)
            {
                throw new IOException("No data");
            }
            return new ByteArrayInputStream(data_);
        }

        @Override
        public String getName()
        {
            return "ByteArrayDataSource";
        }

        @Override
        public OutputStream getOutputStream() throws IOException
        {
            throw new IOException("Not supported");
        }
    }

    /** Not thread-safe; do not reuse. */
    private class MessageFactory
    {
        private final MimeMessage message_;
        private Multipart multiPart_;

        MessageFactory(InternetAddress from, String subject, String bodyText,
            InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc, Map<String, String> headers, boolean forceCreation) throws MessagingException
        {
            message_ = createMessage(from, subject, bodyText, to, cc, bcc, headers, forceCreation);
            if (message_ != null)
            {
                multiPart_ = new MimeMultipart();
                MimeBodyPart body = new MimeBodyPart();
                if (isHtml(bodyText))
                {
                    body.setDataHandler(new DataHandler(new HtmlDataSource(bodyText, DEFAULT_CHARSET)));
                }
                else
                {
                    body.setText(bodyText);
                }
                multiPart_.addBodyPart(body);
            }
        }

        boolean isEmpty()
        {
            return message_ == null;
        }

        void addAttachment(String filePath) throws MessagingException
        {
            if (message_ != null && filePath != null)
            {
                MimeBodyPart attach = new MimeBodyPart();
                FileDataSource fds = new FileDataSource(filePath);
                attach.setDataHandler(new DataHandler(fds));
                attach.setFileName(fds.getName());
                multiPart_.addBodyPart(attach);
            }
        }

        void addAttachment(byte[] attachmentData, String mimeType, String name) throws MessagingException
        {
            if (message_ != null && attachmentData != null)
            {
                MimeBodyPart attach = new MimeBodyPart();
                attach.setDataHandler(new DataHandler(new ByteArrayDataSource(attachmentData, mimeType)));
                attach.setFileName(name);
                attach.setContentID("<" + name + ">");
                multiPart_.addBodyPart(attach);
            }
        }

        MimeMessage construct() throws MessagingException
        {
            if (message_ != null)
            {
                message_.setContent(multiPart_);
            }
            return message_;
        }
    }

    private static class HtmlDataSource implements DataSource
    {
        private byte[] data_;
        private final String type_;

        HtmlDataSource(String data, String charset)
        {
            try
            {
                data_ = data.getBytes(charset);
            }
            catch (UnsupportedEncodingException uex)
            {
                data_ = data.getBytes();
            }
            type_ = "text/html; charset=" + charset;
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            if (data_ == null)
            {
                throw new IOException("no data");
            }
            return new ByteArrayInputStream(data_);
        }

        @Override
        public OutputStream getOutputStream() throws IOException
        {
            throw new IOException("cannot do this");
        }

        @Override
        public String getContentType()
        {
            return type_;
        }

        @Override
        public String getName()
        {
            return "HtmlDataSource";
        }
    }
}
