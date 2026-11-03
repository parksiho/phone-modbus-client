# phone-modbus-client

안드로이드 폰에 USB-C 이더넷 어댑터를 꽂아 현장 계측기(전력량계, PLC, 게이트웨이)와 **Modbus TCP 통신을 점검하는 앱**의 PoC.

핵심은 **앱 안에서 IP를 설정하고, 폰의 LTE/Wi-Fi 인터넷과 완전히 분리**하는 것이다.
이를 위해 앱이 USB 어댑터를 OS 대신 직접 점유하고, 앱 내장 네트워크 스택이 자체 IP로 통신한다.

```
[계측기 192.168.10.x] ──RJ45── [USB-C 어댑터] ── 폰
                                      │ (앱이 USB 직접 점유, OS는 어댑터를 모름)
                         ┌────────────┴─────────────┐
                         │ 앱: UsbCdcEcmFrameIO       │  ← USB 벌크 전송으로 이더넷 프레임 송수신
                         │     NetStack (앱 IP)       │  ← ARP / IPv4 / ICMP / TCP
                         │     ModbusTcpClient        │
                         └───────────────────────────┘
폰 OS 네트워크 (LTE / Wi-Fi) ───────────────────────────→ 인터넷 (영향 없음)
```

배너 광고의 HTTP 도 이 LTE/Wi-Fi 경로만 쓴다. USB 장비망으로는 나가지 않는다. 규칙은 아래 광고 섹션.

## 구조

| 모듈 | 내용 |
|---|---|
| `core/` | 순수 Java (안드로이드 의존성 없음). `NetStack`, `TcpConnection`, `ModbusTcpClient`, `RegisterDecoder` |
| `app/` | 안드로이드 앱. `UsbCdcEcmFrameIO`(USB 드라이버), `UsbAdapterInfo`(칩셋 식별), `MainActivity`(PoC 화면), `BannerSlot`(AdMob 배너, LTE/Wi-Fi 전용) |
| `tools/` | PC 검증 도구: TAP 브리지, 가상 전력량계, ICMP ping, 통합 테스트 스크립트 |

`FrameIO` 인터페이스 아래(USB 드라이버)만 바꾸면 위의 스택·Modbus 로직은 그대로 재사용된다.

## 기능 (PoC 범위)

- 앱 내부 IP / prefix / 게이트웨이 설정, 실행 중 변경
- IP 충돌 검사(ARP Probe) + Gratuitous ARP 알림
- ARP, Ping(왕복시간), 포트 열림 확인, 장비 → 앱 IP Ping 응답
- Modbus TCP FC01, 02, 03, 04, 05, 06, 15, 16
- 요청/응답 원시 HEX 프레임, 응답시간, 예외 코드 한글 해석
- 데이터 해석: INT16 / UINT16 / INT32 / UINT32 / FLOAT32 / FLOAT64, 바이트 순서 ABCD / CDAB / BADC / DCBA
- 1초 주기 폴링 + 성공률/평균/최대 응답시간 통계
- USB 장치 정보(VID:PID, 구성/인터페이스/엔드포인트, 칩셋 추정)
- 폰 인터넷 상태 표시 (장비망과 분리 확인용)

## 화면 (Claude Design "Modbus Client" 적용)

Nocturne 다크 디자인 시스템을 안드로이드 View 로 옮겼다 (`app/.../ui/N.java` 에 색상·반경·컴포넌트 토큰).

- **상단 바**: 메뉴(드로어), 제목, `앱 IP → 장비 IP:포트 · Unit` 요약, KO/EN 전환
- **Modbus 클라이언트** (접이식 6개 섹션, 각 헤더에 상태 점 + 한 줄 요약)
  - USB 어댑터: 폰 인터넷 상태, USB 정보 / 연결 / 해제
  - 앱 IP: IP · Prefix · 게이트웨이, IP 적용 · ARP 충돌 검사
  - 대상 장비: IP · 포트 · Unit ID, ARP / Ping / 포트 확인 (결과가 버튼 안에 표시)
  - Modbus TCP 요청: 기능 코드 8종, 시작 주소·개수 / 쓰기 값, 데이터 형식·바이트 순서, 실행 / 폴링(0.5~5s)
  - 응답: HEX/DEC/BIN 원시값, 해석값, Modbus 참조번호(40001 등), 바뀐 값 강조,
    **FC03 행 탭 → 쓰기 시트(FC06/FC16 미리보기)**, **FC01 행 탭 → 코일 ON/OFF 전환**
  - 로그: 전체 / TX·RX / 오류 필터, 원시 프레임 HEX
- **Ping 테스트** (드로어 메뉴): ICMP(크기 0~1472 B) 또는 포트 포함 TCP 연결 확인, 횟수 4/10/∞, 간격 0.2~1s,
  송신·수신·손실·평균 카드, RTT 막대 그래프(최근 36개), min/max/mdev, 응답 라인
- 폰트: Inter(라틴, OFL), 아이콘: Phosphor(MIT) — `app/src/main/assets/fonts`, 라이선스는 `assets/licenses`

디자인 프로토타입과 다른 점 (실제 통신에 맞춘 부분):
- 프로토타입의 `eth1` 표기 대신 `USB` (앱 IP는 OS 인터페이스가 아니라 앱 내부 스택에 있음)
- 숫자 입력이 잘못되면 0을 쓰지 않고 오류 로그만 남김 (현장 장비 오기록 방지)
- Ping은 어댑터 연결 + 앱 IP 적용 후 실제로 송신 (프로토타입은 시뮬레이션)

## PC 검증 (리눅스, root)

실제 장비 없이 TAP 가상 이더넷과 가상 전력량계로 스택 전체를 검증한다.

```bash
sudo tools/run_tap_test.sh
```

검증 결과 (2026-10-28): **30/30 통과**
- 앱 IP `192.168.10.50` 은 OS 인터페이스에 등록되지 않은 상태로 통신 → 분리 확인
- Ping 5/5 (평균 0.2 ms), 장비 → 앱 IP Ping 3/3
- FC01~06, 15, 16 읽기·쓰기, FLOAT32/UINT32/CDAB 해석, 예외 02 처리
- 300회 연속 폴링 300 성공
- Ping 크기 1000 B / 상한 1472 B, TTL 읽기, TCP 연결 후 즉시 RST(TCP ping)
- 값 인코딩(쓰기용) 5개 타입 × 4개 바이트 순서 왕복, FLOAT32 CDAB 쓰기→읽기
- 닫힌 포트 → RST 거부, 없는 장비 → ARP 실패, 대역 밖 → 경로 없음 메시지

USB 드라이버 코드(`UsbCdcEcmFrameIO`)도 가짜 RTL8153(구성#1 제조사 전용 + 구성#2 ECM) 장치로
구성 전환 → 패킷 필터 → MAC 읽기 → Ping → Modbus 읽기까지 확인했다.

## 안드로이드 빌드

Android Studio 에서 이 폴더를 열고 `app` 을 실행한다. (AGP 8.7, compileSdk 35, minSdk 26, Java 11)

`core` 는 외부 라이브러리가 없다. `app` 만 AdMob `play-services-ads` 25.5.0 을 쓴다 (UMP 동의 SDK 포함).

```bash
./gradlew assembleDebug
```

## 광고 (AdMob 배너)

메인 화면 하단에 **고정 적응형 배너 하나**만 둔다. 전면, 앱 오픈, 보상형 광고는 없다.
광고가 없거나 로드에 실패해도 Modbus / Ping / USB 는 그대로 동작한다.

테스트 ID 는 `app/src/main/res/values/ads.xml` 에만 있다. 매니페스트 `APPLICATION_ID` 는 `admob_application_id` 를 참조한다.

| 키 | 현재 값 (Google 테스트) |
|---|---|
| `admob_application_id` | `ca-app-pub-3940256099942544~3347511713` |
| `admob_banner_unit_id` | `ca-app-pub-3940256099942544/9214589741` (적응형 배너) |

순서는 UMP 동의(`requestConsentInfoUpdate` → `loadAndShowConsentFormIfRequired`) → `MobileAds.initialize` → 배너 로드다.
SDK 25.5 의 `MobileAdsInitProvider` 는 앱 ID 형식만 검사하고 소켓을 열지 않는다. 초기화는 네트워크 규칙을 통과한 뒤에만 호출한다.
EEA 밖에서는 AdMob 에 개인정보 메시지를 게시하기 전까지 동의 창이 없는 것이 정상이다. 동의가 필요 없으면 바로 배너를 요청한다.

### 네트워크 규칙

광고, UMP, SDK 초기화의 HTTP 는 **LTE/셀룰러 또는 Wi-Fi** 로만 나간다.

1. 로드 전에 `ConnectivityManager` 로 기본 네트워크를 본다. `TRANSPORT_CELLULAR` 또는 `TRANSPORT_WIFI` 이고 `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED` 일 때만 진행한다.
2. 기본 네트워크가 `TRANSPORT_ETHERNET` 이면 (OS 가 어댑터를 먼저 잡은 경우) 셀룰러/Wi-Fi 를 `requestNetwork` 로 요청한다. 그 네트워크가 같은 조건을 만족할 때만 `bindProcessToNetwork` 로 프로세스 소켓을 묶고, 그 다음에 UMP·초기화·로드를 한다.
3. 조건에 맞는 네트워크가 없으면 배너를 `GONE` 으로 숨기고 요청하지 않는다. 기본 네트워크와 셀룰러/Wi-Fi 의 변화는 `NetworkCallback` 으로 다시 판단한다.
4. 적합한 네트워크를 잃으면 **배너를 먼저 파괴한 다음** 바인딩을 풀어, 갱신 요청이 이더넷으로 새지 않게 한다.

`bindProcessToNetwork` 는 이 프로세스의 **java.net 소켓**만 묶는다. 장비 통신은 `UsbCdcEcmFrameIO.send` / `receive` 가 `UsbDeviceConnection.bulkTransfer` 로 이더넷 프레임을 보내고, `NetStack` / `TcpConnection` 이 그 프레임 위에서 TCP 를 만든다. OS 소켓을 쓰지 않으므로 바인딩과 무관하다. `SocketTransport` 는 OS 소켓 비교용이며 앱 경로에서 쓰지 않는다.

폴링, Modbus 쓰기(FC05/06/15/16), Ping 테스트가 도는 동안에는 `AdView.pause()`, 끝나면 `resume()` 한다. Activity `onResume` / `onPause` / `onDestroy` 에도 연결한다. 배너는 메인 화면에만 보이고, Ping 화면에서는 숨긴 채 멈춘다.

API 31 미만을 포함해, 프로세스를 LTE/Wi-Fi 에 묶으면 기본 네트워크 콜백이 그 네트워크를 가리킨다. 폰 인터넷 줄의 "OS 가 어댑터 사용 중" 은 이더넷 트랜스포트를 따로 감시해서, 광고 바인딩 후에도 OS 가 어댑터를 잡고 있으면 경고가 남는다.

### 실제 ID로 바꾸기

1. [AdMob](https://admob.google.com/) 에서 앱을 등록하고 **앱 ID** 와 **배너 광고 단위 ID** 를 만든다.
2. `app/src/main/res/values/ads.xml` 의 두 문자열을 그 값으로 바꾼다.
3. Play Console **정책 → 앱 콘텐츠 → 광고** 에서 광고 포함으로 선언한다.
4. **데이터 보안** 양식에 광고 ID 등 AdMob 이 수집·공유하는 항목을 적고, 개인정보처리방침 URL 을 스토어 등록정보와 앱에 연결한다.
5. Play Console 에 등록한 개발자 도메인 루트에 `app-ads.txt` 를 둔다. AdMob 앱 설정의 한 줄(`google.com, pub-xxxxxxxx, DIRECT, f08c47fec0942fa0`)을 넣는다.
6. 테스트 ID 로 스토어 빌드를 올리지 않는다. 개발 중에는 테스트 ID 를 유지해 무효 트래픽으로 계정이 제한되지 않게 한다.

실기기에서 볼 것: Wi-Fi 또는 LTE 에서만 테스트 배너가 보이는지, 비행기 모드이거나 이더넷뿐이면 배너가 사라지는지, OS 가 어댑터를 잡은 상태에서도 Modbus/Ping 이 계속되는지, 폴링·쓰기·Ping 중에 배너 갱신이 멈추는지.

## 실기기 PoC 체크리스트

어댑터: **NX-UC-RJ45-020M / 050M** (USB 3.1 Type-C Gen1, 칩셋 "Smart 칩셋" — 정확한 모델 미확인)

| # | 확인 항목 | 방법 | 판정 |
|---|---|---|---|
| 1 | 어댑터 칩셋 확인 | 꽂고 **[USB 정보]** → VID:PID, CDC-ECM 지원 여부 기록 | ECM 지원 = 현재 드라이버로 진행 / 미지원 = 칩셋 전용 드라이버 필요 |
| 2 | 앱이 어댑터 점유 | **[어댑터 연결]** → "이 어댑터는 이제 OS 가 아닌 앱이 사용합니다" 로그 | 실패 시 오류 메시지 그대로 기록 |
| 3 | 인터넷 분리 | 연결 후 상단 "폰 인터넷: Wi-Fi/모바일 데이터 · 정상" 유지 확인, 브라우저로 인터넷 사용 | "이더넷(⚠ OS 가 어댑터 사용 중)" 이 뜨면 점유 실패 |
| 4 | 앱 IP 설정 | 장비 대역 IP 입력 → **[IP 적용 · 충돌 검사]** | 충돌 경고 없어야 함 |
| 5 | 장비 응답 | **[ARP] [Ping] [포트 확인]** (502) | ARP 성공이 1차 관문 (Ping 은 장비가 막을 수 있음) |
| 6 | Modbus 읽기 | FC03, 장비 매뉴얼의 레지스터 주소/타입/순서로 **[실행]** | 값이 매뉴얼과 일치 |
| 7 | 안정성 | **[폴링 시작]** 10분 | 실패 0, 응답시간 기록 |
| 8 | 재연결 | 케이블 뽑았다 꽂기 → 다시 **[어댑터 연결]** | 정상 복귀 |
| 9 | 배너 | Wi-Fi 또는 LTE 에서만 테스트 배너. 비행기 모드·이더넷뿐이면 배너 없음. 폴링/쓰기/Ping 중에는 갱신 정지 | 광고 실패·오프라인이어도 1–8 은 그대로 |

결과(특히 1번 USB 정보 전체 로그)를 공유하면 다음 단계(전용 드라이버 여부)를 결정한다.

## 알려진 제약 / 다음 단계

- USB 드라이버는 **CDC-ECM** 만 지원. ECM 모드가 없는 칩(예: ASIX AX88179 제조사 모드)은 전용 드라이버 추가 필요
- 일부 폰에서는 커널 드라이버가 어댑터를 놓지 않아 ECM 구성 전환이 거부될 수 있음 → 실기기 확인 항목
- IPv4 단편화, IPv6, DHCP 클라이언트, TCP 윈도우 스케일 미지원 (Modbus TCP 용도에는 불필요)
- 폴링 중 다른 요청은 같은 통신 스레드에서 순서대로 실행됨
- Inter 폰트는 라틴 문자만 포함 → 한글은 시스템 폰트로 표시
