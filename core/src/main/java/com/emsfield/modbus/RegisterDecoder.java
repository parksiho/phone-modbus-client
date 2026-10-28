package com.emsfield.modbus;

/**
 * 레지스터 값을 계측값으로 해석한다. 전력량계 제조사마다 바이트/워드 순서가 달라 순서 선택이 필수다.
 */
public final class RegisterDecoder {

    public enum DataType {
        INT16(1), UINT16(1), INT32(2), UINT32(2), FLOAT32(2), FLOAT64(4);

        public final int registers;

        DataType(int registers) {
            this.registers = registers;
        }
    }

    /** A=최상위 바이트. ABCD=빅엔디안(Modbus 표준), CDAB=워드 스왑, BADC=바이트 스왑, DCBA=리틀엔디안 */
    public enum ByteOrder { ABCD, CDAB, BADC, DCBA }

    private RegisterDecoder() {}

    public static double decode(int[] regs, int index, DataType type, ByteOrder order) {
        if (index < 0 || index + type.registers > regs.length) {
            throw new IllegalArgumentException(type + " 해석에 레지스터 " + type.registers + "개 필요");
        }
        byte[] raw = toBytes(regs, index, type.registers, order);
        long v = 0;
        for (byte b : raw) v = (v << 8) | (b & 0xFF);
        switch (type) {
            case INT16: return (short) v;
            case UINT16: return v & 0xFFFF;
            case INT32: return (int) v;
            case UINT32: return v & 0xFFFFFFFFL;
            case FLOAT32: return Float.intBitsToFloat((int) v);
            case FLOAT64: return Double.longBitsToDouble(v);
            default: throw new IllegalStateException();
        }
    }

    /**
     * 값을 레지스터 워드로 변환 (쓰기용). decode 의 역함수.
     * 정수형은 범위를 벗어나면 하위 비트만 남는다(장비 쓰기 전 미리보기로 확인).
     */
    public static int[] encode(double value, DataType type, ByteOrder order) {
        long bits;
        switch (type) {
            case INT16:
            case UINT16: bits = ((long) value) & 0xFFFFL; break;
            case INT32:
            case UINT32: bits = ((long) value) & 0xFFFFFFFFL; break;
            case FLOAT32: bits = Float.floatToIntBits((float) value) & 0xFFFFFFFFL; break;
            case FLOAT64: bits = Double.doubleToLongBits(value); break;
            default: throw new IllegalStateException();
        }
        int n = type.registers;
        int[] be = new int[n]; // 빅엔디안(ABCD) 워드
        for (int i = 0; i < n; i++) be[i] = (int) ((bits >>> ((n - 1 - i) * 16)) & 0xFFFF);
        byte[] raw = toBytes(be, 0, n, order); // 순서 변환은 자기 역함수
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = ((raw[i * 2] & 0xFF) << 8) | (raw[i * 2 + 1] & 0xFF);
        return out;
    }

    /** 레지스터들을 지정 순서를 적용한 빅엔디안 바이트열로 변환 */
    static byte[] toBytes(int[] regs, int index, int count, ByteOrder order) {
        byte[] b = new byte[count * 2];
        boolean wordSwap = order == ByteOrder.CDAB || order == ByteOrder.DCBA;
        boolean byteSwap = order == ByteOrder.BADC || order == ByteOrder.DCBA;
        for (int i = 0; i < count; i++) {
            int r = regs[index + (wordSwap ? count - 1 - i : i)];
            int hi = (r >>> 8) & 0xFF;
            int lo = r & 0xFF;
            b[i * 2] = (byte) (byteSwap ? lo : hi);
            b[i * 2 + 1] = (byte) (byteSwap ? hi : lo);
        }
        return b;
    }
}
