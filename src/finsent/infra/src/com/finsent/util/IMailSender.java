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

import java.util.Collections;
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;

/**
 * @author Denis Kramarenko
 */
public interface IMailSender
{
    String HEADER_X_PRIORITY = "X-Priority";
    String VALUE_X_PRIORITY_HIGH = "1";

    default Map<String, String> newPriorityHeaders(boolean highPriority)
    {
        return highPriority ? Collections.singletonMap(HEADER_X_PRIORITY, VALUE_X_PRIORITY_HIGH) : Collections.emptyMap();
    }

    /**
     * Send message.
     * If <code>body</code> starts with &lthtml&gt tag, then message is sent as HTML, otherwise as plain.
     * If exception happens, it is logged and consumed.
     *
     * @param from from address in message, <code>null</code> is allowed
     * @param subject subject in message, null is allowed
     * @param body message itself, null isn't allowed
     * @param to array of recipient addresses, cannot be null or empty
     */
    default void send(InternetAddress from, String subject, String body, InternetAddress[] to, boolean highPriority)
    {
        send(from, subject, body, to, newPriorityHeaders(highPriority));
    }

    void send(InternetAddress from, String subject, String body, InternetAddress[] to, Map<String, String> headers);

    /**
     * Sends message with attachments taken from specified places in file system.
     * If <code>body</code> starts with &lthtml&gt tag, then message is sent as HTML, otherwise as plain.
     * If exception happens, it is logged and consumed.
     *
     * @param from from address in message, <code>null</code> is allowed
     * @param subject subject in message, null is allowed
     * @param body message itself, null isn't allowed
     * @param to array of recipient addresses, cannot be null or empty
     */
    default void send(InternetAddress from, String subject, String body, InternetAddress[] to, String[] filesToAttach, boolean highPriority)
    {
        send(from, subject, body, to, filesToAttach, newPriorityHeaders(highPriority));
    }

    void send(InternetAddress from, String subject, String body, InternetAddress[] to, String[] filesToAttach, Map<String, String> headers);

    public void send(InternetAddress from, String subject, String body, InternetAddress[] to, InternetAddress[] cc, String[] filesToAttach, boolean highPriority);

    /**
     * Send message. Note that this method may throw an exception.
     */
    default void sendPlainMessage(InternetAddress from, String subject, String body, InternetAddress[] to, boolean highPriority) throws MessagingException
    {
        sendPlainMessage(from, subject, body, to, newPriorityHeaders(highPriority));
    }

    void sendPlainMessage(InternetAddress from, String subject, String body, InternetAddress[] to, Map<String, String> headers) throws MessagingException;

    /**
     * Send message with CC and BCC. Note that this method may throw an exception.
     */
    default void sendPlainMessage(
        InternetAddress from, String subject, String body,
        InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc, boolean highPriority)
        throws MessagingException
    {
        sendPlainMessage(from, subject, body, to, cc, bcc, newPriorityHeaders(highPriority));
    }

    void sendPlainMessage(
        InternetAddress from, String subject, String body,
        InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc, Map<String, String> headers)
        throws MessagingException;

    /**
     * Send HTML message.
     * @param from from address
     * @param subject subject
     * @param body body in HTML format
     * @param to to addresses
     * @param highPriority highPriority
     * @throws MessagingException
     */
    default void sendHtmlMessage(InternetAddress from, String subject, String body, InternetAddress[] to, boolean highPriority)
        throws MessagingException
    {
        sendHtmlMessage(from, subject, body, to, newPriorityHeaders(highPriority));
    }

    void sendHtmlMessage(InternetAddress from, String subject, String body, InternetAddress[] to, Map<String, String> headers)
        throws MessagingException;

    /**
     * Send HTML message.
     * @param from from address
     * @param subject subject
     * @param body body in HTML format
     * @param to to addresses
     * @param cc CC addresses
     * @param bcc BCC addresses
     * @param highPriority highPriority
     * @throws MessagingException
     */
    default void sendHtmlMessage(
        InternetAddress from,
        String subject,
        String body,
        InternetAddress[] to,
        InternetAddress[] cc,
        InternetAddress[] bcc,
        boolean highPriority)
        throws MessagingException
    {
        sendHtmlMessage(from, subject, body, to, cc, bcc, newPriorityHeaders(highPriority));
    }

    public void sendHtmlMessage(
        InternetAddress from,
        String subject,
        String body,
        InternetAddress[] to,
        InternetAddress[] cc,
        InternetAddress[] bcc,
        Map<String, String> headers)
        throws MessagingException;

    /**
     * Send message with attachments. Note that this method may throw an exception.
     * If <code>body</code> starts with &lthtml&gt tag, then message is sent as HTML, otherwise as plain.
     */
    default void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText, InternetAddress[] to, String[] resourcePaths, boolean highPriority)
        throws MessagingException
    {
        sendMessageWithAttachments(from, subject, bodyText, to, resourcePaths, newPriorityHeaders(highPriority));
    }

    void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText, InternetAddress[] to, String[] resourcePaths, Map<String, String> headers)
        throws MessagingException;

    /**
     * Send message with CC, BCC and attachments. Note that this method may throw an exception.
     * If <code>body</code> starts with &lthtml&gt tag, then message is sent as HTML, otherwise as plain.
     */
    default void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText, InternetAddress[] to, InternetAddress[] cc,
        InternetAddress[] bcc, String[] resourcePaths, boolean highPriority)
        throws MessagingException
    {
        sendMessageWithAttachments(from, subject, bodyText, to, cc, bcc, resourcePaths, newPriorityHeaders(highPriority));
    }

    void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText, InternetAddress[] to, InternetAddress[] cc,
        InternetAddress[] bcc, String[] resourcePaths, Map<String, String> headers)
        throws MessagingException;

    /**
     * Send message with CC, BCC and attachments. Note that this method may throw an exception.
     * If <code>body</code> starts with &lthtml&gt tag, then message is sent as HTML, otherwise as plain.
     */
    default void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText,
        InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc,
        byte[][] attachmentData, String[] attachmentMimeTypes, String[] attachmentNames,
        boolean highPriority) throws MessagingException
    {
        sendMessageWithAttachments(from, subject, bodyText, to, cc, bcc, attachmentData, attachmentMimeTypes, attachmentNames, newPriorityHeaders(highPriority));
    }

    void sendMessageWithAttachments(
        InternetAddress from, String subject, String bodyText,
        InternetAddress[] to, InternetAddress[] cc, InternetAddress[] bcc,
        byte[][] attachmentData, String[] attachmentMimeTypes, String[] attachmentNames,
        Map<String, String> headers) throws MessagingException;
}
