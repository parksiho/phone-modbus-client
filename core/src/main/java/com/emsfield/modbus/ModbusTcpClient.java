package com.emsfield.modbus;

import com.emsfield.net.ByteTransport;
import com.emsfield.net.Hex;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * Modbus TCP 클라이언트. 전송 계층({@link ByteTransport})과 분리되어 있어
 * 앱 내장 TCP 와 OS 소켓 어디서나 동일하게 동작한다.
 */
public final class ModbusTcpClient implements Closeable {

    public static final int FC_READ_COILS = 0x01;
    public static final int FC_READ_DISCRETE_INPUTS = 0x02;
    public static final int FC_READ_HOLDING_REGISTERS = 0x03;
    public static final int FC_READ_INPUT_REGISTERS = 0x04;
    public static final int FC_WRITE_SINGLE_COIL = 0x05;
    public static final int FC_WRITE_SINGLE_REGISTER = 0x06;
    public static final int FC_WRITE_MULTIPLE_COILS = 0x0F;
    public static final int FC_WRITE_MULTIPLE_REGISTERS = 0x10;

    private final ByteTransport transport;
    private int timeoutMs = 1000;
    private int transactionId;

    public ModbusTcpClient(ByteTransport transport) {
        this.transport = transport;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    // ------------------------------------------------------------------ 읽기

    public ModbusResult readCoils(int unit, int address, int quantity) throws IOException {
        return readBits(unit, FC_READ_COILS, address, quantity);
    }

    public ModbusResult readDiscreteInputs(int unit, int address, int quantity) throws IOException {
        return readBits(unit, FC_READ_DISCRETE_INPUTS, address, quantity);
    }

    public ModbusResult readHoldingRegisters(int unit, int address, int quantity) throws IOException {
        return readRegisters(unit, FC_READ_HOLDING_REGISTERS, address, quantity);
    }

    public ModbusResult readInputRegisters(int unit, int address, int quantity) throws IOException {
        return readRegisters(unit, FC_READ_INPUT_REGISTERS, address, quantity);
    }

    // ------------------------------------------------------------------ 쓰기

    public ModbusResult writeSingleCoil(int unit, int address, boolean on) throws IOException {
        checkAddress(address, 1);
        byte[] pdu = {FC_WRITE_SINGLE_COIL, hi(address), lo(address), (byte) (on ? 0xFF : 0x00), 0};
        return transact(unit, pdu, null);
    }

    public ModbusResult writeSingleRegister(int unit, int address, int value) throws IOException {
        checkAddress(address, 1);
        byte[] pdu = {FC_WRITE_SINGLE_REGISTER, hi(address), lo(address), hi(value), lo(value)};
        return transact(unit, pdu, null);
    }

    public ModbusResult writeMultipleCoils(int unit, int address, boolean[] values) throws IOException {
        checkQuantity(values.length, 1968);
        checkAddress(address, values.length);
        int byteCount = (values.length + 7) / 8;
        byte[] pdu = new byte[6 + byteCount];
        pdu[0] = FC_WRITE_MULTIPLE_COILS;
        pdu[1] = hi(address);
        pdu[2] = lo(address);
        pdu[3] = hi(values.length);
        pdu[4] = lo(values.length);
        pdu[5] = (byte) byteCount;
        for (int i = 0; i < values.length; i++) if (values[i]) pdu[6 + i / 8] |= (byte) (1 << (i % 8));
        return transact(unit, pdu, null);
    }

    public ModbusResult writeMultipleRegisters(int unit, int address, int[] values) throws IOException {
        checkQuantity(values.length, 123);
        checkAddress(address, values.length);
        byte[] pdu = new byte[6 + values.length * 2];
        pdu[0] = FC_WRITE_MULTIPLE_REGISTERS;
        pdu[1] = hi(address);
        pdu[2] = lo(address);
        pdu[3] = hi(values.length);
        pdu[4] = lo(values.length);
        pdu[5] = (byte) (values.length * 2);
        for (int i = 0; i < values.length; i++) {
            pdu[6 + i * 2] = hi(values[i]);
            pdu[7 + i * 2] = lo(values[i]);
        }
        return transact(unit, pdu, null);
    }

    // ------------------------------------------------------------------ 공통

    private ModbusResult readRegisters(int unit, int fc, int address, int quantity) throws IOException {
        checkQuantity(quantity, 125);
        checkAddress(address, quantity);
        byte[] pdu = {(byte) fc, hi(address), lo(address), hi(quantity), lo(quantity)};
        return transact(unit, pdu, new int[]{quantity, 16});
    }

    private ModbusResult readBits(int unit, int fc, int address, int quantity) throws IOException {
        checkQuantity(quantity, 2000);
        checkAddress(address, quantity);
        byte[] pdu = {(byte) fc, hi(address), lo(address), hi(quantity), lo(quantity)};
        return transact(unit, pdu, new int[]{quantity, 1});
    }

    /**
     * @param expect 읽기 요청이면 {개수, 16(레지스터)|1(비트)}, 쓰기면 null
     */
    private synchronized ModbusResult transact(int unit, byte[] pdu, int[] expect) throws IOException {
        if (unit < 0 || unit > 255) throw new IllegalArgumentException("Unit ID 는 0~255");
        int tid = (++transactionId) & 0xFFFF;
        byte[] req = new byte[7 + pdu.length];
        req[0] = hi(tid);
        req[1] = lo(tid);
        req[4] = hi(pdu.length + 1);
        req[5] = lo(pdu.length + 1);
        req[6] = (byte) unit;
        System.arraycopy(pdu, 0, req, 7, pdu.length);

        long t0 = System.nanoTime();
        long deadline = System.currentTimeMillis() + timeoutMs;
        transport.write(req, 0, req.length);

        byte[] resp;
        while (true) {
            byte[] mbap = new byte[7];
            readFully(mbap, deadline);
            int rtid = u16(mbap, 0);
            int pid = u16(mbap, 2);
            int len = u16(mbap, 4);
            if (pid != 0 || len < 2 || len > 254) {
                throw new IOException("잘못된 MBAP 헤더 (Modbus TCP 장비가 맞는지 확인): " + Hex.of(mbap));
            }
            byte[] body = new byte[len - 1];
            readFully(body, deadline);
            if (rtid != tid) continue; // 이전 요청의 늦은 응답은 버림
            resp = new byte[7 + body.length];
            System.arraycopy(mbap, 0, resp, 0, 7);
            System.arraycopy(body, 0, resp, 7, body.length);
            break;
        }
        double rtt = (System.nanoTime() - t0) / 1_000_000.0;

        int rfc = resp[7] & 0xFF;
        int fc = pdu[0] & 0xFF;
        if (rfc == (fc | 0x80)) {
            int ex = resp.length > 8 ? resp[8] & 0xFF : 0xFF;
            return new ModbusResult(fc, req, resp, rtt, ex, null, null);
        }
        if (rfc != fc) throw new IOException(String.format("응답 기능 코드 불일치: 요청 %02X, 응답 %02X", fc, rfc));
        if ((resp[6] & 0xFF) != unit) throw new IOException("응답 Unit ID 불일치: " + (resp[6] & 0xFF));

        if (expect == null) return new ModbusResult(fc, req, resp, rtt, 0, null, null);

        int byteCount = resp[8] & 0xFF;
        if (9 + byteCount > resp.length) throw new IOException("응답 길이 오류");
        int qty = expect[0];
        if (expect[1] == 16) {
            if (byteCount != qty * 2) throw new IOException("레지스터 개수 불일치: 요청 " + qty + ", 응답 " + byteCount / 2);
            int[] regs = new int[qty];
            for (int i = 0; i < qty; i++) regs[i] = u16(resp, 9 + i * 2);
            return new ModbusResult(fc, req, resp, rtt, 0, regs, null);
        }
        if (byteCount < (qty + 7) / 8) throw new IOException("비트 응답 길이 오류");
        boolean[] bits = new boolean[qty];
        for (int i = 0; i < qty; i++) bits[i] = ((resp[9 + i / 8] >> (i % 8)) & 1) != 0;
        return new ModbusResult(fc, req, resp, rtt, 0, null, bits);
    }

    private void readFully(byte[] b, long deadline) throws IOException {
        int got = 0;
        while (got < b.length) {
            int remain = (int) (deadline - System.currentTimeMillis());
            if (remain <= 0) throw new SocketTimeoutException("Modbus 응답 시간 초과 (" + timeoutMs + " ms) - Unit ID/장비 상태 확인");
            int n = transport.read(b, got, b.length - got, remain);
            if (n < 0) throw new IOException("장비가 연결을 닫았습니다 (Unit ID 또는 요청이 잘못되었을 수 있음)");
            got += n;
        }
    }

    private static void checkQuantity(int q, int max) {
        if (q < 1 || q > max) throw new IllegalArgumentException("개수는 1~" + max + " 범위여야 합니다: " + q);
    }

    private static void checkAddress(int address, int quantity) {
        if (address < 0 || address + quantity > 0x10000) {
            throw new IllegalArgumentException("주소 범위 초과 (0~65535): " + address);
        }
    }

    private static byte hi(int v) {
        return (byte) (v >>> 8);
    }

    private static byte lo(int v) {
        return (byte) v;
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }
}
