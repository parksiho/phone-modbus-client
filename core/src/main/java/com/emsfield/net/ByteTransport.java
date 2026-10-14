package com.emsfield.net;

import java.io.Closeable;
import java.io.IOException;

/**
 * 바이트 스트림 연결 추상화. Modbus 클라이언트는 이 인터페이스만 사용한다.
 * <p>
 * - {@link TcpConnection}: 앱 내장 TCP (정식 방식, 앱 자체 IP)
 * - {@link SocketTransport}: java.net.Socket (OS 네트워크 사용 시)
 */
public interface ByteTransport extends Closeable {
    void write(byte[] b, int off, int len) throws IOException;

    /**
     * @return 읽은 바이트 수, 상대가 연결을 닫았으면 -1
     * @throws java.net.SocketTimeoutException 타임아웃
     */
    int read(byte[] b, int off, int len, int timeoutMs) throws IOException;
}
