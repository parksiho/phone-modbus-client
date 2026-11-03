# Play 출시 체크리스트

순서는 개인 개발자 계정 기준입니다. 조직 계정은 비공개 테스트 12명 규칙이 다를 수 있으니 콘솔 안내를 우선합니다. 날짜는 2026-11-03 기준 공개 문서입니다.

## 1. 개발자 계정

1. [Play Console](https://play.google.com/console) 에서 개발자 계정을 만듭니다. 등록비와 신원 확인이 있습니다.
2. 개인 계정은 기기 확인(Play Console 모바일 앱)이 추가로 있을 수 있습니다.
3. 2023-11-13 이후에 만든 **개인** 계정은 프로덕션이 바로 열리지 않습니다. 비공개 테스트에 **12명 이상**이 참여한 상태가, 신청 시점으로부터 **직전 14일 동안 끊기지 않아야** 합니다. 중간에 빠지면 그 사람의 14일은 다시 셉니다. 조직 계정과 그 날짜 이전의 개인 계정은 이 조항의 적용이 다릅니다.
4. 조건을 채운 뒤 대시보드에서 프로덕션 액세스를 신청합니다. 테스트, 앱, 출시 준비에 대한 질문이 있습니다.

## 2. 업로드 키와 앱 서명

1. `tools/gen_upload_keystore.sh` 로 업로드 키를 만듭니다. `keystore.properties` 와 `*.jks` 는 gitignore 입니다. 예제만 커밋되어 있습니다 (`keystore.properties.example`).
2. 키스토어와 비밀번호를 저장소 밖(비밀번호 관리자, 오프라인 복사본)에 백업합니다. 업로드 키를 잃으면 앱 서명 등록 후에도 업데이트 업로드가 막힐 수 있습니다.
3. CI 에서는 `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` 를 비밀로 넣습니다. 환경변수가 있으면 `keystore.properties` 보다 우선합니다. 아무 값도 없으면 `./gradlew bundleRelease` 는 서명 없이 끝납니다.
4. Play Console 에서 **Play 앱 서명**을 켭니다. 우리가 가진 키는 업로드 키이고, 이용자에게 전달되는 앱 서명 키는 Google 이 갖습니다. 첫 AAB 업로드 때 등록됩니다.

## 3. 버전과 타깃 API

- 지금 `versionCode` 는 1, `versionName` 은 `1.0.0` 입니다. Play 에 올리는 빌드마다 `versionCode` 를 올립니다.
- `targetSdk` / `compileSdk` 는 **35** 입니다.
- 2026-08-31 부터 새 앱과 업데이트는 **Android 16 (API 36)** 이상을 타깃해야 제출됩니다. 연장은 2026-11-01 까지였습니다. 오늘(2026-11-03) 이후 제출이면 API 36 으로 올리기 전에는 거절될 수 있습니다. 올리기 전에 실기기에서 USB, 광고, 화면 여백을 다시 봅니다. 이 저장소는 아직 35 입니다.

## 4. 광고를 실제 ID 로

스토어에 올릴 빌드에는 테스트 ID 를 넣지 않습니다.

1. AdMob 에서 앱과 배너 광고 단위를 만듭니다.
2. `app/src/main/res/values/ads.xml` 의 앱 ID 와 배너 단위만 바꿉니다.
3. **앱 콘텐츠 → 광고**: 광고 포함, 배너만.
4. **광고 ID**: 사용함, 목적 광고.
5. 데이터 보안은 `data-safety.md`, 개인정보처리방침은 `privacy-policy.md` 입니다. 방침의 `[개발자 법적 이름]`, `[시행일]`, `privacy@example.com` 을 바꾼 뒤 공개 URL 로 호스트합니다.
6. `app-ads.txt` 의 `pub-0000000000000000` 을 AdMob 게시자 ID 로 바꾼 파일을, Play 에 등록한 개발자 도메인 루트에 HTTPS 로 둡니다.
7. 콘텐츠 등급 설문은 `content-rating.md` 를 보고 답합니다.

## 5. 번들

```bash
./gradlew bundleRelease
```

키가 있으면 서명된 AAB, 없으면 서명되지 않은 AAB 가 `app/build/outputs/bundle/release/` 에 나옵니다. 서명본만 Play 에 업로드됩니다. R8 과 리소스 축소는 release 에 켜져 있습니다.

업로드 트랙은 내부 테스트 → (개인 신규 계정이면) 비공개 테스트 14일 → 프로덕션 신청 → 프로덕션입니다. 첫 프로덕션은 단계적 출시(예: 20%)로 시작하고, 비정상 종료와 광고 오류를 본 뒤 비율을 올립니다.

## 6. 스토어 등록정보

문구는 `listing.md` 입니다.

| 자산 | 규격 | 이 저장소 |
|---|---|---|
| 앱 이름 | 30자 이하 | 문구만 있음 |
| 간단한 설명 | 80자 이하 | 문구만 있음 |
| 자세한 설명 | 4000자 이하 | 문구만 있음 |
| 고해상도 아이콘 | 512×512, 32비트 PNG | `play-store/graphics/play-icon-512.png`. 배경이 불투명하다. |
| 그래픽 이미지 | 1024×500 PNG | `play-store/graphics/feature-graphic-1024x500.png` |
| 런처 아이콘 | 적응형 + 레거시 mipmap | `mipmap-anydpi-v26` 과 mdpi–xxxhdpi. 매니페스트 `android:icon` / `android:roundIcon`. |
| 휴대폰 스크린샷 | 최소 2장. 각 변 320–3840 px. 가로세로 16:9 또는 9:16 이 안전합니다. | **없음.** 실기기에서 메인 화면과 Ping 화면을 찍습니다. |
| 개인정보처리방침 URL | 공개 HTTPS | 아직 호스트하지 않음 |

콘솔이 요구하는 정확한 픽셀 제한은 업로드 화면의 안내를 다시 확인합니다. 아이콘을 고친 뒤 다시 만들려면 `python3 tools/gen_launcher_icons.py` (Pillow 필요). 원본은 `play-store/graphics/icon.svg` 와 `res/drawable/ic_launcher_*.xml` 이다. 스크린샷은 실기기 사진이라 스크립트로 만들지 않는다.

## 7. 권한

`permissions.md` 를 봅니다. `WRITE_SECURE_SETTINGS` 는 릴리스 매니페스트에 없습니다. USB 호스트는 `required="true"` 기능 선언이라, USB 호스트가 없는 기기에는 앱이 노출되지 않습니다.

## 8. 올리기 전에 실기기에서

- 실제 AdMob ID 로 테스트 기기를 등록한 뒤, Wi-Fi 와 LTE 에서만 배너가 보이는지
- 어댑터를 OS 가 잡은 상태에서도 Modbus 가 되는지
- 폴링, 쓰기, Ping 중에 배너가 멈추는지
- 쓰기 한 번이 장비 값을 바꾸는지 (설명문과 같아야 함)
- CDC-ECM 이 아닌 어댑터에서 실패가 앱 전체를 멈추지 않는지
- R8 릴리스 빌드에서 광고 초기화와 UMP 가 디버그 빌드와 같이 도는지
