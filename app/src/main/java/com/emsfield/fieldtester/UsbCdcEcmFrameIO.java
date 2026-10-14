package com.emsfield.fieldtester;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.emsfield.net.FrameIO;
import com.emsfield.net.Hex;
import com.emsfield.net.NetStack;

import java.io.IOException;
import java.security.SecureRandom;

/**
 * USB CDC-ECM 이더넷 어댑터를 앱이 직접 점유하는 유저스페이스 드라이버.
 * <p>
 * 커널 드라이버(r8152, cdc_ether 등)를 분리하고 앱이 벌크 전송으로 이더넷 프레임을 주고받는다.
 * 이 순간부터 안드로이드 OS 는 이 어댑터를 네트워크로 인식하지 않으므로,
 * 폰의 LTE/Wi-Fi 라우팅에는 아무 영향이 없다.
 * <p>
 * 많은 Realtek RTL8153 어댑터는 구성 #1(제조사 전용) 외에 CDC-ECM 구성을 함께 제공한다.
 * 현재 구성이 ECM 이 아니면 ECM 구성으로 전환을 시도한다.
 */
public final class UsbCdcEcmFrameIO implements FrameIO {

    private static final int SET_ETHERNET_PACKET_FILTER = 0x43;
    private static final int PACKET_TYPE_ALL_MULTICAST = 0x02;
    private static final int PACKET_TYPE_DIRECTED = 0x04;
    private static final int PACKET_TYPE_BROADCAST = 0x08;
    private static final int CS_INTERFACE = 0x24;
    private static final int ETHERNET_NETWORKING_FD = 0x0F;

    private final UsbDevice device;
    private final UsbDeviceConnection conn;
    private final UsbInterface commIntf;
    private final UsbInterface dataIntf;
    private final UsbEndpoint in;
    private final UsbEndpoint out;
    private final byte[] mac;
    private final byte[] rxBuf;
    private final NetStack.Logger log;

    private UsbCdcEcmFrameIO(UsbDevice device, UsbDeviceConnection conn, UsbInterface commIntf, UsbInterface dataIntf,
                             UsbEndpoint in, UsbEndpoint out, byte[] mac, NetStack.Logger log) {
        this.device = device;
        this.conn = conn;
        this.commIntf = commIntf;
        this.dataIntf = dataIntf;
        this.in = in;
        this.out = out;
        this.mac = mac;
        this.rxBuf = new byte[Math.max(2048, in.getMaxPacketSize() * 4)];
        this.log = log;
    }

    /** 사용 권한(UsbManager.hasPermission)을 받은 뒤 호출한다. */
    public static UsbCdcEcmFrameIO open(UsbManager manager, UsbDevice device, NetStack.Logger log) throws IOException {
        UsbConfiguration ecmCfg = UsbAdapterInfo.findConfig(device, UsbAdapterInfo.CDC_SUBCLASS_ECM);
        if (ecmCfg == null) {
            String chip = UsbAdapterInfo.knownChip(device);
            throw new IOException("이 어댑터는 CDC-ECM 모드가 없습니다"
                    + (chip != null ? " (" + chip + ")" : "")
                    + ". 칩셋 전용 드라이버가 필요합니다 - USB 정보 화면 내용을 개발자에게 전달하세요.");
        }
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) throw new IOException("USB 장치를 열 수 없습니다 (권한 확인)");
        try {
            selectConfiguration(device, conn, ecmCfg, log);

            UsbInterface comm = null;
            UsbInterface data = null;
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int i = 0; i < ecmCfg.getInterfaceCount(); i++) {
                UsbInterface intf = ecmCfg.getInterface(i);
                if (comm == null && intf.getInterfaceClass() == UsbAdapterInfo.CDC_COMM_CLASS
                        && intf.getInterfaceSubclass() == UsbAdapterInfo.CDC_SUBCLASS_ECM) {
                    comm = intf;
                } else if (intf.getInterfaceClass() == UsbAdapterInfo.CDC_DATA_CLASS && intf.getEndpointCount() >= 2) {
                    UsbEndpoint ein = null;
                    UsbEndpoint eout = null;
                    for (int e = 0; e < intf.getEndpointCount(); e++) {
                        UsbEndpoint ep = intf.getEndpoint(e);
                        if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                        if (ep.getDirection() == UsbConstants.USB_DIR_IN) ein = ep;
                        else eout = ep;
                    }
                    if (ein != null && eout != null) {
                        data = intf; // 엔드포인트가 있는 대체 설정(보통 alt 1)
                        in = ein;
                        out = eout;
                    }
                }
            }
            if (comm == null || data == null) throw new IOException("ECM 인터페이스 구성을 찾지 못했습니다");

            if (!conn.claimInterface(comm, true)) throw new IOException("제어 인터페이스 점유 실패 (IF" + comm.getId() + ")");
            if (!conn.claimInterface(data, true)) throw new IOException("데이터 인터페이스 점유 실패 (IF" + data.getId() + ")");
            if (!conn.setInterface(data)) throw new IOException("데이터 인터페이스 활성화 실패 (alt " + data.getAlternateSetting() + ")");

            int filter = PACKET_TYPE_DIRECTED | PACKET_TYPE_BROADCAST | PACKET_TYPE_ALL_MULTICAST;
            int r = conn.controlTransfer(0x21, SET_ETHERNET_PACKET_FILTER, filter, comm.getId(), null, 0, 1000);
            if (r < 0) log.log("패킷 필터 설정 응답 없음 (일부 장치는 무시해도 동작)");

            byte[] mac = readMac(conn, log);
            log.log("USB ECM 연결: IF" + comm.getId() + "/IF" + data.getId() + ", IN " + Integer.toHexString(in.getAddress())
                    + " OUT " + Integer.toHexString(out.getAddress()) + ", MAC " + Hex.mac(mac)
                    + " → 이 어댑터는 이제 OS 가 아닌 앱이 사용합니다");
            return new UsbCdcEcmFrameIO(device, conn, comm, data, in, out, mac, log);
        } catch (IOException | RuntimeException e) {
            conn.close();
            throw e;
        }
    }

    /**
     * 현재 구성이 ECM 이 아니면 전환한다. 커널 드라이버가 인터페이스를 잡고 있으면
     * 구성 변경이 거부되므로(EBUSY) 먼저 강제 점유로 커널 드라이버를 분리한다.
     */
    private static void selectConfiguration(UsbDevice device, UsbDeviceConnection conn, UsbConfiguration ecmCfg,
                                            NetStack.Logger log) throws IOException {
        if (device.getConfigurationCount() <= 1) return;
        // 현재 활성 구성은 GET_CONFIGURATION 으로 확인
        byte[] cur = new byte[1];
        int n = conn.controlTransfer(0x80, 0x08, 0, 0, cur, 1, 1000);
        int active = n == 1 ? cur[0] & 0xFF : -1;
        if (active == ecmCfg.getId()) {
            log.log("이미 ECM 구성(#" + active + ") 활성 상태");
            return;
        }
        log.log("현재 구성 #" + active + " → ECM 구성 #" + ecmCfg.getId() + " 전환 시도");
        for (int c = 0; c < device.getConfigurationCount(); c++) {
            UsbConfiguration cfg = device.getConfiguration(c);
            if (cfg.getId() != active) continue;
            for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                UsbInterface intf = cfg.getInterface(i);
                if (conn.claimInterface(intf, true)) conn.releaseInterface(intf); // 커널 드라이버 분리
            }
        }
        if (!conn.setConfiguration(ecmCfg)) {
            throw new IOException("ECM 구성으로 전환 실패 - 이 폰의 커널 드라이버가 어댑터를 놓지 않습니다. "
                    + "(PoC 확인 항목: 어댑터를 다시 꽂고 재시도, 안 되면 칩셋 전용 드라이버 필요)");
        }
        log.log("ECM 구성 전환 성공");
    }

    /** ECM 기능 디스크립터의 iMACAddress 문자열에서 MAC 을 읽는다. 실패 시 로컬 관리 MAC 생성. */
    private static byte[] readMac(UsbDeviceConnection conn, NetStack.Logger log) {
        byte[] raw = conn.getRawDescriptors();
        if (raw != null) {
            int i = 0;
            while (i + 2 <= raw.length) {
                int len = raw[i] & 0xFF;
                if (len < 2 || i + len > raw.length) break;
                int type = raw[i + 1] & 0xFF;
                if (type == CS_INTERFACE && len >= 4 && (raw[i + 2] & 0xFF) == ETHERNET_NETWORKING_FD) {
                    int idx = raw[i + 3] & 0xFF;
                    byte[] mac = readMacString(conn, idx);
                    if (mac != null) return mac;
                    break;
                }
                i += len;
            }
        }
        byte[] mac = new byte[6];
        new SecureRandom().nextBytes(mac);
        mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // 로컬 관리, 유니캐스트
        log.log("어댑터 MAC 을 읽지 못해 임시 MAC 사용: " + Hex.mac(mac));
        return mac;
    }

    private static byte[] readMacString(UsbDeviceConnection conn, int index) {
        if (index == 0) return null;
        byte[] buf = new byte[255];
        int n = conn.controlTransfer(0x80, 0x06, (0x03 << 8) | index, 0x0409, buf, buf.length, 1000);
        if (n < 2 + 24) return null;
        StringBuilder hex = new StringBuilder();
        for (int i = 2; i + 1 < n; i += 2) hex.append((char) ((buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8)));
        String s = hex.toString();
        if (s.length() < 12) return null;
        byte[] mac = new byte[6];
        try {
            for (int i = 0; i < 6; i++) mac[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return mac;
    }

    @Override
    public void send(byte[] frame, int length) throws IOException {
        int n = conn.bulkTransfer(out, frame, length, 1000);
        if (n != length) throw new IOException("USB 송신 실패 (" + n + "/" + length + ") - 어댑터 분리 확인");
        if (length % out.getMaxPacketSize() == 0) conn.bulkTransfer(out, new byte[0], 0, 100); // ZLP
    }

    @Override
    public int receive(byte[] buffer, int timeoutMs) {
        int n = conn.bulkTransfer(in, rxBuf, rxBuf.length, timeoutMs);
        if (n <= 0) return 0;
        int len = Math.min(n, buffer.length);
        System.arraycopy(rxBuf, 0, buffer, 0, len);
        return len;
    }

    @Override
    public byte[] macAddress() {
        return mac.clone();
    }

    @Override
    public String describe() {
        return String.format("USB CDC-ECM %04X:%04X", device.getVendorId(), device.getProductId());
    }

    @Override
    public void close() {
        try {
            conn.releaseInterface(dataIntf);
            conn.releaseInterface(commIntf);
        } finally {
            conn.close();
            log.log("USB 어댑터 해제 (OS 가 다시 인식할 수 있음)");
        }
    }
}
