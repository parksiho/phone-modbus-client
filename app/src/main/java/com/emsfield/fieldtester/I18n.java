package com.emsfield.fieldtester;

import java.util.HashMap;
import java.util.Map;

/** 디자인의 KO/EN 문자열 표 (Modbus Client.dc.html Component.T) */
final class I18n {
    private I18n() {}

    private static final Map<String, String[]> T = new HashMap<>();

    private static void p(String k, String ko, String en) {
        T.put(k, new String[]{ko, en});
    }

    static {
        p("appName", "모드버스 체커", "Modbus Checker");
        p("title", "Modbus 클라이언트", "Modbus Client");
        p("secAdapter", "USB 어댑터", "USB adapter");
        p("usbInfo", "USB 정보", "USB info");
        p("connect", "연결", "Connect");
        p("connecting", "연결 중…", "Connecting…");
        p("disconnect", "해제", "Release");
        p("adNone", "어댑터 연결 안 됨", "No adapter connected");
        p("adUp", "연결됨", "Connected");
        p("secIp", "앱 IP", "App IP");
        p("ipNote", "폰 네트워크와 별개로 USB 어댑터에만 적용됩니다.", "Applies to the USB adapter only, separate from the phone network.");
        p("ipAddr", "IP 주소", "IP address");
        p("gw", "게이트웨이", "Gateway");
        p("optional", "선택", "Optional");
        p("apply", "IP 적용 · 충돌 검사", "Apply IP · conflict check");
        p("checking", "검사 중…", "Checking…");
        p("ipOk", "적용됨 · ARP 충돌 없음", "Applied · no ARP conflict");
        p("ipConflict", "충돌: 같은 IP를 쓰는 장비가 있습니다", "Conflict: another device uses this IP");
        p("ipBad", "잘못된 IP 형식", "Invalid IP format");
        p("ipNotSet", "미적용", "Not applied");
        p("secTarget", "대상 장비", "Target device");
        p("port", "포트", "Port");
        p("portChk", "포트 확인", "Port check");
        p("secReq", "Modbus TCP 요청", "Modbus TCP request");
        p("start", "시작 주소", "Start address");
        p("count", "개수", "Quantity");
        p("countWords", "개수 (값)", "Quantity (values)");
        p("value", "값", "Value");
        p("values", "값 (쉼표 구분)", "Values (comma-separated)");
        p("type", "데이터 형식", "Data type");
        p("order", "바이트 순서", "Byte order");
        p("run", "실행", "Send");
        p("poll", "폴링 시작", "Start polling");
        p("stop", "폴링 중지", "Stop polling");
        p("interval", "폴링 주기", "Poll interval");
        p("secRes", "응답", "Response");
        p("polling", "폴링 중", "Polling");
        p("addr", "주소", "Addr");
        p("state", "상태", "State");
        p("noRes", "아직 응답이 없습니다.", "No response yet.");
        p("noResHint", "요청을 실행하면 레지스터가 여기에 표시됩니다.", "Send a request to see registers here.");
        p("tapReg", "행을 눌러 값 쓰기 (FC06/FC16)", "Tap a row to write (FC06/FC16)");
        p("tapCoil", "행을 눌러 ON/OFF 전환 (FC05)", "Tap a row to toggle (FC05)");
        p("readOnly", "읽기 전용", "Read-only");
        p("secLog", "로그", "Log");
        p("all", "전체", "All");
        p("errors", "오류", "Errors");
        p("clear", "지우기", "Clear");
        p("logEmpty", "로그가 없습니다.", "Log is empty.");
        p("writeReg", "레지스터 쓰기", "Write register");
        p("cancel", "취소", "Cancel");
        p("send", "전송", "Send");
        p("needAdapter", "먼저 USB 어댑터를 연결하세요.", "Connect the USB adapter first.");
        p("needIp", "먼저 앱 IP를 적용하세요.", "Apply the app IP first.");
        p("setup", "설정", "Setup");
        p("notChecked", "미확인", "Not checked");
        p("noAdapter", "어댑터 없음", "No adapter");
        p("entries", "건", "entries");
        p("newVal", "새 값", "New value");
        p("mMain", "Modbus 클라이언트", "Modbus client");
        p("mPing", "Ping 테스트", "Ping test");
        p("running", "실행 중", "Running");
        p("pTarget", "대상 IP", "Target IP");
        p("pSize", "크기 (B)", "Size (B)");
        p("pCount", "횟수", "Count");
        p("pInterval", "간격", "Interval");
        p("pStart", "Ping 시작", "Start ping");
        p("pStop", "중지", "Stop");
        p("pSent", "송신", "Sent");
        p("pRecv", "수신", "Recv");
        p("pLoss", "손실", "Loss");
        p("pAvg", "평균", "Avg");
        p("pEmpty", "시작을 누르면 응답이 여기에 표시됩니다.", "Tap start to see replies here.");
        p("pTimeout", "요청 시간 초과", "Request timeout");
        p("pUsePort", "포트 포함", "Include port");
        p("pUsePortSub", "TCP 연결로 IP:포트 응답 확인", "TCP connect to IP:port");
        p("pRefused", "연결 거부", "Connection refused");
        p("pConnTimeout", "연결 시간 초과", "Connection timeout");
        p("phoneNet", "폰 인터넷", "Phone internet");
        p("netOk", "정상", "OK");
        p("netChecking", "확인 중", "Checking");
        p("netNone", "연결 없음", "No connection");
        p("netWifi", "Wi‑Fi", "Wi‑Fi");
        p("netCell", "모바일 데이터", "Mobile data");
        p("netEth", "이더넷 (⚠ OS가 어댑터 사용 중)", "Ethernet (⚠ OS owns the adapter)");
        p("netOther", "기타", "Other");
    }

    static String get(String lang, String key) {
        String[] v = T.get(key);
        if (v == null) return key;
        return "en".equals(lang) ? v[1] : v[0];
    }

    /** 기능 코드 목록 (Component.FN) */
    static final String[][] FN = {
            {"01", "01 Coils 읽기", "01 Read Coils"},
            {"02", "02 Discrete Inputs 읽기", "02 Read Discrete Inputs"},
            {"03", "03 Holding Registers 읽기", "03 Read Holding Registers"},
            {"04", "04 Input Registers 읽기", "04 Read Input Registers"},
            {"05", "05 Coil 쓰기 (단일)", "05 Write Single Coil"},
            {"06", "06 Register 쓰기 (단일)", "06 Write Single Register"},
            {"0F", "15 Coils 쓰기 (다중)", "15 Write Multiple Coils"},
            {"10", "16 Registers 쓰기 (다중)", "16 Write Multiple Registers"},
    };

    static final String[] EXC_EN = {"", "Illegal Function", "Illegal Data Address", "Illegal Data Value",
            "Server Device Failure", "Acknowledge", "Server Device Busy", "", "", "", "Gateway Path Unavailable",
            "Gateway Target Failed to Respond"};

    static String exception(int code) {
        return code >= 0 && code < EXC_EN.length && !EXC_EN[code].isEmpty() ? EXC_EN[code] : "Unknown";
    }
}
