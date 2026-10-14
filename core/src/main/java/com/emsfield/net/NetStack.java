package com.emsfield.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.NoRouteToHostException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 앱 내장 최소 IPv4 네트워크 스택 (Ethernet / ARP / IPv4 / ICMP / TCP 클라이언트).
 * <p>
 * 폰 OS의 네트워크 스택을 전혀 거치지 않는다. USB 이더넷 어댑터는 앱이 직접 점유하고,
 * IP 주소는 이 객체 안에만 존재하므로 폰의 LTE/Wi-Fi 라우팅과 완전히 분리된다.
 * <p>
 * 범위: 같은 대역(또는 게이트웨이 경유) 장비와의 Modbus TCP 수준 통신. IP 단편화, IPv6,
 * TCP 윈도우 스케일/SACK 등은 지원하지 않는다.
 */
public final class NetStack implements Closeable {

    public interface Logger {
        void log(String msg);
    }

    public static final class PingResult {
        public final boolean ok;
        public final double rttMs;
        public final String error;

        PingResult(boolean ok, double rttMs, String error) {
            this.ok = ok;
            this.rttMs = rttMs;
            this.error = error;
        }

        @Override
        public String toString() {
            return ok ? String.format("응답 %.2f ms", rttMs) : "실패: " + error;
        }
    }

    static final int ETH_HDR = 14;
    static final int TYPE_ARP = 0x0806;
    static final int TYPE_IPV4 = 0x0800;
    static final int PROTO_ICMP = 1;
    static final int PROTO_TCP = 6;
    private static final byte[] BROADCAST = {-1, -1, -1, -1, -1, -1};
    private static final byte[] ZERO_MAC = new byte[6];
    private static final int MIN_FRAME = 60;
    private static final int ARP_RETRY_MS = 300;

    private final FrameIO io;
    private final byte[] mac;
    private final Logger logger;
    private volatile IpConfig config;

    private final Map<Integer, byte[]> arpCache = new ConcurrentHashMap<>();
    private final Object arpMonitor = new Object();
    private final Map<Long, TcpConnection> tcp = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;
    private final Object txLock = new Object();
    private final Object pingLock = new Object();
    private final Random rnd = new SecureRandom();
    private final AtomicInteger ipId = new AtomicInteger();
    private final AtomicLong rxFrames = new AtomicLong();
    private final AtomicLong txFrames = new AtomicLong();

    private volatile boolean running;
    private volatile Thread rxThread;
    private volatile PingWaiter pingWaiter;
    private volatile byte[] conflictMac;
    private int pingSeq;
    private volatile boolean frameTrace;

    public NetStack(FrameIO io, IpConfig config, Logger logger) {
        this.io = io;
        this.mac = io.macAddress().clone();
        this.config = config;
        this.logger = logger != null ? logger : msg -> { };
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "netstack-timer");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::rxLoop, "netstack-rx");
        t.setDaemon(true);
        rxThread = t;
        t.start();
        log("스택 시작: " + io.describe() + ", MAC " + Hex.mac(mac) + ", IP " + config);
    }

    @Override
    public void close() throws IOException {
        running = false;
        for (TcpConnection c : tcp.values()) c.abort("스택 종료");
        timer.shutdownNow();
        Thread t = rxThread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        io.close();
    }

    /** 실행 중 IP 변경. ARP 캐시를 비우고 기존 TCP 연결은 끊는다. */
    public void setConfig(IpConfig newConfig) {
        for (TcpConnection c : tcp.values()) c.abort("IP 설정 변경");
        arpCache.clear();
        conflictMac = null;
        this.config = newConfig;
        log("IP 변경: " + newConfig);
    }

    public IpConfig config() {
        return config;
    }

    public byte[] mac() {
        return mac.clone();
    }

    public void setFrameTrace(boolean on) {
        frameTrace = on;
    }

    public long rxFrames() {
        return rxFrames.get();
    }

    public long txFrames() {
        return txFrames.get();
    }

    ScheduledExecutorService timer() {
        return timer;
    }

    void log(String msg) {
        logger.log(msg);
    }

    // ------------------------------------------------------------------ public operations

    /**
     * ARP 프로브로 같은 IP를 쓰는 장비가 있는지 확인하고, 없으면 Gratuitous ARP 로 알린다.
     * (현장 장비의 ARP 캐시에 남은 예전 MAC 정보를 갱신하는 효과도 있다)
     *
     * @return 충돌한 장비의 MAC, 충돌이 없으면 null
     */
    public byte[] probeAddressConflict(int waitMs) throws IOException {
        conflictMac = null;
        int addr = config.address;
        for (int i = 0; i < 2; i++) {
            sendArp(1, BROADCAST, ZERO_MAC, addr, 0); // ARP probe: 출발지 IP 0.0.0.0
            sleep(waitMs / 2);
            if (conflictMac != null) break;
        }
        byte[] c = conflictMac;
        if (c != null) {
            log("⚠ IP 충돌: " + Ip4.str(addr) + " 를 MAC " + Hex.mac(c) + " 장비가 이미 사용 중");
            return c;
        }
        sendArp(1, BROADCAST, ZERO_MAC, addr, addr); // gratuitous ARP
        log("IP 충돌 없음, " + Ip4.str(addr) + " 사용 알림(Gratuitous ARP) 전송");
        return null;
    }

    /** ARP 로 대상 IP의 MAC 을 확인한다. 장비 연결 1차 진단용. */
    public byte[] arp(int ip, int timeoutMs) throws IOException {
        return resolve(nextHop(ip), timeoutMs);
    }

    public PingResult ping(int dst, int timeoutMs) {
        synchronized (pingLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                resolve(nextHop(dst), timeoutMs); // ARP 시간은 RTT 에서 제외
            } catch (IOException e) {
                return new PingResult(false, 0, e.getMessage());
            }
            int id = 0x4542;
            int seq = (++pingSeq) & 0xFFFF;
            byte[] p = new byte[8 + 32];
            p[0] = 8;
            put16(p, 4, id);
            put16(p, 6, seq);
            for (int i = 8; i < p.length; i++) p[i] = (byte) ('a' + (i - 8) % 26);
            put16(p, 2, checksum(p, 0, p.length, 0));
            PingWaiter w = new PingWaiter(dst, id, seq);
            pingWaiter = w;
            long t0 = System.nanoTime();
            try {
                sendIpv4(dst, PROTO_ICMP, p, 0, p.length, true, 0);
                long remain = Math.max(1, deadline - System.currentTimeMillis());
                if (w.latch.await(remain, TimeUnit.MILLISECONDS)) {
                    return new PingResult(true, (w.replyNanos - t0) / 1_000_000.0, null);
                }
                return new PingResult(false, 0, "응답 없음 (장비가 ICMP 를 막았을 수 있음 - Modbus 포트 확인 권장)");
            } catch (IOException e) {
                return new PingResult(false, 0, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new PingResult(false, 0, "중단됨");
            } finally {
                pingWaiter = null;
            }
        }
    }

    /** 앱 내장 TCP 로 연결한다. */
    public TcpConnection connect(int dst, int port, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        resolve(nextHop(dst), timeoutMs);
        int localPort;
        long key;
        TcpConnection c;
        synchronized (tcp) {
            do {
                localPort = 49152 + rnd.nextInt(16384);
                key = key(dst, port, localPort);
            } while (tcp.containsKey(key));
            c = new TcpConnection(this, dst, port, localPort, rnd.nextInt());
            tcp.put(key, c);
        }
        int remain = (int) Math.max(1, deadline - System.currentTimeMillis());
        c.connect(remain);
        return c;
    }

    void unregister(TcpConnection c) {
        tcp.remove(key(c.remoteIp, c.remotePort, c.localPort));
    }

    // ------------------------------------------------------------------ receive path

    private void rxLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            int n;
            try {
                n = io.receive(buf, 200);
            } catch (IOException e) {
                if (running) {
                    log("수신 오류: " + e.getMessage());
                    sleep(200);
                }
                continue;
            }
            if (n <= 0) continue;
            rxFrames.incrementAndGet();
            if (frameTrace) log("RX " + n + "B " + Hex.of(buf, 0, Math.min(n, 64)));
            try {
                handleFrame(buf, n);
            } catch (RuntimeException e) {
                log("프레임 처리 오류: " + e);
            }
        }
    }

    private void handleFrame(byte[] b, int n) {
        if (n < ETH_HDR) return;
        boolean toUs = regionEq(b, 0, mac);
        boolean bcast = regionEq(b, 0, BROADCAST);
        if (!toUs && !bcast) return;
        int type = u16(b, 12);
        if (type == TYPE_ARP) {
            handleArp(b, ETH_HDR, n - ETH_HDR);
        } else if (type == TYPE_IPV4 && toUs) {
            handleIpv4(b, ETH_HDR, n - ETH_HDR);
        }
    }

    private void handleArp(byte[] b, int o, int len) {
        if (len < 28) return;
        if (u16(b, o) != 1 || u16(b, o + 2) != TYPE_IPV4 || b[o + 4] != 6 || b[o + 5] != 4) return;
        int op = u16(b, o + 6);
        byte[] sha = Arrays.copyOfRange(b, o + 8, o + 14);
        int spa = Ip4.read(b, o + 14);
        int tpa = Ip4.read(b, o + 24);
        IpConfig c = config;
        if (spa == c.address && !Arrays.equals(sha, mac)) {
            conflictMac = sha;
            log("⚠ IP 충돌 감지: " + Ip4.str(spa) + " 를 MAC " + Hex.mac(sha) + " 장비도 사용 중");
        }
        if (spa != 0 && spa != c.address) {
            byte[] old = arpCache.put(spa, sha);
            if (old == null || !Arrays.equals(old, sha)) {
                synchronized (arpMonitor) {
                    arpMonitor.notifyAll();
                }
            }
        }
        if (op == 1 && tpa == c.address) {
            try {
                sendArp(2, sha, sha, spa, c.address);
            } catch (IOException e) {
                log("ARP 응답 전송 실패: " + e.getMessage());
            }
        }
    }

    private void handleIpv4(byte[] b, int o, int len) {
        if (len < 20) return;
        int vihl = b[o] & 0xFF;
        if ((vihl >>> 4) != 4) return;
        int ihl = (vihl & 0xF) * 4;
        if (ihl < 20 || ihl > len) return;
        int total = u16(b, o + 2);
        if (total < ihl || total > len) return;
        if (checksum(b, o, ihl, 0) != 0) return;
        if ((u16(b, o + 6) & 0x3FFF) != 0) return; // 단편화 패킷 미지원
        int proto = b[o + 9] & 0xFF;
        int src = Ip4.read(b, o + 12);
        int dst = Ip4.read(b, o + 16);
        if (dst != config.address) return;
        int po = o + ihl;
        int plen = total - ihl;
        if (proto == PROTO_ICMP) handleIcmp(src, b, po, plen);
        else if (proto == PROTO_TCP) handleTcp(src, dst, b, po, plen);
    }

    private void handleIcmp(int src, byte[] b, int o, int len) {
        if (len < 8 || checksum(b, o, len, 0) != 0) return;
        int type = b[o] & 0xFF;
        if (type == 8) { // echo request → reply (장비/노트북에서 앱 IP로 ping 가능)
            byte[] r = Arrays.copyOfRange(b, o, o + len);
            r[0] = 0;
            r[2] = 0;
            r[3] = 0;
            put16(r, 2, checksum(r, 0, len, 0));
            try {
                sendIpv4(src, PROTO_ICMP, r, 0, len, false, 0);
            } catch (IOException ignored) {
                // ARP 미해결이면 다음 요청에서 응답
            }
        } else if (type == 0) {
            PingWaiter w = pingWaiter;
            if (w != null && w.target == src && u16(b, o + 4) == w.id && u16(b, o + 6) == w.seq) {
                w.replyNanos = System.nanoTime();
                w.latch.countDown();
            }
        }
    }

    private void handleTcp(int src, int dst, byte[] b, int o, int len) {
        if (len < 20) return;
        if (checksum(b, o, len, pseudoSum(src, dst, PROTO_TCP, len)) != 0) return;
        int sport = u16(b, o);
        int dport = u16(b, o + 2);
        TcpConnection c = tcp.get(key(src, sport, dport));
        if (c != null) {
            c.onSegment(b, o, len);
            return;
        }
        int flags = b[o + 13] & 0xFF;
        if ((flags & TcpConnection.RST) != 0) return;
        int seq = Ip4.read(b, o + 4);
        int ack = Ip4.read(b, o + 8);
        int dataOff = ((b[o + 12] & 0xFF) >>> 4) * 4;
        int segLen = len - dataOff + ((flags & TcpConnection.SYN) != 0 ? 1 : 0) + ((flags & TcpConnection.FIN) != 0 ? 1 : 0);
        try {
            if ((flags & TcpConnection.ACK) != 0) {
                sendTcp(src, dport, sport, ack, 0, TcpConnection.RST, 0, null, null, 0, 0);
            } else {
                sendTcp(src, dport, sport, 0, seq + segLen, TcpConnection.RST | TcpConnection.ACK, 0, null, null, 0, 0);
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ transmit path

    private int nextHop(int dst) throws IOException {
        IpConfig c = config;
        if (dst == c.address) throw new IOException("대상 IP가 앱 자신의 IP와 같습니다");
        if (c.sameSubnet(dst)) return dst;
        if (c.gateway != 0) return c.gateway;
        throw new NoRouteToHostException("대상 " + Ip4.str(dst) + " 가 설정 대역(" + c + ") 밖이고 게이트웨이가 없습니다");
    }

    /**
     * IP → MAC 해석. 수신 스레드에서는 절대 대기하지 않는다(대기하면 ARP 응답을 처리할 스레드가 막힘).
     */
    private byte[] resolve(int ip, int timeoutMs) throws IOException {
        byte[] m = arpCache.get(ip);
        if (m != null) return m;
        if (ip == config.broadcast()) return BROADCAST;
        boolean mayBlock = Thread.currentThread() != rxThread && timeoutMs > 0;
        if (!mayBlock) {
            sendArp(1, BROADCAST, ZERO_MAC, ip, config.address);
            throw new IOException("ARP 미해결: " + Ip4.str(ip));
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastSend = 0;
        synchronized (arpMonitor) {
            while (true) {
                m = arpCache.get(ip);
                if (m != null) return m;
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    throw new NoRouteToHostException("ARP 응답 없음: " + Ip4.str(ip)
                            + " (케이블 연결, 장비 전원, IP 대역 설정을 확인하세요)");
                }
                if (now - lastSend >= ARP_RETRY_MS) {
                    sendArp(1, BROADCAST, ZERO_MAC, ip, config.address);
                    lastSend = now;
                }
                try {
                    arpMonitor.wait(Math.min(ARP_RETRY_MS, deadline - now));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("중단됨");
                }
            }
        }
    }

    private void sendArp(int op, byte[] ethDst, byte[] tha, int tpa, int spa) throws IOException {
        byte[] f = new byte[ETH_HDR + 28];
        System.arraycopy(ethDst, 0, f, 0, 6);
        System.arraycopy(mac, 0, f, 6, 6);
        put16(f, 12, TYPE_ARP);
        int o = ETH_HDR;
        put16(f, o, 1);
        put16(f, o + 2, TYPE_IPV4);
        f[o + 4] = 6;
        f[o + 5] = 4;
        put16(f, o + 6, op);
        System.arraycopy(mac, 0, f, o + 8, 6);
        Ip4.write(f, o + 14, spa);
        System.arraycopy(tha, 0, f, o + 18, 6);
        Ip4.write(f, o + 24, tpa);
        sendFrame(f, f.length);
    }

    /**
     * @param mayBlock false 이면 ARP 미해결 시 대기하지 않고 IOException
     */
    void sendIpv4(int dst, int proto, byte[] payload, int off, int len, boolean mayBlock, int arpTimeoutMs) throws IOException {
        int arpWait = mayBlock ? (arpTimeoutMs > 0 ? arpTimeoutMs : 1000) : 0;
        byte[] dmac = resolve(nextHop(dst), arpWait);
        byte[] f = new byte[ETH_HDR + 20 + len];
        System.arraycopy(dmac, 0, f, 0, 6);
        System.arraycopy(mac, 0, f, 6, 6);
        put16(f, 12, TYPE_IPV4);
        int o = ETH_HDR;
        f[o] = 0x45;
        put16(f, o + 2, 20 + len);
        put16(f, o + 4, ipId.incrementAndGet() & 0xFFFF);
        put16(f, o + 6, 0x4000); // Don't Fragment
        f[o + 8] = 64;
        f[o + 9] = (byte) proto;
        Ip4.write(f, o + 12, config.address);
        Ip4.write(f, o + 16, dst);
        put16(f, o + 10, checksum(f, o, 20, 0));
        System.arraycopy(payload, off, f, o + 20, len);
        sendFrame(f, f.length);
    }

    void sendTcp(int dst, int sport, int dport, int seq, int ack, int flags, int window,
                 byte[] options, byte[] data, int off, int len) throws IOException {
        int optLen = options == null ? 0 : options.length;
        int hl = 20 + optLen;
        byte[] s = new byte[hl + len];
        put16(s, 0, sport);
        put16(s, 2, dport);
        Ip4.write(s, 4, seq);
        Ip4.write(s, 8, ack);
        s[12] = (byte) ((hl / 4) << 4);
        s[13] = (byte) flags;
        put16(s, 14, Math.min(window, 0xFFFF));
        if (optLen > 0) System.arraycopy(options, 0, s, 20, optLen);
        if (len > 0) System.arraycopy(data, off, s, hl, len);
        put16(s, 16, checksum(s, 0, s.length, pseudoSum(config.address, dst, PROTO_TCP, s.length)));
        // TCP 는 연결 시점에 ARP 를 미리 해결하므로 대기하지 않는다 (락 보유 중 대기 → 교착 방지)
        sendIpv4(dst, PROTO_TCP, s, 0, s.length, false, 0);
    }

    private void sendFrame(byte[] f, int len) throws IOException {
        byte[] out = f;
        if (len < MIN_FRAME) { // 일부 USB 어댑터는 자동 패딩을 하지 않음
            out = Arrays.copyOf(f, MIN_FRAME);
            len = MIN_FRAME;
        }
        synchronized (txLock) {
            io.send(out, len);
        }
        txFrames.incrementAndGet();
        if (frameTrace) log("TX " + len + "B " + Hex.of(out, 0, Math.min(len, 64)));
    }

    // ------------------------------------------------------------------ helpers

    private static final class PingWaiter {
        final int target, id, seq;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile long replyNanos;

        PingWaiter(int target, int id, int seq) {
            this.target = target;
            this.id = id;
            this.seq = seq;
        }
    }

    private static long key(int remoteIp, int remotePort, int localPort) {
        return ((remoteIp & 0xFFFFFFFFL) << 32) | ((long) (remotePort & 0xFFFF) << 16) | (localPort & 0xFFFF);
    }

    static long pseudoSum(int src, int dst, int proto, int len) {
        return ((src >>> 16) & 0xFFFF) + (src & 0xFFFF) + ((dst >>> 16) & 0xFFFF) + (dst & 0xFFFF) + proto + len;
    }

    static int checksum(byte[] b, int off, int len, long initial) {
        long sum = initial;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (b[i] & 0xFF) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (int) (~sum & 0xFFFF);
    }

    static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
    }

    private static boolean regionEq(byte[] b, int off, byte[] m) {
        for (int i = 0; i < 6; i++) if (b[off + i] != m[i]) return false;
        return true;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
