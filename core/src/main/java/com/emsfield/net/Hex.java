package com.emsfield.net;

public final class Hex {
    private static final char[] D = "0123456789ABCDEF".toCharArray();

    private Hex() {}

    public static String of(byte[] b) {
        return b == null ? "" : of(b, 0, b.length);
    }

    public static String of(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            int v = b[off + i] & 0xFF;
            sb.append(D[v >>> 4]).append(D[v & 0xF]);
        }
        return sb.toString();
    }

    public static String mac(byte[] m) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            int v = m[i] & 0xFF;
            sb.append(D[v >>> 4]).append(D[v & 0xF]);
        }
        return sb.toString();
    }
}
