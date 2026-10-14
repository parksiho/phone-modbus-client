package com.emsfield.fieldtester;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.util.Locale;

/**
 * USB 이더넷 어댑터 식별. 칩셋에 따라 필요한 드라이버가 다르므로
 * 실기기에서 VID/PID 와 인터페이스 구성을 그대로 보여준다.
 */
public final class UsbAdapterInfo {

    static final int CDC_COMM_CLASS = 0x02;
    static final int CDC_DATA_CLASS = 0x0A;
    static final int CDC_SUBCLASS_ECM = 0x06;
    static final int CDC_SUBCLASS_NCM = 0x0D;

    private UsbAdapterInfo() {}

    /** 알려진 USB 이더넷 칩셋 이름 (모르면 null) */
    public static String knownChip(UsbDevice d) {
        int vid = d.getVendorId();
        int pid = d.getProductId();
        if (vid == 0x0BDA) {
            switch (pid) {
                case 0x8152: return "Realtek RTL8152 (100Mbps)";
                case 0x8153: return "Realtek RTL8153 (1Gbps)";
                case 0x8155:
                case 0x8156: return "Realtek RTL8156 (2.5Gbps)";
                case 0x8157: return "Realtek RTL8157 (5Gbps)";
                default: return null;
            }
        }
        if (vid == 0x0B95) {
            switch (pid) {
                case 0x1790: return "ASIX AX88179 (1Gbps)";
                case 0x178A: return "ASIX AX88178A (1Gbps)";
                case 0x772B:
                case 0x7720:
                case 0x772A: return "ASIX AX88772 계열 (100Mbps)";
                default: return null;
            }
        }
        return null;
    }

    /** 이 장치에 CDC-ECM 구성이 있는지 (현재 PoC 드라이버가 지원하는 모드) */
    public static boolean hasEcm(UsbDevice d) {
        return findConfig(d, CDC_SUBCLASS_ECM) != null;
    }

    public static boolean hasNcm(UsbDevice d) {
        return findConfig(d, CDC_SUBCLASS_NCM) != null;
    }

    static UsbConfiguration findConfig(UsbDevice d, int cdcSubclass) {
        for (int c = 0; c < d.getConfigurationCount(); c++) {
            UsbConfiguration cfg = d.getConfiguration(c);
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface intf = cfg.getInterface(i);
                if (intf.getInterfaceClass() == CDC_COMM_CLASS && intf.getInterfaceSubclass() == cdcSubclass) {
                    return cfg;
                }
            }
        }
        return null;
    }

    public static String describe(UsbDevice d) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "장치: %s%n", d.getDeviceName()));
        sb.append(String.format(Locale.US, "VID:PID = %04X:%04X%n", d.getVendorId(), d.getProductId()));
        sb.append("제조사: ").append(nz(d.getManufacturerName())).append('\n');
        sb.append("제품명: ").append(nz(d.getProductName())).append('\n');
        String chip = knownChip(d);
        sb.append("칩셋 추정: ").append(chip != null ? chip : "알 수 없음 (VID:PID 를 개발자에게 전달)").append('\n');
        sb.append("CDC-ECM 지원: ").append(hasEcm(d) ? "예 → 현재 PoC 드라이버로 사용 가능" : "아니오").append('\n');
        sb.append("CDC-NCM 지원: ").append(hasNcm(d) ? "예" : "아니오").append('\n');
        sb.append("구성 수: ").append(d.getConfigurationCount()).append('\n');
        for (int c = 0; c < d.getConfigurationCount(); c++) {
            UsbConfiguration cfg = d.getConfiguration(c);
            sb.append(String.format(Locale.US, " [구성 #%d id=%d] 인터페이스 %d개%n", c, cfg.getId(), cfg.getInterfaceCount()));
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface intf = cfg.getInterface(i);
                sb.append(String.format(Locale.US, "   IF%d alt%d class=%02X sub=%02X proto=%02X %s%n",
                        intf.getId(), intf.getAlternateSetting(), intf.getInterfaceClass(),
                        intf.getInterfaceSubclass(), intf.getInterfaceProtocol(), className(intf)));
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    sb.append(String.format(Locale.US, "     EP %02X %s %s max=%d%n", ep.getAddress(),
                            ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN " : "OUT",
                            epType(ep.getType()), ep.getMaxPacketSize()));
                }
            }
        }
        return sb.toString();
    }

    private static String className(UsbInterface intf) {
        int c = intf.getInterfaceClass();
        int s = intf.getInterfaceSubclass();
        if (c == CDC_COMM_CLASS && s == CDC_SUBCLASS_ECM) return "(CDC-ECM 제어)";
        if (c == CDC_COMM_CLASS && s == CDC_SUBCLASS_NCM) return "(CDC-NCM 제어)";
        if (c == CDC_DATA_CLASS) return "(CDC 데이터)";
        if (c == 0xFF) return "(제조사 전용)";
        return "";
    }

    private static String epType(int t) {
        switch (t) {
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "INT ";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "ISOC";
            default: return "CTRL";
        }
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }
}
