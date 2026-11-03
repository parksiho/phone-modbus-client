# 개인정보처리방침 / Privacy Policy

시행일 / Effective date: [시행일]

개발자 / Developer: [개발자 법적 이름]

연락처 / Contact: privacy@example.com

`privacy@example.com` 과 위의 이름·날짜는 자리표시자입니다. 스토어에 올리기 전에 실제 값으로 바꿉니다. 이 문서를 개발자 웹사이트의 고정 URL 에 올린 뒤, 그 URL 을 Play Console 과 스토어 등록정보에 넣습니다.

---

## 한국어

모드버스 체커("앱", 영문 Modbus Checker)는 USB-C 이더넷 어댑터로 현장 계측기의 Modbus TCP 를 점검하는 도구입니다. 개발자는 이용자 계정을 만들지 않으며, 장비 데이터나 Modbus 값을 받는 서버를 운영하지 않습니다.

### 기기 안에만 있는 데이터

다음 정보는 폰에만 저장되고, 개발자 서버로 전송되지 않습니다.

- 앱 IP, 프리픽스, 게이트웨이, 대상 장비 주소, 포트, Unit ID, 요청 형식, 언어, 폴링 주기
- USB 어댑터로 주고받은 Modbus 요청·응답, Ping 결과, 화면의 로그
- 이용자가 입력한 레지스터 값

Modbus 통신과 장비 데이터는 폰과 USB 어댑터 사이의 현장에서 끝나며, 개발자가 수집하지 않습니다. 앱을 지우거나 앱 저장소를 삭제하면 이 데이터도 폰에서 지워집니다.

### 광고 (AdMob) 와 광고 ID

앱은 화면 하단에 Google AdMob 배너 광고를 표시할 수 있습니다. 광고가 로드될 때 Google 이 다음을 처리할 수 있습니다.

- 광고 ID (Advertising ID)
- 광고 요청에 필요한 기기 정보 (예를 들어 기기 모델, 언어, 화면 크기, 대략적인 위치 또는 IP 주소). 정확한 항목은 Google 의 현재 안내를 따릅니다.
- 광고 표시·클릭 등 광고와의 상호작용
- UMP(User Messaging Platform) 로 받은 광고 동의 상태

이 정보의 처리 주체는 Google 입니다. 개발자는 광고 ID 를 자신의 서버에 저장하지 않습니다. 광고 요청은 LTE/셀룰러 또는 Wi-Fi 로만 나가며, USB 장비망으로는 나가지 않습니다.

유럽경제지역, 영국 등 동의가 필요한 곳에서는 광고를 요청하기 전에 UMP 동의 창을 띄울 수 있습니다. 동의하지 않거나 필요한 네트워크가 없으면 광고를 요청하지 않습니다. 동의 선택은 기기에서 바꿀 수 있으며, Google 의 광고 설정과 폰의 광고 ID 재설정으로 광고 ID 를 지울 수 있습니다.

Google 의 안내: https://policies.google.com/privacy 그리고 https://policies.google.com/technologies/ads

### 아동

이 앱은 아동을 대상으로 하지 않으며, 아동 전용 광고 처리를 켜지 않습니다.

### 보관과 보안

개발자가 서버에 보관하는 이용자 데이터나 장비 데이터는 없습니다. 광고 데이터는 Google 이 자신의 정책에 따라 보관합니다. 광고 통신은 HTTPS 를 사용합니다.

### 삭제 요청

개발자 서버에 계정이나 장비 기록이 없습니다. 폰의 앱 데이터를 지우려면 앱을 삭제하거나 시스템 설정에서 저장소를 지우면 됩니다. Google 이 가진 광고 ID 데이터는 기기 설정에서 광고 ID 를 재설정하거나, 아래 이메일로 요청하면 개발자가 Google 도구로 처리할 수 있는 범위를 안내합니다.

### 변경

이 방침이 바뀌면 같은 URL 의 문서와 시행일을 갱신합니다.

### 문의

privacy@example.com

---

## English

Modbus Checker (the "app"; Korean name 모드버스 체커) checks Modbus TCP on field instruments through a USB-C Ethernet adapter. The developer does not create user accounts and does not operate a server that receives equipment data or Modbus values.

### Data that stays on the device

The following stays on the phone and is not sent to a developer server:

- App IP, prefix, gateway, target address, port, unit ID, request format, language, and poll interval
- Modbus requests and responses, ping results, and on-screen logs exchanged through the USB adapter
- Register values you type

Modbus traffic and equipment data remain on the link between the phone and the USB adapter. The developer does not collect them. Uninstalling the app or clearing its storage removes this data from the phone.

### Advertising (AdMob) and the advertising ID

The app may show a Google AdMob banner at the bottom of the screen. When an ad loads, Google may process:

- The advertising ID
- Device details needed to request an ad (for example device model, language, screen size, and coarse location or IP address). The exact set follows Google's current documentation.
- Interactions with the ad, such as impressions and clicks
- The ad-consent state collected through the User Messaging Platform (UMP)

Google is the controller of that processing. The developer does not store the advertising ID on the developer's own server. Ad requests use LTE/cellular or Wi-Fi only. They are not sent through the USB equipment network.

Where consent is required, including the EEA and the UK, the app can show a UMP consent form before requesting ads. If you do not consent, or no suitable phone network is available, the app does not request an ad. You can change the consent choice on the device, and you can reset the advertising ID in the phone's settings or in Google's ad settings.

Google's policies: https://policies.google.com/privacy and https://policies.google.com/technologies/ads

### Children

The app is not directed at children, and it does not enable child-directed ad treatment.

### Retention and security

The developer does not keep user or equipment data on a server. Google retains ad data under its own policy. Ad traffic uses HTTPS.

### Deletion

There is no account and no equipment record on a developer server. To remove data from the phone, uninstall the app or clear its storage. To reset the advertising ID held by Google, use the device advertising-ID setting. You can also email the address below and the developer will explain what Google-side deletion the developer can actually start.

### Changes

If this policy changes, the same URL and the effective date are updated.

### Contact

privacy@example.com
