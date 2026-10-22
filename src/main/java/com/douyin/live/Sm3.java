package com.douyin.live;

/**
 * Minimal SM3 implementation used by the native a_bogus signer.
 */
final class Sm3 {
    private Sm3() {
    }

    static byte[] hash(byte[] input) {
        long bitLength = (long) input.length * 8;
        int paddedLength = ((input.length + 9 + 63) / 64) * 64;
        byte[] padded = new byte[paddedLength];
        System.arraycopy(input, 0, padded, 0, input.length);
        padded[input.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            padded[paddedLength - 1 - i] = (byte) (bitLength >>> (i * 8));
        }

        int[] state = {
                0x7380166f,
                0x4914b2b9,
                0x172442d7,
                0xda8a0600,
                0xa96f30bc,
                0x163138aa,
                0xe38dee4d,
                0xb0fb0e4e
        };
        int[] words = new int[68];
        int[] expanded = new int[64];
        for (int offset = 0; offset < padded.length; offset += 64) {
            for (int i = 0; i < 16; i++) {
                int index = offset + i * 4;
                words[i] = ((padded[index] & 0xff) << 24)
                        | ((padded[index + 1] & 0xff) << 16)
                        | ((padded[index + 2] & 0xff) << 8)
                        | (padded[index + 3] & 0xff);
            }
            for (int i = 16; i < 68; i++) {
                int value = words[i - 16] ^ words[i - 9] ^ rotateLeft(words[i - 3], 15);
                value ^= rotateLeft(value, 15) ^ rotateLeft(value, 23);
                words[i] = value ^ rotateLeft(words[i - 13], 7) ^ words[i - 6];
            }
            for (int i = 0; i < 64; i++) {
                expanded[i] = words[i] ^ words[i + 4];
            }

            int a = state[0];
            int b = state[1];
            int c = state[2];
            int d = state[3];
            int e = state[4];
            int f = state[5];
            int g = state[6];
            int h = state[7];

            for (int i = 0; i < 64; i++) {
                int t = i < 16 ? 0x79cc4519 : 0x7a879d8a;
                int ss1 = rotateLeft(rotateLeft(a, 12) + e + rotateLeft(t, i), 7);
                int ss2 = ss1 ^ rotateLeft(a, 12);
                int ff = i < 16 ? a ^ b ^ c : (a & b) | (a & c) | (b & c);
                int gg = i < 16 ? e ^ f ^ g : (e & f) | (~e & g);
                int tt1 = ff + d + ss2 + expanded[i];
                int tt2 = gg + h + ss1 + words[i];

                d = c;
                c = rotateLeft(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = rotateLeft(f, 19);
                f = e;
                e = tt2 ^ rotateLeft(tt2, 9) ^ rotateLeft(tt2, 17);
            }

            state[0] ^= a;
            state[1] ^= b;
            state[2] ^= c;
            state[3] ^= d;
            state[4] ^= e;
            state[5] ^= f;
            state[6] ^= g;
            state[7] ^= h;
        }

        byte[] result = new byte[32];
        for (int i = 0; i < state.length; i++) {
            result[i * 4] = (byte) (state[i] >>> 24);
            result[i * 4 + 1] = (byte) (state[i] >>> 16);
            result[i * 4 + 2] = (byte) (state[i] >>> 8);
            result[i * 4 + 3] = (byte) state[i];
        }
        return result;
    }

    private static int rotateLeft(int value, int distance) {
        return Integer.rotateLeft(value, distance & 31);
    }
}
