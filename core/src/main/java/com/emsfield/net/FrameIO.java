package com.emsfield.net;

import java.io.Closeable;
import java.io.IOException;

/**
 * 원시 이더넷 프레임 송수신 추상화.
 * <p>
 * - Android 실기기: {@code UsbCdcEcmFrameIO} (USB 어댑터를 앱이 직접 점유)
 * - PC 검증: {@code UdpFrameIO} (리눅스 TAP 브리지)
 * <p>
 * 이 인터페이스 아래만 바꾸면 네트워크 스택과 Modbus 로직은 그대로 재사용된다.
 */
public interface FrameIO extends Closeable {

    /** 이더넷 프레임(목적지 MAC부터, FCS 제외)을 전송한다. */
    void send(byte[] frame, int length) throws IOException;

    /**
     * 프레임 하나를 수신한다.
     *
     * @return 수신 바이트 수, 타임아웃이면 0
     */
    int receive(byte[] buffer, int timeoutMs) throws IOException;

    /** 이 인터페이스가 사용할 출발지 MAC 주소(6바이트). */
    byte[] macAddress();

    /** 사람이 읽을 수 있는 인터페이스 설명 (로그용). */
    String describe();
}
