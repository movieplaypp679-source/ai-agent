package com.aiagent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public final class EdgeDrm {
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final long WIN_EPOCH = 11644473600L;
    private static final long S_TO_NS = 1000000000L;
    private static final long U32_MASK = 0xFFFFFFFFL;

    private static final int[] SHA256_INITIAL = {
        0x6A09E667, (int)0xBB67AE85L, 0x3C6EF372, (int)0xA54FF53AL,
        0x510E527F, (int)0x9B05688CL, 0x1F83D9AB, 0x5BE0CD19
    };

    private static final int[] SHA256_STATE = {
        (int)0xFD9FBEA2L, (int)0x91243CE7L, (int)0xBE499094L, (int)0xCD2E1E0AL,
        0x6C006F49, 0x04BD35AB, (int)0xFEB0A7E4L, 0x03EFFBD2
    };

    private static final int[] SHA256_SCHEDULE = {
        (int)0xEB24BF9EL, 0x1558691F, (int)0xA0C09EE2L, 0x0CFA32BA,
        (int)0xA622EE8CL, (int)0xD1DD9491L, (int)0xC0DD25A0L, (int)0xF9E70AB6L,
        (int)0xF0C31573L, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        (int)0xD258B060L, 0x72766564
    };

    private static final int[] SHA256_ROUND_CONSTANTS = {
        0x428A2F98, 0x71374491, (int)0xB5C0FBCFL, (int)0xE9B5DBA5L,
        0x3956C25B, 0x59F111F1, (int)0x923F82A4L, (int)0xAB1C5ED5L,
        (int)0xD807AA98L, 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, (int)0x80DEB1FEL, (int)0x9BDC06A7L, (int)0xC19BF174L,
        (int)0xE49B69C1L, (int)0xEFBE4786L, 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        (int)0x983E5152L, (int)0xA831C66DL, (int)0xB00327C8L, (int)0xBF597FC7L,
        (int)0xC6E00BF3L, (int)0xD5A79147L, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, (int)0x81C2C92EL, (int)0x92722C85L,
        (int)0xA2BFE8A1L, (int)0xA81A664BL, (int)0xC24B8B70L, (int)0xC76C51A3L,
        (int)0xD192E819L, (int)0xD6990624L, (int)0xF40E3585L, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, (int)0x84C87814L, (int)0x8CC70208L,
        (int)0x90BEFFFAL, (int)0xA4506CEBL, (int)0xBEF9A3F7L, (int)0xC67178F2L
    };

    private static final int SHA256_ADJUSTMENT = (int)0xC9DC06A7L;
    private static final int SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80;
    private static final int SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0;

    private static final int[] SHA256_BLOCK_CONSTANTS = new int[64];
    static {
        System.arraycopy(SHA256_ROUND_CONSTANTS, 0, SHA256_BLOCK_CONSTANTS, 0, 14);
        SHA256_BLOCK_CONSTANTS[14] = SHA256_ADJUSTMENT;
        System.arraycopy(SHA256_ROUND_CONSTANTS, 15, SHA256_BLOCK_CONSTANTS, 15, 49);
    }

    private static final int[][] CS64_COEFFICIENTS = {
        {(int)0xE82E91B1L, (int)0xB32700D0L},
        {0x44DE24C6, (int)0x88DFF51DL},
        {0x3BEB65BC, 0x29514038},
        {(int)0xC89C5933L, (int)0xB328CBDFL},
        {(int)0x8D1EC211L, (int)0xD405B4D5L},
        {0x314EF5D5, 0x26E55F3C},
        {(int)0xE92491B1L, 0x607801C6},
        {(int)0xF0A5B9D5L, (int)0x82B2C1FBL},
        {0x06064CF3, (int)0xFA51A3DAL},
        {(int)0xDA79FA60L, 0x207CC464},
        {(int)0xBED20560L, 0x3C3C74F3},
        {(int)0x89F9C9F8L, (int)0xD2D14B61L},
        {0x35FA38C6, (int)0xBB04A2C1L},
        {(int)0xCB49E3C6L, (int)0xC5626167L}
    };

    private static final int CS64_FACTOR = (int)0x82893933L;
    private static final int CS64_ADJUSTMENT0 = (int)0xF2F90B1AL;
    private static final int CS64_ADJUSTMENT1 = 0x0B1A0000;

    private static int rol16(int val) {
        return (val << 16) | (val >>> 16);
    }

    private static int sigma0(int val) {
        return (val >>> 7) ^ (val << 25) ^ (val >>> 18) ^ (val << 14) ^ (val >>> 3);
    }

    private static int sigma1(int val) {
        return (val >>> 17) ^ (val << 15) ^ (val >>> 19) ^ (val << 13) ^ (val >>> 10);
    }

    private static void sha256Rounds(int[] state, int[] words, int firstRound) {
        int a = state[0], b = state[1], c = state[2], d = state[3];
        int e = state[4], f = state[5], g = state[6], h = state[7];
        int[] constants = (firstRound == 14) ? SHA256_BLOCK_CONSTANTS : SHA256_ROUND_CONSTANTS;
        int aligned = Math.min(64, ((firstRound + 7) / 8) * 8);

        for (int i = firstRound; i < aligned; i++) {
            long rotE = (e & U32_MASK) * 0x100000001L;
            long rotA = (a & U32_MASK) * 0x100000001L;
            int s1 = (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            int ch = g ^ (e & (f ^ g));
            int t1 = h + s1 + ch + constants[i] + words[i];
            int s0 = (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            int maj = (a & b) | (c & (a | b));
            int t2 = s0 + maj;
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }

        for (int i = aligned; i < 64; i += 8) {
            long rotE = (e & U32_MASK) * 0x100000001L;
            long rotA = (a & U32_MASK) * 0x100000001L;
            int t1 = h + (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25)) + (g ^ (e & (f ^ g))) + constants[i] + words[i];
            d += t1;
            h = t1 + (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((a & b) | (c & (a | b)));

            rotE = (d & U32_MASK) * 0x100000001L;
            rotA = (h & U32_MASK) * 0x100000001L;
            t1 = g + (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25)) + (f ^ (d & (e ^ f))) + constants[i + 1] + words[i + 1];
            c += t1;
            g = t1 + (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((h & a) | (b & (h | a)));

            rotE = (c & U32_MASK) * 0x100000001L;
            rotA = (g & U32_MASK) * 0x100000001L;
            t1 = f + (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25)) + (e ^ (c & (d ^ e))) + constants[i + 2] + words[i + 2];
            b += t1;
            f = t1 + (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((g & h) | (a & (g | h)));

            rotE = (b & U32_MASK) * 0x100000001L;
            rotA = (f & U32_MASK) * 0x100000001L;
            t1 = e + (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25)) + (d ^ (b & (c ^ d))) + constants[i + 3] + words[i + 3];
            a += t1;
            e = t1 + (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((f & g) | (h & (f | g)));

            rotE = (a & U32_MASK) * 0x100000001L;
            rotA = (e & U32_MASK) * 0x100000001L;
            t1 = d + (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25)) + (c ^ (a & (b ^ c))) + constants[i + 4] + words[i + 4];
            h += t1;
            d = t1 + (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((e & f) | (g & (e | f)));

            rotE = (h & U32_MASK) * 0x100000001L;
            rotA = (d & U32_MASK) * 0x100000001L;
            t1 = c + (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25)) + (b ^ (h & (a ^ b))) + constants[i + 5] + words[i + 5];
            g += t1;
            c = t1 + (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((d & e) | (f & (d | e)));

            rotE = (g & U32_MASK) * 0x100000001L;
            rotA = (c & U32_MASK) * 0x100000001L;
            t1 = b + (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25)) + (a ^ (g & (h ^ a))) + constants[i + 6] + words[i + 6];
            f += t1;
            b = t1 + (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((c & d) | (e & (c | d)));

            rotE = (f & U32_MASK) * 0x100000001L;
            rotA = (b & U32_MASK) * 0x100000001L;
            t1 = a + (int)((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25)) + (h ^ (f & (g ^ h))) + constants[i + 7] + words[i + 7];
            e += t1;
            a = t1 + (int)((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((b & c) | (d & (b | c)));
        }

        state[0] += a; state[1] += b; state[2] += c; state[3] += d;
        state[4] += e; state[5] += f; state[6] += g; state[7] += h;
    }

    private static void expandSchedule(int[] words, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            int x = words[i - 2], y = words[i - 15];
            words[i] = sigma1(x) + words[i - 7] + sigma0(y) + words[i - 16];
        }
    }

    private static int[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int padLen = (55 - length) % 64;
        if (padLen < 0) padLen += 64;
        ByteBuffer bb = ByteBuffer.allocate(data.length - 7 + 1 + padLen + 8);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put(data, 7, data.length - 7);
        bb.put((byte) 0x80);
        for (int i = 0; i < padLen; i++) bb.put((byte) 0);
        bb.putLong(((long) length) * 8L);

        byte[] padded = bb.array();
        int[] words = new int[64];
        int w14 = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        int w15 = ((data[3] & 0xFF) << 24) | ((data[4] & 0xFF) << 16) | ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
        words[14] = w14;
        words[15] = w15;

        for (int i = 16; i < 30; i++) {
            int val = SHA256_SCHEDULE[i - 16];
            if (i - 2 >= 14) val += (i == 16) ? (sigma1(words[i - 2]) ^ SHA256_SIGMA1_ADJUSTMENT) : sigma1(words[i - 2]);
            if (i - 7 >= 14) val += words[i - 7];
            if (i - 15 >= 14) val += (i == 29) ? (sigma0(words[i - 15]) ^ SHA256_SIGMA0_ADJUSTMENT) : sigma0(words[i - 15]);
            words[i] = val;
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14];
        expandSchedule(words, 31);

        int[] state = Arrays.copyOf(SHA256_STATE, 8);
        sha256Rounds(state, words, 14);
        for (int i = 0; i < 8; i++) state[i] += SHA256_INITIAL[i];

        // Now process remaining blocks from padded
        for (int offset = 0; offset < padded.length; offset += 64) {
            ByteBuffer block = ByteBuffer.wrap(padded, offset, 64).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < 16; i++) words[i] = block.getInt();
            expandSchedule(words, 16);
            int[] tempState = Arrays.copyOf(state, 8);
            sha256Rounds(tempState, words, 0);
            for (int i = 0; i < 8; i++) state[i] += tempState[i];
        }
        return state;
    }

    private static int[] cs64Digest(byte[] data, int[] digest) {
        int keyA = digest[0] | 1;
        int keyB = digest[1] | 1;
        int factorA = keyA * CS64_FACTOR;
        int factorB = keyB * CS64_FACTOR;

        int[] terms = new int[CS64_COEFFICIENTS.length + (data.length + 4) / 4 + 2];
        int[] offsets = new int[terms.length];
        int termCount = 0;

        for (int i = 0; i < CS64_COEFFICIENTS.length; i++) {
            terms[termCount] = CS64_COEFFICIENTS[i][0] * ((i % 2 == 0) ? factorA : factorB);
            offsets[termCount] = CS64_COEFFICIENTS[i][1];
            termCount++;
        }

        // Split b" " + data into little-endian 32-bit words, zero padded to even count
        byte[] prep = new byte[data.length + 1];
        prep[0] = 0;
        System.arraycopy(data, 0, prep, 1, data.length);
        int pad = (-prep.length) % 4;
        if (pad < 0) pad += 4;
        ByteBuffer bb = ByteBuffer.allocate(prep.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(prep);
        bb.rewind();
        int wordCount = (prep.length + pad) / 4;
        int[] words = new int[wordCount + (wordCount % 2 != 0 ? 1 : 0)];
        for (int i = 0; i < wordCount; i++) words[i] = bb.getInt();

        for (int i = 0; i < words.length; i++) {
            int word = words[i];
            int term = word * ((i % 2 == 0) ? keyA : keyB);
            int offset = rol16(word) * ((i % 2 == 0) ? (int)0xEF0569FBL : (int)0xC31713DBL);
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factorA;
                offset += CS64_ADJUSTMENT1;
            }
            terms[termCount] = term;
            offsets[termCount] = offset;
            termCount++;
        }

        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < termCount; i += 2) {
            int x = terms[i], y = terms[i + 1];
            int t = rol16(offsets[i] + x);
            int q = t * 0x76F50F34;
            swapAcc += rol16(q) * (int)0xC3EFEA97L + q;
            t = rol16(offsets[i + 1] + y);
            q = t * 0x1881CE3C;
            swapLast = rol16(q) * 0x35BD1EC9 + q;
            swapAcc += swapLast;

            state = state * keyA + x;
            state = rol16(state) * (int)0xCF98B111L;
            state = rol16(state) * (int)0x87085B9FL;
            state = rol16(state) * 0x12CEB96D;
            state = rol16(state) * 0x257E1D83;
            acc += state;
            t = state * keyB + y;
            t = rol16(t) * (int)0xA27416F5L;
            t = rol16(t) * (int)0xD38396FFL;
            state = rol16(rol16(t) * 0x7C932B89) * (int)0xBFA49F69L;
            acc += state;
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }

    public static String generateSecMsGec() {
        double now = (double)(System.currentTimeMillis() / 1000L);
        double ticks = now + WIN_EPOCH;
        ticks -= ticks % 300;
        ticks *= S_TO_NS / 100.0;
        String strToHash = String.format("%.0f%s", ticks, TRUSTED_CLIENT_TOKEN);
        byte[] bytesToHash = strToHash.getBytes(StandardCharsets.US_ASCII);

        int[] shaState = sha256Digest(bytesToHash);
        int[] cs64 = cs64Digest(bytesToHash, shaState);

        StringBuilder csHex = new StringBuilder();
        for (int val : cs64) {
            // Little-endian hex of each 32-bit int
            for (int b = 0; b < 4; b++) {
                csHex.append(String.format("%02X", (val >>> (b * 8)) & 0xFF));
            }
        }

        StringBuilder shaHex = new StringBuilder();
        for (int val : shaState) {
            // Big-endian hex of each 32-bit int
            for (int b = 3; b >= 0; b--) {
                shaHex.append(String.format("%02X", (val >>> (b * 8)) & 0xFF));
            }
        }
        return csHex.toString() + "-" + shaHex.toString();
    }
}
