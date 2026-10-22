package com.douyin.live;

import java.nio.charset.StandardCharsets;

/**
 * Native a_bogus signer based on the SM3 + RC4 encoding used by the web client.
 */
public final class AbogusSigner {
    private static final String ALPHABET =
            "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe";
    private static final String UA_ALPHABET =
            "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe";
    private static final String WINDOW_ENV =
            "1920|1080|1920|1040|0|30|0|0|1872|92|1920|1040|1857|92|1|24|Win32";

    private AbogusSigner() {
    }

    public static String sign(String query, String userAgent) {
        int startTime = (int) System.currentTimeMillis();
        int endTime = startTime + 100;
        byte[] urlHash = doubleHash((query + "cus").getBytes(StandardCharsets.UTF_8));
        byte[] customerHash = doubleHash("cus".getBytes(StandardCharsets.UTF_8));
        byte[] encryptedUserAgent = WebcastSignature.rc4(
                userAgent.getBytes(StandardCharsets.UTF_8), new byte[]{0, 1, 14});
        String encodedUserAgent = WebcastSignature.customBase64(encryptedUserAgent, UA_ALPHABET);
        byte[] userAgentHash = Sm3.hash(encodedUserAgent.getBytes(StandardCharsets.ISO_8859_1));

        int[] values = new int[73];
        values[8] = 3;
        values[10] = endTime;
        values[16] = startTime;
        values[18] = 44;

        putBigEndian(values, 20, startTime);
        values[24] = 0;
        values[25] = 0;

        putBigEndian(values, 26, 0);
        values[30] = 0;
        values[31] = 1;
        values[32] = 0;
        values[33] = 0;
        putBigEndian(values, 34, 14);

        values[38] = urlHash[21] & 0xff;
        values[39] = urlHash[22] & 0xff;
        values[40] = customerHash[21] & 0xff;
        values[41] = customerHash[22] & 0xff;
        values[42] = userAgentHash[23] & 0xff;
        values[43] = userAgentHash[24] & 0xff;

        putBigEndian(values, 44, endTime);
        values[48] = 3;
        values[49] = 0;
        values[50] = 0;

        values[51] = 110624;
        putBigEndian(values, 52, values[51]);
        values[56] = 6383;
        values[57] = values[56] & 0xff;
        values[58] = (values[56] >>> 8) & 0xff;
        values[59] = (values[56] >>> 16) & 0xff;
        values[60] = (values[56] >>> 24) & 0xff;

        byte[] environment = WINDOW_ENV.getBytes(StandardCharsets.UTF_8);
        values[64] = environment.length;
        values[65] = environment.length & 0xff;
        values[66] = (environment.length >>> 8) & 0xff;
        values[69] = 0;
        values[70] = 0;
        values[71] = 0;
        values[72] = checksum(values);

        byte[] payload = {
                (byte) values[18], (byte) values[20], (byte) values[52],
                (byte) values[26], (byte) values[30], (byte) values[34],
                (byte) values[58], (byte) values[38], (byte) values[40],
                (byte) values[53], (byte) values[42], (byte) values[21],
                (byte) values[27], (byte) values[54], (byte) values[55],
                (byte) values[31], (byte) values[35], (byte) values[57],
                (byte) values[39], (byte) values[41], (byte) values[43],
                (byte) values[22], (byte) values[28], (byte) values[32],
                (byte) values[60], (byte) values[36], (byte) values[23],
                (byte) values[29], (byte) values[33], (byte) values[37],
                (byte) values[44], (byte) values[45], (byte) values[59],
                (byte) values[46], (byte) values[47], (byte) values[48],
                (byte) values[49], (byte) values[50], (byte) values[24],
                (byte) values[25], (byte) values[65], (byte) values[66],
                (byte) values[70], (byte) values[71]
        };
        byte[] withEnvironment = new byte[payload.length + environment.length + 1];
        System.arraycopy(payload, 0, withEnvironment, 0, payload.length);
        System.arraycopy(environment, 0, withEnvironment, payload.length, environment.length);
        withEnvironment[withEnvironment.length - 1] = (byte) values[72];

        byte[] encrypted = WebcastSignature.rc4(withEnvironment, new byte[]{121});
        byte[] randomPrefix = generateRandomPrefix();
        byte[] complete = new byte[randomPrefix.length + encrypted.length];
        System.arraycopy(randomPrefix, 0, complete, 0, randomPrefix.length);
        System.arraycopy(encrypted, 0, complete, randomPrefix.length, encrypted.length);
        return WebcastSignature.customBase64(complete, ALPHABET) + "=";
    }

    private static int checksum(int[] values) {
        int result = values[18] ^ values[20] ^ values[26] ^ values[30]
                ^ values[38] ^ values[40] ^ values[42] ^ values[21]
                ^ values[27] ^ values[31] ^ values[35] ^ values[39]
                ^ values[41] ^ values[43] ^ values[22] ^ values[28]
                ^ values[32] ^ values[36] ^ values[23] ^ values[29]
                ^ values[33] ^ values[37] ^ values[44] ^ values[45]
                ^ values[46] ^ values[47] ^ values[48] ^ values[49]
                ^ values[50] ^ values[24] ^ values[25] ^ values[52]
                ^ values[53] ^ values[54] ^ values[55] ^ values[57]
                ^ values[58] ^ values[59] ^ values[60] ^ values[65]
                ^ values[66] ^ values[70] ^ values[71];
        return result & 0xff;
    }

    private static void putBigEndian(int[] target, int offset, int value) {
        target[offset] = (value >>> 24) & 0xff;
        target[offset + 1] = (value >>> 16) & 0xff;
        target[offset + 2] = (value >>> 8) & 0xff;
        target[offset + 3] = value & 0xff;
    }

    private static byte[] doubleHash(byte[] input) {
        return Sm3.hash(Sm3.hash(input));
    }

    private static byte[] generateRandomPrefix() {
        byte[] first = obfuscatedRandom((int) (0.123456789 * 10000), 3, 45);
        byte[] second = obfuscatedRandom((int) (0.987654321 * 10000), 1, 0);
        byte[] third = obfuscatedRandom((int) (0.555555555 * 10000), 1, 5);
        byte[] result = new byte[12];
        System.arraycopy(first, 0, result, 0, 4);
        System.arraycopy(second, 0, result, 4, 4);
        System.arraycopy(third, 0, result, 8, 4);
        return result;
    }

    private static byte[] obfuscatedRandom(int random, int firstOption, int secondOption) {
        int first = random & 0xff;
        int second = (random >>> 8) & 0xff;
        return new byte[]{
                (byte) ((first & 170) | (firstOption & 85)),
                (byte) ((first & 85) | (firstOption & 170)),
                (byte) ((second & 170) | (secondOption & 85)),
                (byte) ((second & 85) | (secondOption & 170))
        };
    }
}
