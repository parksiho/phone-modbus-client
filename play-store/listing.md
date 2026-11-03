# 스토어 등록정보

Play Console 의 기본 언어와 영어 번역에 붙여 넣는다. 글자 수는 공백을 포함한다.
런처 이름은 `app/src/main/res/values/strings.xml` (한국어) 와 `values-en/strings.xml` (영어) 이다.

아직 없는 것: 512×512 아이콘, 1024×500 그래픽 이미지, 휴대폰 스크린샷. 체크리스트를 본다.

## 한국어

### 앱 이름 (13 / 30)

현장 Modbus 테스터

### 간단한 설명 (53 / 80)

USB-C 이더넷으로 계측기 Modbus TCP를 점검합니다. 장비망은 폰 인터넷과 분리됩니다.

### 자세한 설명

현장 Modbus 테스터는 안드로이드 폰과 USB-C 이더넷 어댑터로 전력량계, PLC, 게이트웨이 같은 현장 계측기의 Modbus TCP를 점검하는 도구입니다.

폰의 LTE·Wi-Fi와 장비망을 섞지 않습니다. 앱이 USB 어댑터를 직접 점유하고, 앱 안에만 있는 IP로 ARP, IPv4, ICMP, TCP를 처리합니다. 그 주소는 안드로이드 네트워크 설정에 등록되지 않습니다.

할 수 있는 일

- 앱 IP, 프리픽스, 게이트웨이를 설정하고 IP 충돌을 검사합니다.
- ARP, Ping, TCP 포트 열림을 확인합니다.
- Modbus TCP FC01, 02, 03, 04, 05, 06, 15, 16 을 읽고 씁니다.
- INT16, UINT16, INT32, UINT32, FLOAT32, FLOAT64 와 바이트 순서 ABCD, CDAB, BADC, DCBA 로 값을 해석합니다.
- 주기 폴링, 응답 시간, 원시 HEX 로그를 봅니다.
- USB VID:PID 와 CDC-ECM 지원 여부를 확인합니다.

알아 둘 점

- 쓰기(FC05, FC06, FC15, FC16)는 장비 레지스터를 실제로 바꿉니다. 주소와 값을 확인한 뒤 실행하세요.
- USB 드라이버는 CDC-ECM 어댑터만 지원합니다. ECM 이 없는 칩은 연결되지 않을 수 있습니다.
- USB 호스트(OTG)가 되는 폰이 필요합니다.

화면 하단에 배너 광고가 나올 수 있습니다. 광고 통신은 LTE 또는 Wi-Fi 로만 나가며 USB 장비망으로는 나가지 않습니다. 동의가 필요한 지역에서는 광고 전에 동의 창이 표시될 수 있습니다.

## English

### App name (19 / 30)

Field Modbus Tester

### Short description (80 / 80)

Test Modbus TCP on field instruments over USB-C Ethernet, off the phone network.

### Full description

Field Modbus Tester checks Modbus TCP on field instruments such as power meters, PLCs, and gateways, using an Android phone and a USB-C Ethernet adapter.

The phone's LTE or Wi-Fi stays separate from the equipment network. The app claims the USB adapter itself and runs ARP, IPv4, ICMP, and TCP on an IP that exists only inside the app. That address is not registered as an Android network.

What you can do

- Set the app IP, prefix, and gateway, and check for an IP conflict.
- Run ARP, ping, and a TCP port check.
- Read and write Modbus TCP FC01, 02, 03, 04, 05, 06, 15, and 16.
- Decode INT16, UINT16, INT32, UINT32, FLOAT32, and FLOAT64 with byte orders ABCD, CDAB, BADC, and DCBA.
- Poll on an interval and inspect response time and raw HEX logs.
- View the USB VID:PID and whether the adapter speaks CDC-ECM.

Before you rely on it

- Writes (FC05, FC06, FC15, FC16) change real device registers. Check the address and the value first.
- The USB driver supports CDC-ECM adapters only. Chips without an ECM mode may not connect.
- The phone must support USB host (OTG).

A banner ad may appear at the bottom of the screen. Ad traffic uses LTE or Wi-Fi only and does not go out through the USB equipment network. Where consent is required, a consent form can appear before ads load.
