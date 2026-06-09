package com.finsent.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * HTTP client with transient-error retry/backoff. Ports the {@code requests.Session}
 * configured in Python {@code shared._build_http_session}: up to {@value #MAX_RETRIES}
 * retries with exponential backoff on connection errors and on the retryable status
 * codes {@code 429/500/502/503/504}, for GET and POST.
 *
 * <p>A non-2xx response that survives all retries is surfaced as an {@link IOException}
 * (the Java equivalent of the call sites' {@code response.raise_for_status()}).
 */
public final class Http
{
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_BASE_MILLIS = 1000L;
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 500, 502, 503, 504);

    private static final HttpClient CLIENT = newClient();
    // The latency-critical urgent lane runs on its own client -- and thus its own NIO selector -- so
    // its small feed burst is not starved by the regular collection's concurrent feed/market-data
    // connects on the shared selector (the cold-start connect storm RssSource also caps against).
    private static final HttpClient URGENT_CLIENT = newClient();

    /** Selects which underlying client (and NIO selector) a request runs on. */
    public enum Channel
    {
        SHARED,
        URGENT
    }

    /**
     * A conditional-GET result: the response {@code status}, the {@code body} (null on a 304 Not
     * Modified), and the {@code etag}/{@code lastModified} validators the server returned (empty when
     * absent) for the caller to cache and replay as {@code If-None-Match}/{@code If-Modified-Since}.
     */
    public record Response(int status, String body, String etag, String lastModified)
    {
        /** True when the server answered 304 Not Modified -- the cached content is still current. */
        public boolean notModified()
        {
            return status == 304;
        }
    }

    private Http()
    {
    }

    private static HttpClient newClient()
    {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static HttpClient clientFor(Channel channel)
    {
        return channel == Channel.URGENT ? URGENT_CLIENT : CLIENT;
    }

    /** GET the body of a URL with optional query params and headers (default {@value #MAX_RETRIES} retries). */
    public static String get(String url, Map<String, String> params,
                             Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException
    {
        return get(Channel.SHARED, url, params, headers, timeout, MAX_RETRIES);
    }

    /**
     * GET with an explicit retry budget. The latency-sensitive urgent poll passes a small (or zero)
     * {@code maxRetries} so a flaky feed fails fast instead of stalling the lane on backoff sleeps;
     * the next poll retries anyway.
     */
    public static String get(String url, Map<String, String> params,
                             Map<String, String> headers, Duration timeout, int maxRetries)
            throws IOException, InterruptedException
    {
        return get(Channel.SHARED, url, params, headers, timeout, maxRetries);
    }

    /**
     * GET on an explicit {@link Channel}. The urgent lane passes {@link Channel#URGENT} to run on a
     * dedicated client (selector), isolated from the shared selector's regular-collection load.
     */
    public static String get(Channel channel, String url, Map<String, String> params,
                             Map<String, String> headers, Duration timeout, int maxRetries)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(buildUri(url, params))
                .timeout(timeout)
                .GET();
        applyHeaders(builder, headers);
        return sendWithRetry(clientFor(channel), builder.build(), maxRetries);
    }

    /**
     * Conditional GET on an explicit {@link Channel}: sends the caller's {@code headers} (which may
     * include {@code If-None-Match}/{@code If-Modified-Since}) and returns the status, body and the
     * {@code ETag}/{@code Last-Modified} validators. A {@code 304 Not Modified} comes back as a
     * {@link Response} with a null body (not an error); other non-2xx still throw.
     */
    public static Response getConditional(Channel channel, String url,
                                          Map<String, String> headers, Duration timeout, int maxRetries)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .GET();
        applyHeaders(builder, headers);
        HttpResponse<String> response = sendRetrying(clientFor(channel), builder.build(), maxRetries);
        int status = response.statusCode();
        if (status != 304 && (status < 200 || status >= 300))
        {
            throw httpError(response);
        }
        String body = status == 304 ? null : response.body();
        return new Response(status, body, header(response, "ETag"), header(response, "Last-Modified"));
    }

    /** POST a JSON body and return the response body. */
    public static String postJson(String url, String jsonBody,
                                  Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        applyHeaders(builder, headers);
        return sendWithRetry(CLIENT, builder.build(), MAX_RETRIES);
    }

    private static String sendWithRetry(HttpClient client, HttpRequest request, int maxRetries)
            throws IOException, InterruptedException
    {
        return bodyOf(sendRetrying(client, request, maxRetries));
    }

    /** Send with retry/backoff and return the last response, or throw if every attempt errored at the IO level. */
    private static HttpResponse<String> sendRetrying(HttpClient client, HttpRequest request, int maxRetries)
            throws IOException, InterruptedException
    {
        IOException lastIoError = null;
        HttpResponse<String> lastResponse = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++)
        {
            backoff(attempt);
            try
            {
                lastResponse = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (!RETRYABLE_STATUSES.contains(lastResponse.statusCode()))
                {
                    break;
                }
                lastIoError = null;
            }
            catch (IOException transientError)
            {
                lastIoError = transientError;
                lastResponse = null;
            }
        }
        if (lastResponse == null)
        {
            throw new IOException("Request to " + request.uri() + " failed after retries", lastIoError);
        }
        return lastResponse;
    }

    /** The 2xx body, or an {@link IOException} for any non-2xx response (raise_for_status equivalent). */
    private static String bodyOf(HttpResponse<String> response) throws IOException
    {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw httpError(response);
        }
        return response.body();
    }

    private static IOException httpError(HttpResponse<String> response)
    {
        String snippet = response.body() == null ? "" : response.body();
        if (snippet.length() > 200)
        {
            snippet = snippet.substring(0, 200);
        }
        return new IOException("HTTP " + response.statusCode() + " from " + response.uri() + ": " + snippet);
    }

    private static String header(HttpResponse<String> response, String name)
    {
        return response.headers().firstValue(name).orElse("");
    }

    private static void backoff(int attempt) throws InterruptedException
    {
        if (attempt > 0)
        {
            long millis = (long) (BACKOFF_BASE_MILLIS * Math.pow(2, attempt - 1));
            Thread.sleep(millis);
        }
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, String> headers)
    {
        for (Map.Entry<String, String> header : nullToEmpty(headers).entrySet())
        {
            builder.header(header.getKey(), header.getValue());
        }
    }

    private static URI buildUri(String url, Map<String, String> params)
    {
        Map<String, String> query = nullToEmpty(params);
        String full;
        if (query.isEmpty())
        {
            full = url;
        }
        else
        {
            full = url + "?" + encodeQuery(query);
        }
        return URI.create(full);
    }

    private static String encodeQuery(Map<String, String> params)
    {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> param : params.entrySet())
        {
            if (query.length() > 0)
            {
                query.append('&');
            }
            query.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
                 .append('=')
                 .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    private static Map<String, String> nullToEmpty(Map<String, String> map)
    {
        return map != null ? map : Collections.emptyMap();
    }
}
