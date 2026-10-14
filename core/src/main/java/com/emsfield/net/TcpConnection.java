package com.emsfield.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 앱 내장 TCP 클라이언트 연결 (능동 연결 전용).
 * <p>
 * Modbus TCP 처럼 짧은 요청/응답이 오가는 용도에 맞춘 최소 구현:
 * 3-way handshake, MSS 협상, 재전송(지수 백오프), 순서대로 도착한 데이터 수신, FIN/RST 처리.
 * 순서가 어긋난 세그먼트는 버리고 중복 ACK 로 재전송을 유도한다.
 */
public final class TcpConnection implements ByteTransport {

    static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10;

    private static final int RECV_CAP = 16 * 1024;
    private static final int SEND_CAP = 64 * 1024;
    private static final int OUR_MSS = 1460;
    private static final int RTO_INITIAL_MS = 250;
    private static final int RTO_MAX_MS = 4000;
    private static final int MAX_RETRIES = 6;
    private static final int CLOSE_WAIT_MS = 1000;

    enum State { SYN_SENT, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2, CLOSE_WAIT, LAST_ACK, CLOSED }

    private final NetStack stack;
    final int remoteIp;
    final int remotePort;
    final int localPort;
    private final int iss;

    private State state = State.SYN_SENT;
    private int sndUna, sndNxt, rcvNxt;
    private int sndWnd = 1024;
    private int mss = 536;

    /** sndUna 부터 시작하는 전송 대기 + 미확인 데이터 */
    private byte[] sendBuf = new byte[4096];
    private int sendLen;
    private final byte[] recvBuf = new byte[RECV_CAP];
    private int recvLen;

    private boolean finQueued, finSent, finAcked, remoteFin;
    private String error;
    private int retries;
    private int rto = RTO_INITIAL_MS;
    private ScheduledFuture<?> rtxTimer;

    TcpConnection(NetStack stack, int remoteIp, int remotePort, int localPort, int iss) {
        this.stack = stack;
        this.remoteIp = remoteIp;
        this.remotePort = remotePort;
        this.localPort = localPort;
        this.iss = iss;
        this.sndUna = iss;
        this.sndNxt = iss + 1;
    }

    // ------------------------------------------------------------------ connect / close

    synchronized void connect(int timeoutMs) throws IOException {
        sendSyn();
        scheduleRtx();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (state == State.SYN_SENT) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                failLocked("연결 시간 초과");
                throw new SocketTimeoutException("TCP 연결 시간 초과: " + Ip4.str(remoteIp) + ":" + remotePort
                        + " (포트가 열려 있는지, 방화벽/장비 설정 확인)");
            }
            waitQuietly(remain);
        }
        if (state != State.ESTABLISHED) {
            throw new ConnectException(error != null ? error : "연결 실패");
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (state == State.CLOSED) return;
            if (state == State.SYN_SENT) {
                abortLocked("사용자 종료");
                return;
            }
            if (state == State.ESTABLISHED) {
                state = State.FIN_WAIT_1;
                finQueued = true;
            } else if (state == State.CLOSE_WAIT) {
                state = State.LAST_ACK;
                finQueued = true;
            }
            trySendLocked();
            long deadline = System.currentTimeMillis() + CLOSE_WAIT_MS;
            while (state != State.CLOSED) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                waitQuietly(remain);
            }
            if (state != State.CLOSED) abortLocked(null); // 정상 종료가 안 되면 RST 로 정리
        }
    }

    /** RST 를 보내고 즉시 닫는다. */
    void abort(String reason) {
        synchronized (this) {
            abortLocked(reason);
        }
    }

    // ------------------------------------------------------------------ ByteTransport

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        synchronized (this) {
            if (error != null) throw new IOException(error);
            if (state != State.ESTABLISHED && state != State.CLOSE_WAIT) throw new IOException("연결이 닫혔습니다");
            if (sendLen + len > SEND_CAP) throw new IOException("송신 버퍼 초과 (상대 장비가 수신하지 않음)");
            if (sendLen + len > sendBuf.length) {
                byte[] nb = new byte[Math.max(sendBuf.length * 2, sendLen + len)];
                System.arraycopy(sendBuf, 0, nb, 0, sendLen);
                sendBuf = nb;
            }
            System.arraycopy(b, off, sendBuf, sendLen, len);
            sendLen += len;
            trySendLocked();
        }
    }

    @Override
    public int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
        synchronized (this) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (recvLen == 0) {
                if (error != null) throw new IOException(error);
                if (remoteFin || state == State.CLOSED) return -1;
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) throw new SocketTimeoutException("응답 시간 초과 (" + timeoutMs + " ms)");
                waitQuietly(remain);
            }
            boolean windowWasTight = RECV_CAP - recvLen < mss;
            int n = Math.min(len, recvLen);
            System.arraycopy(recvBuf, 0, b, off, n);
            System.arraycopy(recvBuf, n, recvBuf, 0, recvLen - n);
            recvLen -= n;
            if (windowWasTight && state != State.CLOSED) sendAckLocked(); // 윈도우 갱신 알림
            return n;
        }
    }

    public synchronized boolean isOpen() {
        return state == State.ESTABLISHED;
    }

    // ------------------------------------------------------------------ segment input (수신 스레드)

    void onSegment(byte[] b, int o, int len) {
        int seq = Ip4.read(b, o + 4);
        int ack = Ip4.read(b, o + 8);
        int dataOff = ((b[o + 12] & 0xFF) >>> 4) * 4;
        int flags = b[o + 13] & 0xFF;
        int win = NetStack.u16(b, o + 14);
        if (dataOff < 20 || dataOff > len) return;
        int po = o + dataOff;
        int plen = len - dataOff;

        synchronized (this) {
            if (state == State.CLOSED) return;

            if ((flags & RST) != 0) {
                if (state == State.SYN_SENT) {
                    if ((flags & ACK) != 0 && ack == iss + 1) {
                        failLocked("연결 거부됨(RST): 포트 " + remotePort + " 가 닫혀 있거나 장비가 연결을 거부했습니다");
                    }
                } else if (seq == rcvNxt || inRecvWindow(seq)) {
                    failLocked("상대 장비가 연결을 리셋했습니다(RST)");
                }
                return;
            }

            if (state == State.SYN_SENT) {
                if ((flags & (SYN | ACK)) == (SYN | ACK) && ack == iss + 1) {
                    rcvNxt = seq + 1;
                    sndUna = ack;
                    sndWnd = win;
                    mss = Math.min(OUR_MSS, parseMss(b, o + 20, dataOff - 20));
                    state = State.ESTABLISHED;
                    retries = 0;
                    rto = RTO_INITIAL_MS;
                    cancelRtx();
                    sendAckLocked();
                    notifyAll();
                }
                return;
            }

            if ((flags & ACK) != 0) processAckLocked(ack, win);

            if (plen > 0) {
                if (seq == rcvNxt && !remoteFin) {
                    int take = Math.min(plen, RECV_CAP - recvLen);
                    System.arraycopy(b, po, recvBuf, recvLen, take);
                    recvLen += take;
                    rcvNxt += take;
                }
                sendAckLocked(); // 순서 어긋남이면 중복 ACK
                notifyAll();
            }

            if ((flags & FIN) != 0) {
                if (!remoteFin && seq + plen == rcvNxt) {
                    rcvNxt += 1;
                    remoteFin = true;
                    sendAckLocked();
                    if (state == State.ESTABLISHED) {
                        state = State.CLOSE_WAIT;
                    } else if (state == State.FIN_WAIT_1 || state == State.FIN_WAIT_2) {
                        state = State.CLOSED;
                        finishLocked();
                    }
                    notifyAll();
                } else if (remoteFin) {
                    sendAckLocked(); // 재전송된 FIN
                }
            }
        }
    }

    private void processAckLocked(int ack, int win) {
        sndWnd = win;
        int acked = ack - sndUna;
        int outstanding = sndNxt - sndUna;
        if (acked > 0 && acked <= outstanding) {
            int dataAcked = acked;
            boolean finJustAcked = false;
            if (finSent && !finAcked && ack == sndNxt) {
                dataAcked -= 1;
                finAcked = true;
                finJustAcked = true;
            }
            dataAcked = Math.min(dataAcked, sendLen);
            System.arraycopy(sendBuf, dataAcked, sendBuf, 0, sendLen - dataAcked);
            sendLen -= dataAcked;
            sndUna = ack;
            retries = 0;
            rto = RTO_INITIAL_MS;
            if (sndUna == sndNxt) cancelRtx();
            else scheduleRtx();
            if (finJustAcked) {
                if (state == State.FIN_WAIT_1) {
                    state = State.FIN_WAIT_2;
                } else if (state == State.LAST_ACK) {
                    state = State.CLOSED;
                    finishLocked();
                }
            }
            notifyAll();
        }
        trySendLocked();
    }

    // ------------------------------------------------------------------ output

    private void trySendLocked() {
        if (state == State.CLOSED || state == State.SYN_SENT) return;
        while (true) {
            int inFlightData = inFlightData();
            int unsent = sendLen - inFlightData;
            if (unsent <= 0) break;
            int wndAvail = sndWnd - (sndNxt - sndUna);
            if (wndAvail <= 0) {
                if (rtxTimer == null) scheduleRtx(); // 윈도우 0 → 타이머로 다시 시도
                break;
            }
            int n = Math.min(unsent, Math.min(mss, wndAvail));
            segment(sndNxt, ACK | PSH, sendBuf, inFlightData, n);
            sndNxt += n;
            if (rtxTimer == null) scheduleRtx();
        }
        int inFlightData = inFlightData();
        if (finQueued && !finSent && inFlightData == sendLen) {
            segment(sndNxt, FIN | ACK, null, 0, 0);
            sndNxt += 1;
            finSent = true;
            if (rtxTimer == null) scheduleRtx();
        }
    }

    private void onRetransmitTimer() {
        synchronized (this) {
            rtxTimer = null;
            if (state == State.CLOSED) return;
            int inFlightData = inFlightData();
            boolean pendingUnsent = sendLen > inFlightData;
            if (state != State.SYN_SENT && sndNxt == sndUna && !pendingUnsent) return;
            if (++retries > MAX_RETRIES) {
                abortLocked("응답 없음: 재전송 " + MAX_RETRIES + "회 실패 (케이블/장비 상태 확인)");
                return;
            }
            rto = Math.min(rto * 2, RTO_MAX_MS);
            if (state == State.SYN_SENT) {
                sendSyn();
            } else if (inFlightData > 0) {
                segment(sndUna, ACK | PSH, sendBuf, 0, Math.min(inFlightData, mss));
            } else if (finSent && sndNxt - sndUna == 1) {
                segment(sndNxt - 1, FIN | ACK, null, 0, 0);
            } else if (pendingUnsent && sndWnd == 0) {
                segment(sndNxt, ACK, sendBuf, inFlightData, 1); // 윈도우 프로브 (1바이트)
                sndNxt += 1;
            }
            scheduleRtx();
        }
    }

    private void sendSyn() {
        byte[] mssOpt = {2, 4, (byte) (OUR_MSS >>> 8), (byte) OUR_MSS};
        try {
            stack.sendTcp(remoteIp, localPort, remotePort, iss, 0, SYN, window(), mssOpt, null, 0, 0);
        } catch (IOException e) {
            stack.log("SYN 전송 실패: " + e.getMessage());
        }
    }

    private void sendAckLocked() {
        segment(sndNxt, ACK, null, 0, 0);
    }

    private void segment(int seq, int flags, byte[] data, int off, int len) {
        try {
            stack.sendTcp(remoteIp, localPort, remotePort, seq, rcvNxt, flags, window(), null, data, off, len);
        } catch (IOException e) {
            // ARP 미해결 등 → 재전송 타이머가 다시 시도
            stack.log("TCP 세그먼트 전송 보류: " + e.getMessage());
        }
    }

    /** 전송했지만 아직 ACK 받지 못한 데이터 바이트 수 (FIN 은 제외) */
    private int inFlightData() {
        return sndNxt - sndUna - (finSent && !finAcked ? 1 : 0);
    }

    private int window() {
        return RECV_CAP - recvLen;
    }

    private void scheduleRtx() {
        cancelRtx();
        try {
            rtxTimer = stack.timer().schedule(this::onRetransmitTimer, rto, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // 스택 종료 중
        }
    }

    private void cancelRtx() {
        if (rtxTimer != null) {
            rtxTimer.cancel(false);
            rtxTimer = null;
        }
    }

    private void abortLocked(String reason) {
        if (state == State.CLOSED) return;
        if (state != State.SYN_SENT) segment(sndNxt, RST | ACK, null, 0, 0);
        if (reason != null && error == null) error = reason;
        state = State.CLOSED;
        finishLocked();
    }

    private void failLocked(String msg) {
        if (error == null) error = msg;
        state = State.CLOSED;
        finishLocked();
    }

    private void finishLocked() {
        cancelRtx();
        stack.unregister(this);
        notifyAll();
    }

    private boolean inRecvWindow(int seq) {
        int d = seq - rcvNxt;
        return d >= 0 && d < window();
    }

    private static int parseMss(byte[] b, int o, int len) {
        int i = 0;
        while (i < len) {
            int kind = b[o + i] & 0xFF;
            if (kind == 0) break;
            if (kind == 1) {
                i++;
                continue;
            }
            if (i + 1 >= len) break;
            int l = b[o + i + 1] & 0xFF;
            if (l < 2) break;
            if (kind == 2 && l == 4 && i + 3 < len) return NetStack.u16(b, o + i + 2);
            i += l;
        }
        return 536;
    }

    private void waitQuietly(long ms) {
        try {
            wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "TCP " + Ip4.str(remoteIp) + ":" + remotePort + " (local " + localPort + ", " + state + ")";
    }
}
