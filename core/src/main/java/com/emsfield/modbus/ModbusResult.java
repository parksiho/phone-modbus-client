package com.emsfield.modbus;

import com.emsfield.net.Hex;

/** Modbus 트랜잭션 1건의 결과. 현장 진단을 위해 원시 프레임과 응답시간을 함께 담는다. */
public final class ModbusResult {
    public final int functionCode;
    public final byte[] request;
    public final byte[] response;
    public final double rttMs;
    /** 0 이면 정상, 그 외는 Modbus 예외 코드 */
    public final int exceptionCode;
    public final int[] registers;
    public final boolean[] bits;

    ModbusResult(int functionCode, byte[] request, byte[] response, double rttMs,
                 int exceptionCode, int[] registers, boolean[] bits) {
        this.functionCode = functionCode;
        this.request = request;
        this.response = response;
        this.rttMs = rttMs;
        this.exceptionCode = exceptionCode;
        this.registers = registers;
        this.bits = bits;
    }

    public boolean ok() {
        return exceptionCode == 0;
    }

    public String exceptionText() {
        return describeException(exceptionCode);
    }

    public static String describeException(int code) {
        switch (code) {
            case 0: return "정상";
            case 1: return "01 Illegal Function - 장비가 이 기능 코드를 지원하지 않음";
            case 2: return "02 Illegal Data Address - 주소 범위 오류 (0/1-based 오프셋 확인)";
            case 3: return "03 Illegal Data Value - 개수/값 범위 오류";
            case 4: return "04 Server Device Failure - 장비 내부 오류";
            case 5: return "05 Acknowledge - 처리 중";
            case 6: return "06 Server Device Busy - 장비 바쁨";
            case 10: return "0A Gateway Path Unavailable - 게이트웨이 경로 없음";
            case 11: return "0B Gateway Target Failed to Respond - 게이트웨이 하위 장비 무응답 (Unit ID 확인)";
            default: return String.format("%02X 알 수 없는 예외", code);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("FC%02d %s (%.2f ms)%n", functionCode, ok() ? "OK" : "예외 " + exceptionText(), rttMs));
        sb.append("TX: ").append(Hex.of(request)).append('\n');
        sb.append("RX: ").append(Hex.of(response));
        return sb.toString();
    }
}
