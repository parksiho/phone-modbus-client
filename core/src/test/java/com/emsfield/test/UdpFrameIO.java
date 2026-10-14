package com.emsfield.test;

import com.emsfield.net.FrameIO;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/** PC 검증용: tools/tap_bridge.py 와 UDP 로 이더넷 프레임을 주고받는다. */
public final class UdpFrameIO implements FrameIO {
    private final DatagramSocket socket;
    private final InetSocketAddress bridge;
    private final byte[] mac;

    public UdpFrameIO(int localPort, int bridgePort, byte[] mac) throws IOException {
        this.socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort));
        this.bridge = new InetSocketAddress(InetAddress.getLoopbackAddress(), bridgePort);
        this.mac = mac.clone();
    }

    @Override
    public void send(byte[] frame, int length) throws IOException {
        socket.send(new DatagramPacket(frame, length, bridge));
    }

    @Override
    public int receive(byte[] buffer, int timeoutMs) throws IOException {
        socket.setSoTimeout(Math.max(1, timeoutMs));
        DatagramPacket p = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(p);
            return p.getLength();
        } catch (SocketTimeoutException e) {
            return 0;
        }
    }

    @Override
    public byte[] macAddress() {
        return mac.clone();
    }

    @Override
    public String describe() {
        return "UDP-TAP 브리지 " + bridge;
    }

    @Override
    public void close() {
        socket.close();
    }
}
