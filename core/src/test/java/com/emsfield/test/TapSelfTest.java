package com.emsfield.test;

import com.emsfield.modbus.ModbusResult;
import com.emsfield.modbus.ModbusTcpClient;
import com.emsfield.modbus.RegisterDecoder;
import com.emsfield.modbus.RegisterDecoder.ByteOrder;
import com.emsfield.modbus.RegisterDecoder.DataType;
import com.emsfield.net.Hex;
import com.emsfield.net.Ip4;
import com.emsfield.net.IpConfig;
import com.emsfield.net.NetStack;
import com.emsfield.net.TcpConnection;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.Arrays;

/**
 * 가상 장비망(TAP)에서 앱 내장 스택의 동작을 검증한다.
 * 앱 IP 192.168.10.50/24 (OS 에는 존재하지 않는 IP), 가상 전력량계 192.168.10.1:502.
 */
public final class TapSelfTest {
    private static int pass, fail;

    public static void main(String[] args) throws Exception {
        int holdSec = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        byte[] mac = {0x02, 0x45, 0x4D, 0x53, 0x00, 0x50}; // 로컬 관리 MAC
        IpConfig cfg = IpConfig.of("192.168.10.50", 24, "");
        int device = Ip4.parse("192.168.10.1");

        NetStack stack = new NetStack(new UdpFrameIO(47001, 47000, mac), cfg, m -> System.out.println("  [stack] " + m));
        stack.start();

        section("1. IP 충돌 검사");
        check("앱 IP 192.168.10.50 충돌 없음", stack.probeAddressConflict(600) == null);
        stack.setConfig(IpConfig.of("192.168.10.1", 24, ""));
        check("장비와 같은 IP(192.168.10.1) 설정 시 충돌 감지", stack.probeAddressConflict(600) != null);
        stack.setConfig(cfg);
        stack.probeAddressConflict(400);

        section("2. ARP / Ping");
        byte[] devMac = stack.arp(device, 1000);
        check("장비 MAC 확인 " + Hex.mac(devMac), devMac != null);
        double sum = 0, max = 0;
        int ok = 0;
        for (int i = 0; i < 5; i++) {
            NetStack.PingResult r = stack.ping(device, 1000);
            System.out.println("  ping " + (i + 1) + ": " + r);
            if (r.ok) {
                ok++;
                sum += r.rttMs;
                max = Math.max(max, r.rttMs);
            }
        }
        check(String.format("Ping 5/5 성공 (평균 %.2f ms, 최대 %.2f ms)", sum / Math.max(ok, 1), max), ok == 5);
        expectError("없는 장비(192.168.10.99) ARP 실패 메시지", NoRouteToHostException.class,
                () -> stack.arp(Ip4.parse("192.168.10.99"), 700));
        expectError("대역 밖(10.0.0.5) + 게이트웨이 없음 → 경로 없음", NoRouteToHostException.class,
                () -> stack.connect(Ip4.parse("10.0.0.5"), 502, 500));

        section("3. TCP 포트 점검");
        expectError("닫힌 포트 503 → 연결 거부(RST)", ConnectException.class,
                () -> stack.connect(device, 503, 1000));

        section("4. Modbus TCP (앱 내장 TCP)");
        TcpConnection conn = stack.connect(device, 502, 1500);
        check("포트 502 연결 성공: " + conn, conn.isOpen());
        ModbusTcpClient mb = new ModbusTcpClient(conn);
        mb.setTimeoutMs(1000);

        ModbusResult r = mb.readHoldingRegisters(1, 0, 12);
        System.out.println("  " + r.toString().replace("\n", "\n  "));
        check("FC03 0~11 읽기", r.ok() && r.registers.length == 12);
        double v = RegisterDecoder.decode(r.registers, 0, DataType.FLOAT32, ByteOrder.ABCD);
        double a = RegisterDecoder.decode(r.registers, 2, DataType.FLOAT32, ByteOrder.ABCD);
        double kw = RegisterDecoder.decode(r.registers, 4, DataType.FLOAT32, ByteOrder.ABCD);
        double kwh = RegisterDecoder.decode(r.registers, 10, DataType.UINT32, ByteOrder.ABCD) * 0.01;
        System.out.printf("  해석: 전압 %.1f V, 전류 %.2f A, 유효전력 %.3f kW, 누적 %.2f kWh%n", v, a, kw, kwh);
        check("FLOAT32/UINT32 해석 값 일치", near(v, 220.5) && near(a, 12.25) && near(kw, 2.701) && near(kwh, 12345.67));

        ModbusResult hz = mb.readInputRegisters(1, 20, 2);
        double hzAbcd = RegisterDecoder.decode(hz.registers, 0, DataType.FLOAT32, ByteOrder.ABCD);
        double hzCdab = RegisterDecoder.decode(hz.registers, 0, DataType.FLOAT32, ByteOrder.CDAB);
        System.out.printf("  주파수 ABCD 해석 %.4g / CDAB 해석 %.4g%n", hzAbcd, hzCdab);
        check("FC04 + 워드 스왑(CDAB) 해석 60 Hz", near(hzCdab, 60.0));

        check("FC06 단일 쓰기 100=4321", mb.writeSingleRegister(1, 100, 4321).ok());
        check("쓴 값 다시 읽기 = 4321", mb.readHoldingRegisters(1, 100, 1).registers[0] == 4321);
        check("FC16 다중 쓰기 100~102", mb.writeMultipleRegisters(1, 100, new int[]{1, 2, 65535}).ok());
        check("다중 쓰기 확인 [1, 2, 65535]",
                Arrays.equals(mb.readHoldingRegisters(1, 100, 3).registers, new int[]{1, 2, 65535}));

        ModbusResult coils = mb.readCoils(1, 0, 8);
        check("FC01 코일 읽기 (0,3 ON)", coils.ok() && coils.bits[0] && coils.bits[3] && !coils.bits[1]);
        check("FC05 코일 1 ON", mb.writeSingleCoil(1, 1, true).ok());
        check("FC15 코일 4~6 쓰기", mb.writeMultipleCoils(1, 4, new boolean[]{true, false, true}).ok());
        ModbusResult coils2 = mb.readDiscreteInputs(1, 0, 8);
        check("FC02 확인 (1,4,6 ON / 5 OFF)", coils2.bits[1] && coils2.bits[4] && !coils2.bits[5] && coils2.bits[6]);

        ModbusResult ex = mb.readHoldingRegisters(1, 1000, 2);
        System.out.println("  " + ex.exceptionText());
        check("범위 밖 주소 → 예외 02 해석", ex.exceptionCode == 2);
        check("FC03 최대 125개 읽기", mb.readHoldingRegisters(1, 0, 125).registers.length == 125);

        section("5. 연속 폴링 (현장 안정성)");
        int n = 300, good = 0;
        double total = 0, worst = 0;
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            ModbusResult p = mb.readHoldingRegisters(1, 0, 6);
            if (p.ok()) {
                good++;
                total += p.rttMs;
                worst = Math.max(worst, p.rttMs);
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        check(String.format("%d회 폴링 %d 성공, 평균 %.2f ms, 최대 %.2f ms, 총 %d ms", n, good, total / good, worst, elapsed), good == n);

        conn.close();
        check("정상 종료(FIN) 후 닫힘", !conn.isOpen());

        TcpConnection c2 = stack.connect(device, 502, 1500);
        ModbusTcpClient mb2 = new ModbusTcpClient(c2);
        check("재연결 후 읽기", mb2.readHoldingRegisters(1, 0, 2).ok());
        c2.close();

        if (holdSec > 0) {
            section("6. 장비 → 앱 IP Ping 대기 (" + holdSec + "초)");
            Thread.sleep(holdSec * 1000L);
        }

        System.out.printf("%n결과: PASS %d / FAIL %d  (RX %d, TX %d 프레임)%n", pass, fail, stack.rxFrames(), stack.txFrames());
        stack.close();
        System.exit(fail == 0 ? 0 : 1);
    }

    interface Action {
        void run() throws Exception;
    }

    private static void expectError(String name, Class<? extends Exception> type, Action a) {
        try {
            a.run();
            check(name + " (예외가 발생하지 않음)", false);
        } catch (Exception e) {
            System.out.println("  → " + e.getClass().getSimpleName() + ": " + e.getMessage());
            check(name, type.isInstance(e));
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-3 * Math.max(1, Math.abs(b));
    }

    private static void section(String s) {
        System.out.println("\n== " + s);
    }

    private static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name);
    }

    private TapSelfTest() {}
}
