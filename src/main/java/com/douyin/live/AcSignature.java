package com.douyin.live;

import java.time.Instant;

/**
 * Java translation of the webcast __ac_signature algorithm.
 */
public final class AcSignature {
    private static final String SIGN_HEAD = "_02B4Z6wo00f01";

    private AcSignature() {
    }

    public static String create(String site, String nonce, String userAgent) {
        return create(site, nonce, userAgent, Instant.now().getEpochSecond());
    }

    public static String create(String site, String nonce, String userAgent, long timestamp) {
        long hashTimestamp = hash1(Long.toString(timestamp), 0);
        long a = hash1(site, hashTimestamp) % 65521;

        String timeBits = Long.toBinaryString(timestamp ^ (a * 65521));
        if (timeBits.length() < 32) {
            timeBits = "0".repeat(32 - timeBits.length()) + timeBits;
        } else if (timeBits.length() > 32) {
            timeBits = timeBits.substring(timeBits.length() - 32);
        }
        long b = Long.parseUnsignedLong("10000000110000" + timeBits, 2);

        long c = hash1(Long.toUnsignedString(b), 0);
        String d = encodeInt(b >>> 2);
        long e = (b >>> 32) & 0xffffffffL;
        String f = encodeInt((b << 28) | (e >>> 4));
        long g = 582085784L ^ b;
        String h = encodeInt((e << 26) | (g >>> 6));
        String i = oneChar(g & 63);

        long j = ((hash1(userAgent, c) % 65521) << 16)
                | (hash1(nonce, c) % 65521);
        String k = encodeInt(j >>> 2);
        String l = encodeInt((j << 28) | ((524576L ^ b) >>> 4));
        String m = encodeInt(a);

        String n = SIGN_HEAD + d + f + h + i + k + l + m;
        String hex = Long.toHexString(hash3(n, 0));
        String o = hex.substring(Math.max(0, hex.length() - 2));
        if (o.length() < 2) {
            o = "0".repeat(2 - o.length()) + o;
        }
        return n + o;
    }

    private static long hash1(String value, long initial) {
        long hash = initial;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            hash = ((hash ^ codePoint) * 65599L) & 0xffffffffL;
            offset += Character.charCount(codePoint);
        }
        return hash;
    }

    private static long hash3(String value, long initial) {
        long hash = initial;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            hash = (hash * 65599L + codePoint) & 0xffffffffL;
            offset += Character.charCount(codePoint);
        }
        return hash;
    }

    private static String encodeInt(long value) {
        StringBuilder result = new StringBuilder(4);
        for (int shift = 24; shift >= 0; shift -= 6) {
            result.append(oneChar((value >>> shift) & 63));
        }
        return result.toString();
    }

    private static String oneChar(long value) {
        if (value < 26) {
            return String.valueOf((char) (value + 65));
        }
        if (value < 52) {
            return String.valueOf((char) (value + 71));
        }
        if (value < 62) {
            return String.valueOf((char) (value - 4));
        }
        return String.valueOf((char) (value - 17));
    }
}
