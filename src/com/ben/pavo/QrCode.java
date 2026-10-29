package com.ben.pavo;

import android.graphics.Bitmap;

/**
 * Small QR encoder (byte mode, error correction level M, versions 1-9) used to
 * share the FTP address with another device's camera.
 *
 * Layout follows the standard reference implementation: function patterns,
 * interleaved Reed-Solomon blocks, eight candidate masks scored by the four
 * penalty rules, then the format information for the chosen mask.
 */
final class QrCode {

    private static final int ECC_PER_BLOCK[] = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22};
    private static final int NUM_BLOCKS[] = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5};

    private static final int ALIGN[][] = {
            null, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38},
            {6, 24, 42}, {6, 26, 46},
    };

    private QrCode() {
    }

    static Bitmap render(String text, int sizePx, int darkColor, int lightColor) {
        boolean[][] modules = encode(text);
        if (modules == null) return null;
        int n = modules.length;
        int quiet = 4;
        int total = n + quiet * 2;
        Bitmap bitmap = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < total; y++) {
            for (int x = 0; x < total; x++) {
                int mx = x - quiet;
                int my = y - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < n && my < n && modules[my][mx];
                bitmap.setPixel(x, y, dark ? darkColor : lightColor);
            }
        }
        return Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, false);
    }

    static boolean[][] encode(String text) {
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Throwable t) {
            data = text.getBytes();
        }
        int version = -1;
        for (int v = 1; v <= 9; v++) {
            if (data.length + 2 <= dataCodewords(v)) {
                version = v;
                break;
            }
        }
        if (version < 0) return null;

        byte[] codewords = addEccAndInterleave(buildData(version, data), version);
        boolean[][] modules = new boolean[17 + version * 4][17 + version * 4];
        boolean[][] function = new boolean[modules.length][modules.length];
        drawFunctionPatterns(modules, function, version);
        drawCodewords(modules, function, codewords);
        int mask = chooseMask(modules, function);
        applyMask(modules, function, mask);
        drawFormatBits(modules, mask);
        if (version >= 7) drawVersion(modules, version);
        return modules;
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private static byte[] buildData(int version, byte[] data) {
        int capacityBits = dataCodewords(version) * 8;
        boolean[] bits = new boolean[capacityBits];
        int p = 0;
        p = append(bits, p, 0b0100, 4);
        p = append(bits, p, data.length, 8);
        for (byte b : data) p = append(bits, p, b & 0xFF, 8);
        p += Math.min(4, capacityBits - p);
        while (p % 8 != 0) p++;
        boolean pad = false;
        while (p < capacityBits) {
            append(bits, p, pad ? 0x11 : 0xEC, 8);
            p += 8;
            pad = !pad;
        }
        byte[] out = new byte[capacityBits / 8];
        for (int i = 0; i < out.length; i++) {
            int value = 0;
            for (int k = 0; k < 8; k++) {
                if (bits[i * 8 + k]) value |= 1 << (7 - k);
            }
            out[i] = (byte) value;
        }
        return out;
    }

    private static int append(boolean[] bits, int pos, int value, int length) {
        for (int i = 0; i < length; i++) {
            bits[pos + i] = ((value >> (length - 1 - i)) & 1) == 1;
        }
        return pos + length;
    }

    private static int rawDataModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int align = version / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (version >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int version) {
        return rawDataModules(version) / 8 - ECC_PER_BLOCK[version] * NUM_BLOCKS[version];
    }

    private static byte[] addEccAndInterleave(byte[] data, int version) {
        int blocks = NUM_BLOCKS[version];
        int eccLen = ECC_PER_BLOCK[version];
        int rawCodewords = rawDataModules(version) / 8;
        int numShort = blocks - rawCodewords % blocks;
        int shortLen = rawCodewords / blocks;
        byte[][] dataBlocks = new byte[blocks][];
        byte[][] eccBlocks = new byte[blocks][];
        int offset = 0;
        for (int i = 0; i < blocks; i++) {
            int len = shortLen - eccLen + (i < numShort ? 0 : 1);
            dataBlocks[i] = new byte[len];
            System.arraycopy(data, offset, dataBlocks[i], 0, len);
            offset += len;
            eccBlocks[i] = reedSolomon(dataBlocks[i], eccLen);
        }
        byte[] result = new byte[rawCodewords];
        int index = 0;
        for (int i = 0; i <= shortLen - eccLen; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < dataBlocks[b].length) result[index++] = dataBlocks[b][i];
            }
        }
        for (int i = 0; i < eccLen; i++) {
            for (int b = 0; b < blocks; b++) result[index++] = eccBlocks[b][i];
        }
        return result;
    }

    private static byte[] reedSolomon(byte[] data, int eccLen) {
        int[] divisor = new int[eccLen];
        divisor[eccLen - 1] = 1;
        int root = 1;
        for (int i = 0; i < eccLen; i++) {
            for (int j = 0; j < eccLen; j++) {
                divisor[j] = mul(divisor[j], root);
                if (j + 1 < eccLen) divisor[j] ^= divisor[j + 1];
            }
            root = mul(root, 2);
        }
        int[] remainder = new int[eccLen];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ remainder[0];
            System.arraycopy(remainder, 1, remainder, 0, eccLen - 1);
            remainder[eccLen - 1] = 0;
            for (int i = 0; i < eccLen; i++) remainder[i] ^= mul(divisor[i], factor);
        }
        byte[] out = new byte[eccLen];
        for (int i = 0; i < eccLen; i++) out[i] = (byte) remainder[i];
        return out;
    }

    private static int mul(int a, int b) {
        int result = 0;
        int x = a & 0xFF;
        int y = b & 0xFF;
        while (y != 0) {
            if ((y & 1) != 0) result ^= x;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
            y >>= 1;
        }
        return result & 0xFF;
    }

    // ------------------------------------------------------------------
    // Modules
    // ------------------------------------------------------------------

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] f, int version) {
        int n = m.length;
        for (int i = 0; i < n; i++) {
            set(m, f, i, 6, i % 2 == 0);
            set(m, f, 6, i, i % 2 == 0);
        }
        finder(m, f, 3, 3);
        finder(m, f, n - 4, 3);
        finder(m, f, 3, n - 4);
        int[] align = ALIGN[version];
        for (int cy : align) {
            for (int cx : align) {
                if ((cx == 6 && cy == 6) || (cx == 6 && cy == n - 7)
                        || (cx == n - 7 && cy == 6)) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        set(m, f, cx + dx, cy + dy,
                                Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                    }
                }
            }
        }
        // Reserve the format strips (values are written after masking).
        for (int i = 0; i < 9; i++) {
            reserve(f, i, 8);      // first copy: column 8
            reserve(f, 8, i);      // first copy: row 8
            if (i < 8) {
                reserve(f, n - 1 - i, 8); // second copy: row 8, right side
                reserve(f, 8, n - 1 - i); // second copy: column 8, bottom side
            }
        }
        reserve(f, 8, n - 8);      // fixed dark module
        if (version >= 7) {
            for (int i = 0; i < 18; i++) {
                reserve(f, n - 11 + i % 3, i / 3);
                reserve(f, i / 3, n - 11 + i % 3);
            }
        }
    }

    private static void finder(boolean[][] m, boolean[][] f, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                set(m, f, cx + dx, cy + dy, dist != 2 && dist != 4);
            }
        }
    }

    private static void set(boolean[][] m, boolean[][] f, int x, int y, boolean dark) {
        if (x < 0 || y < 0 || x >= m.length || y >= m.length) return;
        m[y][x] = dark;
        f[y][x] = true;
    }

    private static void reserve(boolean[][] f, int x, int y) {
        if (x < 0 || y < 0 || x >= f.length || y >= f.length) return;
        f[y][x] = true;
    }

    private static void drawCodewords(boolean[][] m, boolean[][] f, byte[] data) {
        int n = m.length;
        int index = 0;
        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            boolean upward = ((right + 1) & 2) == 0;
            for (int vert = 0; vert < n; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    int y = upward ? n - 1 - vert : vert;
                    if (f[y][x] || index >= data.length * 8) continue;
                    m[y][x] = ((data[index >>> 3] >> (7 - (index & 7))) & 1) != 0;
                    index++;
                }
            }
        }
    }

    private static int chooseMask(boolean[][] m, boolean[][] f) {
        int best = 0;
        int bestScore = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(m, f, mask);
            drawFormatBits(m, mask);
            int score = penalty(m);
            applyMask(m, f, mask);
            if (score < bestScore) {
                bestScore = score;
                best = mask;
            }
        }
        return best;
    }

    private static void applyMask(boolean[][] m, boolean[][] f, int mask) {
        int n = m.length;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (f[y][x]) continue;
                if (maskBit(mask, x, y)) m[y][x] = !m[y][x];
            }
        }
    }

    private static boolean maskBit(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (y / 2 + x / 3) % 2 == 0;
            case 5: return (x * y) % 2 + (x * y) % 3 == 0;
            case 6: return ((x * y) % 2 + (x * y) % 3) % 2 == 0;
            default: return ((x + y) % 2 + (x * y) % 3) % 2 == 0;
        }
    }

    private static int penalty(boolean[][] m) {
        int n = m.length;
        int score = 0;
        for (int y = 0; y < n; y++) {
            int run = 1;
            for (int x = 1; x < n; x++) {
                if (m[y][x] == m[y][x - 1]) run++;
                else {
                    if (run >= 5) score += 3 + run - 5;
                    run = 1;
                }
            }
            if (run >= 5) score += 3 + run - 5;
        }
        for (int x = 0; x < n; x++) {
            int run = 1;
            for (int y = 1; y < n; y++) {
                if (m[y][x] == m[y - 1][x]) run++;
                else {
                    if (run >= 5) score += 3 + run - 5;
                    run = 1;
                }
            }
            if (run >= 5) score += 3 + run - 5;
        }
        for (int y = 0; y < n - 1; y++) {
            for (int x = 0; x < n - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) score += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : m) for (boolean cell : row) if (cell) dark++;
        int percent = dark * 100 / (n * n);
        score += 10 * (Math.abs(percent - 50) / 5);
        return score;
    }

    private static void drawFormatBits(boolean[][] m, int mask) {
        int n = m.length;
        int data = (0 << 3) | mask; // level M has format bits 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) m[i][8] = bit(bits, i);
        m[7][8] = bit(bits, 6);
        m[8][8] = bit(bits, 7);
        m[8][7] = bit(bits, 8);
        for (int i = 9; i < 15; i++) m[8][14 - i] = bit(bits, i);
        for (int i = 0; i < 8; i++) m[8][n - 1 - i] = bit(bits, i);
        for (int i = 8; i < 15; i++) m[n - 15 + i][8] = bit(bits, i);
        m[n - 8][8] = true;
    }

    private static void drawVersion(boolean[][] m, int version) {
        int n = m.length;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = n - 11 + i % 3;
            int c = i / 3;
            m[c][a] = b;
            m[a][c] = b;
        }
    }

    private static boolean bit(int value, int index) {
        return ((value >> index) & 1) == 1;
    }
}
