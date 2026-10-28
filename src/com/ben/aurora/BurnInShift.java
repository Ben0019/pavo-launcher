package com.ben.aurora;

/**
 * Shared OLED pixel-shift pattern.
 *
 * A 5x5 square spiral starting at the centre: 25 offsets with one pixel per
 * step and equal time on every position. Monitor vendors use the same idea
 * (1-2 px every 1-3 minutes); a spiral avoids parking on the centre and keeps
 * each edge inside the shift range.
 */
final class BurnInShift {
    static final int[][] OFFSETS = spiral();

    private BurnInShift() {}

    static int[][] spiral() {
        int side = 5;
        int[][] out = new int[side * side][2];
        int cx = 0, cy = 0, count = 0;
        out[count++] = new int[]{0, 0};
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        int dir = 0, run = 1;
        while (count < out.length) {
            for (int leg = 0; leg < 2 && count < out.length; leg++) {
                for (int i = 0; i < run && count < out.length; i++) {
                    cx += dx[dir];
                    cy += dy[dir];
                    if (Math.abs(cx) <= 2 && Math.abs(cy) <= 2) {
                        out[count++] = new int[]{cx, cy};
                    }
                }
                dir = (dir + 1) % 4;
            }
            run++;
        }
        return out;
    }
}
