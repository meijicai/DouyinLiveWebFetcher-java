package com.douyin.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignatureTest {
    @Test
    void acSignatureMatchesPythonImplementation() {
        String signature = AcSignature.create(
                "www.douyin.com",
                "0123407cc00a9e438deb4",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0",
                1721106114);

        assertEquals("_02B4Z6wo00f01w3S5-QAAIDDhxVVhIEBrOMN8uNAAKXr27", signature);
    }

    @Test
    void webcastSignatureMatchesJavaScriptVector() {
        String signature = WebcastSignature.signWithValues(
                "704f436b2558b0d7a1c7e758527dd8f1", 1, true, 63, 63);
        assertEquals("6pt0VC0aRZDP7n07", signature);
    }

    @Test
    void webcastSignatureUsesCustomPlusAlphabet() {
        String signature = WebcastSignature.signWithValues(
                "704f436b2558b0d7a1c7e758527dd8f1", 0, false, 0, 6);
        assertEquals("fD+evFuzasjOC8Uu", signature);
    }

    @Test
    void sm3MatchesStandardVector() {
        assertArrayEquals(
                java.util.HexFormat.of().parseHex(
                        "66c7f0f462eeedd9d1f2d46bdc10e4e2"
                                + "4167c4875cf2f7a2297da02b8f4ba8e0"),
                Sm3.hash("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    void nativeAbogusHasValidShape() {
        String signature = AbogusSigner.sign(
                "aid=6383&app_name=douyin_web&room_id=123456789",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        assertTrue(signature.length() >= 80);
        assertTrue(signature.endsWith("="));
        assertFalse(signature.contains(" "));
    }
}
