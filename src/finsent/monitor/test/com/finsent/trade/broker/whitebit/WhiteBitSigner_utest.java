package com.finsent.trade.broker.whitebit;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.junit.Test;

/**
 * Pins the WhiteBIT V4 request signing to WhiteBIT's documented format and a HMAC-SHA512 known-answer
 * vector, so a future change can't silently break the byte-exact body / payload / signature the
 * exchange requires. The body and payload match WhiteBIT's own documented example; the signature is an
 * independently computed {@code openssl dgst -sha512 -hmac} value for that payload and a fixed secret.
 */
public class WhiteBitSigner_utest
{
    private static final String PATH = "/api/v4/trade-account/balance";
    private static final long NONCE = 1594297865000L;
    private static final String SECRET = "test_secret_key";
    private static final String BODY = "{\"request\":\"/api/v4/trade-account/balance\",\"nonce\":1594297865000}";
    // WhiteBIT's documented X-TXC-PAYLOAD for this exact body.
    private static final String PAYLOAD =
            "eyJyZXF1ZXN0IjoiL2FwaS92NC90cmFkZS1hY2NvdW50L2JhbGFuY2UiLCJub25jZSI6MTU5NDI5Nzg2NTAwMH0=";
    // Independently computed: printf '%s' <PAYLOAD> | openssl dgst -sha512 -hmac "test_secret_key".
    private static final String SIGNATURE =
            "80c6211ae03493b6087dcbfe2e423d1626565f394ee2f6544e081212755b0074cccdb05caf09f5f0c1563706260348282150b8cea51d551f46c8298d44449fd4";

    @Test
    public void buildsTheCanonicalBodyWhiteBitDocuments()
    {
        assertEquals(BODY, WhiteBitClient.requestBody(PATH, NONCE, Map.of()));
    }

    @Test
    public void paramsAppendAfterNonceInInsertionOrder()
    {
        assertEquals("{\"request\":\"/api/v4/trade-account/balance\",\"nonce\":1594297865000,\"ticker\":\"BTC\"}",
                WhiteBitClient.requestBody(PATH, NONCE, Map.of("ticker", "BTC")));
    }

    @Test
    public void payloadIsBase64OfTheBody()
    {
        assertEquals(PAYLOAD, WhiteBitSigner.sign(BODY, SECRET).payload());
    }

    @Test
    public void signatureMatchesTheHmacSha512KnownAnswer()
    {
        assertEquals(SIGNATURE, WhiteBitSigner.sign(BODY, SECRET).signature());
    }
}
