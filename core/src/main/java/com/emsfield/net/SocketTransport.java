package com.emsfield.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** OS 네트워크 스택(java.net.Socket)을 쓰는 연결. 비교 테스트용. */
public final class SocketTransport implements ByteTransport {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public SocketTransport(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public static SocketTransport connect(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        return new SocketTransport(s);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        out.flush();
    }

    @Override
    public int read(byte[] b, int off, int len, int timeoutMs) throws IOException {
        socket.setSoTimeout(Math.max(1, timeoutMs));
        return in.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
