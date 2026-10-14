package com.emsfield.net;

/** IPv4 주소 유틸리티. 주소는 big-endian int 로 다룬다. */
public final class Ip4 {
    private Ip4() {}

    public static int parse(String s) {
        if (s == null) throw new IllegalArgumentException("IP 주소가 비어 있습니다");
        String[] p = s.trim().split("\\.");
        if (p.length != 4) throw new IllegalArgumentException("잘못된 IP 주소: " + s);
        int ip = 0;
        for (String part : p) {
            int v;
            try {
                v = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("잘못된 IP 주소: " + s);
            }
            if (v < 0 || v > 255) throw new IllegalArgumentException("잘못된 IP 주소: " + s);
            ip = (ip << 8) | v;
        }
        return ip;
    }

    public static String str(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "." + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    public static int mask(int prefix) {
        if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("잘못된 prefix: " + prefix);
        return prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
    }

    public static int read(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static void write(byte[] b, int off, int ip) {
        b[off] = (byte) (ip >>> 24);
        b[off + 1] = (byte) (ip >>> 16);
        b[off + 2] = (byte) (ip >>> 8);
        b[off + 3] = (byte) ip;
    }
}
