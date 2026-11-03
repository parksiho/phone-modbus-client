# 권한 근거

릴리스 매니페스트에 앱이 직접 선언한 항목과, 광고 SDK 가 합쳐 넣는 항목입니다. 제출 전에 `./gradlew :app:processReleaseManifest` 결과와 한 번 더 비교합니다.

## 앱이 선언하는 권한

| 권한 | 왜 있나 | Play 에서 |
|---|---|---|
| `android.permission.INTERNET` | AdMob 배너와 UMP 동의. Modbus/USB 는 소켓을 쓰지 않습니다. | 일반 권한. 별도 민감 권한 선언은 없습니다. |
| `android.permission.ACCESS_NETWORK_STATE` | 폰 인터넷이 LTE/Wi-Fi 인지, 검증됐는지 보고 광고를 장비망으로 보내지 않습니다. 화면의 폰 인터넷 줄에도 씁니다. | 일반 권한. |
| `android.permission.CHANGE_NETWORK_STATE` | 기본 네트워크가 USB 이더넷일 때 셀룰러/Wi-Fi 를 `requestNetwork` 로 요청합니다. 설치 시 부여되는 일반 권한입니다. | 일반 권한. 민감 권한 양식(SMS, 위치, 사진 등)의 대상이 아닙니다. |

## USB

매니페스트에 `android.permission.USB` 같은 USB 권한은 없습니다. 어댑터 접근은 실행 중에 `UsbManager` 권한 대화상자로 받습니다.

`android.hardware.usb.host` 는 `required="true"` 입니다. 권한이 아니라 **기기 필터**입니다. USB 호스트가 없는 기기는 Play 에서 이 앱이 보이지 않습니다. 이 앱은 어댑터 없이 동작하지 않으므로 `required` 를 끄지 않는 편이 맞습니다. 특별한 USB 민감 권한 선언 양식은 없습니다. 스토어 설명에 USB-C 이더넷이 필요하다고 적혀 있어야 합니다.

`USB_DEVICE_ATTACHED` 인텐트 필터는 어댑터를 꽂을 때 앱을 제안하기 위한 것입니다. 권한 선언이 아닙니다.

## WRITE_SECURE_SETTINGS — 릴리스에서 뺌

`WRITE_SECURE_SETTINGS` 는 서명 수준 권한입니다. Play 가 서명한 앱에는 부여되지 않습니다. adb 로만 줄 수 있고, 그때만 "USB 어댑터가 키보드로 잡혀도 화면 키보드 유지"가 됩니다.

스토어 매니페스트에 남겨 두면 받을 수 없는 권한을 선언하는 꼴이라 심사에서 불필요한 질문이 됩니다. **릴리스에는 넣지 않습니다.** `app/src/debug/AndroidManifest.xml` 에만 있습니다. 권한이 없으면 해당 설정은 `SecurityException` 으로 넘어가고 통신 기능은 그대로입니다.

디버그 현장에서 키보드가 숨으면, Play 빌드가 아니라 adb 설치에만 다음을 기대할 수 있습니다.

```bash
adb shell pm grant com.emsfield.fieldtester android.permission.WRITE_SECURE_SETTINGS
```

Play 정책상 이 권한을 다시 메인 매니페스트에 넣지 않는 것을 권합니다.

## 광고 SDK 가 합치는 권한

앱 코드가 직접 선언하지 않습니다. `play-services-ads` 매니페스트 병합 결과입니다.

| 권한 | 근거 | Play 에서 |
|---|---|---|
| `com.google.android.gms.permission.AD_ID` | 광고 ID. Android 13+ 에서 광고 ID 를 읽으려면 필요합니다. | **광고 ID 양식에서 사용함으로 선언**합니다. 제거하면 광고 ID 가 0 이 됩니다. |
| `android.permission.ACCESS_ADSERVICES_AD_ID` | 개인정보 샌드박스 광고 ID API. | SDK 병합. 앱이 따로 선언할 내용이 아닙니다. |
| `android.permission.ACCESS_ADSERVICES_ATTRIBUTION` | 광고 기여 측정. | 같음. |
| `android.permission.ACCESS_ADSERVICES_TOPICS` | 광고 Topics API. | 같음. |
| `android.permission.WAKE_LOCK` | AdMob 이 가져오는 WorkManager 가 짧은 작업을 유지할 때 씁니다. 앱이 직접 켜지 않습니다. | 일반 권한. |
| `android.permission.FOREGROUND_SERVICE` | 같은 WorkManager 구성요소입니다. 현재 병합본의 포그라운드 서비스는 기본값으로 꺼져 있는 플래그를 봅니다. 앱이 장비 통신을 포그라운드로 돌리려고 넣은 것이 아닙니다. | 일반 권한. 민감 권한 양식의 대상이 아닙니다. 광고 SDK 를 빼지 않는 한 제거하지 않습니다. |

`RECEIVE_BOOT_COMPLETED` 는 현재 릴리스 병합 매니페스트에 없습니다. 광고 SDK 가 그 선언을 빼도록 표시해 두었습니다.

위치, 카메라, 마이크, 연락처, 저장소 전체 접근, SMS, 전화, VPN, 기기 관리자 권한은 없습니다.
