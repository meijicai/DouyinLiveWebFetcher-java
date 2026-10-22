package com.douyin.live;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Native generator for the 16-character webcast WebSocket signature.
 */
public final class WebcastSignature {
    private static final byte USER_BEHAVIOR_CODE = 0x0e;
    private static final byte ENVIRONMENT_CODE = 0x01;
    private static final byte MODE_FLAG = 1 << 6;
    private static final String ALPHABET =
            "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe";
    private static final byte[] EMPTY_BODY_DIGEST = doubleMd5(new byte[0]);

    private final SecureRandom random = new SecureRandom();
    private int counter;

    public synchronized String sign(String xMsStub) {
        int flagRandom = random.nextInt(256);
        int currentCounter = (counter + 1) & 0x3f;
        counter = currentCounter;
        int payloadRandom = nextRandomByteExcluding255();
        int keyRandom = nextRandomByteExcluding255();
        return signWithValues(
                xMsStub, currentCounter, (flagRandom & 1) == 1, payloadRandom, keyRandom);
    }

    static String signWithValues(
            String xMsStub,
            int counter,
            boolean randomFlag,
            int payloadRandom,
            int keyRandom) {
        byte[] stub;
        try {
            stub = HexFormat.of().parseHex(xMsStub);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("X-MS-STUB must be a 32-character MD5 hex string");
        }
        if (stub.length != 16) {
            throw new IllegalArgumentException("X-MS-STUB must decode to 16 bytes");
        }

        byte[] stubDigest = md5(stub);
        byte[] payload = {
                (byte) (counter & 0x3f),
                0,
                ENVIRONMENT_CODE,
                USER_BEHAVIOR_CODE,
                EMPTY_BODY_DIGEST[14],
                EMPTY_BODY_DIGEST[15],
                stubDigest[14],
                stubDigest[15],
                (byte) payloadRandom,
                0
        };
        for (int i = 0; i < payload.length - 1; i++) {
            payload[payload.length - 1] ^= payload[i];
        }

        byte[] encrypted = rc4(payload, new byte[]{(byte) keyRandom});
        byte[] result = new byte[2 + encrypted.length];
        result[0] = (byte) (MODE_FLAG | (randomFlag ? 1 << 4 : 0));
        result[1] = (byte) keyRandom;
        System.arraycopy(encrypted, 0, result, 2, encrypted.length);
        return customBase64(result, ALPHABET);
    }

    private int nextRandomByteExcluding255() {
        int value;
        do {
            value = random.nextInt(256);
        } while (value == 255);
        return value;
    }

    static String customBase64(byte[] input, String alphabet) {
        StringBuilder result = new StringBuilder((input.length * 4 + 2) / 3);
        for (int offset = 0; offset < input.length; offset += 3) {
            int first = input[offset] & 0xff;
            int second = offset + 1 < input.length ? input[offset + 1] & 0xff : 0;
            int third = offset + 2 < input.length ? input[offset + 2] & 0xff : 0;

            result.append(alphabet.charAt(first >>> 2));
            result.append(alphabet.charAt(((first & 0x03) << 4) | (second >>> 4)));
            if (offset + 1 < input.length) {
                result.append(alphabet.charAt(((second & 0x0f) << 2) | (third >>> 6)));
            }
            if (offset + 2 < input.length) {
                result.append(alphabet.charAt(third & 0x3f));
            }
        }
        return result.toString();
    }

    static byte[] rc4(byte[] input, byte[] key) {
        int[] state = new int[256];
        for (int i = 0; i < state.length; i++) {
            state[i] = i;
        }
        int j = 0;
        for (int i = 0; i < state.length; i++) {
            j = (j + state[i] + (key[i % key.length] & 0xff)) & 0xff;
            int swap = state[i];
            state[i] = state[j];
            state[j] = swap;
        }

        byte[] output = new byte[input.length];
        int i = 0;
        j = 0;
        for (int offset = 0; offset < input.length; offset++) {
            i = (i + 1) & 0xff;
            j = (j + state[i]) & 0xff;
            int swap = state[i];
            state[i] = state[j];
            state[j] = swap;
            int keyByte = state[(state[i] + state[j]) & 0xff];
            output[offset] = (byte) (input[offset] ^ keyByte);
        }
        return output;
    }

    private static byte[] doubleMd5(byte[] data) {
        return md5(md5(data));
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }
}
