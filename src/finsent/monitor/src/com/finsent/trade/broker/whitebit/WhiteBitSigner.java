package com.finsent.trade.broker.whitebit;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a WhiteBIT private API V4 request. Pure crypto, isolated from any HTTP so it can be checked
 * against WhiteBIT's documented vectors. Given the exact request-body JSON string the call will POST,
 * it produces the two headers WhiteBIT requires:
 * <ul>
 *   <li>{@code X-TXC-PAYLOAD} = base64 of the body bytes, and</li>
 *   <li>{@code X-TXC-SIGNATURE} = lowercase hex of {@code HMAC_SHA512(payload, key=apiSecret)} &mdash;
 *       i.e. the HMAC is taken over the base64 <i>payload</i> string, not the raw body.</li>
 * </ul>
 * The body that is signed must be the identical byte string sent as the HTTP body (see
 * {@link WhiteBitClient}); the signature otherwise will not match.
 */
public final class WhiteBitSigner
{
    private WhiteBitSigner()
    {
    }

    /** A signed request: the {@code body} to POST plus its {@code payload} and {@code signature} headers. */
    public record Signed(String body, String payload, String signature)
    {
    }

    /** Sign {@code body} with {@code apiSecret}, returning the body and the two header values. */
    public static Signed sign(String body, String apiSecret)
    {
        String payload = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String signature = hmacSha512Hex(payload, apiSecret);
        return new Signed(body, payload, signature);
    }

    private static String hmacSha512Hex(String data, String key)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException | InvalidKeyException unavailable)
        {
            // HmacSHA512 is a standard JDK algorithm; a failure here is a broken runtime, not an input error.
            throw new IllegalStateException("HMAC-SHA512 signing unavailable", unavailable);
        }
    }

    private static String toHex(byte[] bytes)
    {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
        {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
