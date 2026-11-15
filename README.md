# chabak-sdk-spike — 0단계: 에뮬레이터에서 SOC 가 진짜 읽히는지

이 앱이 증명하는 것은 딱 셋. 셋 다 되면 `PLEOS_EMULATOR` 모드로 시연할 근거가 생기고, 하나라도 안 되면 시연은 MOCK 입니다.

| # | 증명 | 성공 로그 |
|---|---|---|
| 1 | Vehicle 서비스 연결·권한·지원 | `✔ capability SOC access=READ …` |
| 2 | SOC 단발 읽기 | `✔ SOC(get) = 72.0 %` |
| 3 | 값 변경 콜백 | Virtual Console 에서 바꾼 뒤 `✔ SOC(callback) = 55.0 %` |

## 준비 (당신 PC)

1. Android Studio + Pleos Connect 에뮬레이터 AVD(`Pleos_Connect_v2.1`, Android 14) 실행 중.
2. `chabak-server` 가 호스트에서 `:3000` 으로 떠 있음 (`npm run build && npm start`). AVD 에서는 `10.0.2.2:3000` 으로 보임.
3. 이 폴더를 Android Studio 로 열고 Gradle sync. Pleos nexus 저장소에서 `ai.pleos.playground:Vehicle:2.3.2` 가 받아져야 함 — 안 받아지면 SDK 신청 상태/네트워크 문제이고 그 뒤는 볼 필요 없음.

## 빌드 전에 반드시 할 것 — 시그니처 2개 확인

문서 본문이 JS 렌더링이라 두 가지를 공식 문서에서 확정하지 못했습니다. **Android Studio 에서 Ctrl+클릭으로 30초면 끝납니다.**

| 확인할 것 | 어디서 | 코드에서 고칠 곳 |
|---|---|---|
| `getEvBatteryStateOfCharge` 가 `area` 인자를 받는가 | `MainActivity.readSoc()` 의 호출에 Ctrl+클릭 | 받으면 변형 A 삭제, 변형 B 활성화 |
| SOC 리스너 인터페이스·메서드 이름 (`EvBatteryStateOfChargeListener.onStateOfChargeUpdated`?) | `ai.pleos.playground.vehicle.listener` 패키지 열어 보기 | `subscribeSoc()` 의 object 선언 |

Door 는 `DoorPositionListener.onPositionUpdated(pos: Int?)` 패턴이 공식 샘플에 있으니 EvBattery 도 같은 패턴일 가능성이 높지만, **추정입니다.** 컴파일 에러가 나면 그 자리입니다.

## 실행·판독

```bash
adb logcat -s CHABAK_SPIKE
```

| 로그 | 의미 | 다음 행동 |
|---|---|---|
| `✔ capability SOC access=READ` | 연결·권한·지원 OK | 2번으로 |
| `✘ capability SOC failed: SERVICE_DOWN` | Vehicle Service 미연결 | AVD 가 Pleos 이미지인지, 서비스가 떠 있는지. 2초 후 자동 재시도 1회 |
| `✘ capability SOC failed: PERMISSION_DENIED` | manifest `CAR_ENERGY` 누락 또는 미승인 | manifest 확인. 선언했는데도 나오면 권한 정책 문제 — 코드로 못 고침 |
| `✘ capability SOC failed: UNSUPPORTED` | 이 이미지/차종에서 SOC 미지원 | **SOC 시연 불가 확정.** 다른 읽기(도어·실내온도)로 스파이크 대상을 바꿈 |
| `✘ … UNKNOWN_EXCEPTION(…)` | VehicleException 이 아닌 예외 | 메시지 그대로 보고. `VehicleException` 이 클래스패스에 없을 수 있음 (타 팀 보고) |
| `✔ SOC(get) = 72.0 %` | 읽기 OK | Virtual Console 열어 SOC 바꾸기 |
| `⚠ SOC(get) = null` | 값 없음 | 정상 아님. Console 에서 값 설정 후 재실행 |
| `✔ SOC(callback) = …` | 변경 구독 OK | **0단계 완료** |

## 서버 쪽에서 교차 확인

폰 역할로(또는 curl) 로그인·페어링 후:

```bash
curl -s localhost:3000/v1/vehicles/<vehicleId>/state -H "authorization: Bearer <userJWT>"
# → metrics.evSocPct.source == "PLEOS", executionMode == "PLEOS_EMULATOR"
curl -s localhost:3000/v1/vehicles/<vehicleId>/capabilities -H "authorization: Bearer <userJWT>"
# → features[0].feature == "ev.soc", supported true/null, reason 로 실패 사유
```

`source=PLEOS` 가 찍힌 telemetry 와 `capability-report` 레코드가 **"에뮬레이터 SDK 연동 증거"** 입니다. 영상보다 이 두 레코드와 logcat 을 제출 자료에 넣으세요.

페어링을 안 했으면 state 조회는 404 입니다(소유 검사). 스파이크만 볼 땐 서버 로그(`telemetry evSocPct=… → 202`)로 충분합니다.

## 실패했을 때 해석

- Gradle 에서 SDK 자체가 안 받아짐 → 신청/권한 문제. 공모전 운영에 문의할 사항이지 코드 문제가 아님.
- capability 는 되는데 get 이 `ERROR_EMPTY_RESULT` → 에뮬레이터 Virtual Console 에 SOC 값이 세팅 안 됨.
- 전부 되는데 서버 POST 가 0/타임아웃 → `10.0.2.2` 접근 문제(방화벽, 서버 미실행, `network_security_config`).
