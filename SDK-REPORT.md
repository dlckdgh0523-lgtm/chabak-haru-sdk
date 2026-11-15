# 차박하루 × Pleos Vehicle SDK 연동 보고 (0단계 스파이크)

- SDK: `ai.pleos.playground:Vehicle:2.3.2`. 공개 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 그대로 받아집니다.
- 대상: AVD `Pleos_Connect_2_1`
  - 이미지: `android-34/pleos_car_emulator`, 42dot Automotive, user build
  - Vehicle Service: `ai.pleos.playground.service.vehicle` 2.3.22 (versionCode 74). SDK가 요구하는 최소 버전(2.3.13 / 65) 이상입니다.
- 앱: `chabak-sdk-spike` (applicationId `ai.chabak.spike.v232`). 백엔드는 `chabak-hanbam-backend`의 `10.0.2.2:3000`입니다.

## 결과 요약

| # | 증명 | 결과 | 증거 |
|---|---|---|---|
| 1 | Vehicle 서비스 연결·권한·지원 (capability) | **성공** `✔ capability SOC access=READ` | `EVIDENCE/logcat-run1.txt`, `EVIDENCE/server-capabilities.json` (revision 1, `ev.soc` supported, readAllowed) |
| 2 | SOC 단발 읽기 | **성공** `✔ SOC(get) = 100 %` | logcat, `EVIDENCE/server-state.json` (`metrics.evSocPct.value=100`, `source=PLEOS`) |
| 3 | 값 변경 콜백 | **부분** 리스너 등록 성공, 등록 직후 현재값 콜백 수신(`✔ SOC(callback) = 100 %`). **Virtual Console로 값을 바꾼 뒤 오는 콜백은 아직 확인 못 함** | logcat. 아래 "3번 남은 절차" |

결론: 차량 상태 읽기(SOC)는 에뮬레이터에서 **실제 SDK 값**으로 서버까지 이어집니다(`source=PLEOS`). 그래서 `PLEOS_EMULATOR` 모드 시연 근거가 됩니다. 변경 구독은 3번 절차까지 끝나야 확정입니다.

## 확정한 시그니처 (`Vehicle-2.3.2.aar`의 classes.jar를 javap로 직접 확인)

| 항목 | 실제 | 스파이크 원본 추정 → 수정 |
|---|---|---|
| capability | `checkEvBatteryStateOfChargeCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit)`. `CapabilityConfig.accessType: AccessType {NONE, READ, WRITE, READ_WRITE}` | `(config, areaMap)` 두 인자 → **하나**. SOC는 전역 속성이라 area·범위가 없음 |
| 단발 읽기 | `getEvBatteryStateOfCharge(onComplete: (Int?) -> Unit, onFailed)` | **area 인자 없음** (변형 A가 맞음). 값은 `Float`가 아니라 **`Int` %** |
| 리스너 | `EvBatteryStateOfChargeListener.onEvBatteryStateOfChargeUpdated(Int?)` + 부모 `VehicleListener.onFailed(Exception)` | `onStateOfChargeUpdated(Float?)` → 이름·타입 수정 |
| 등록/해제 | `registerEvBatteryStateOfCharge(listener)` / `unregisterEvBatteryStateOfCharge(listener)` | 그대로 |
| 예외 | 난독화된 `ai.pleos.playground.vehicle.callback.exception.a`, 에러 코드 접근자 `a(): ErrorCode` | `getErrorCode()` 리플렉션은 항상 null → 반환 타입이 `ErrorCode`인 메서드로 찾게 수정 |
| ErrorCode | `ERROR_INTERNAL_SERVICE, ERROR_SERVICE_CONNECTION_FAILURE, ERROR_DRIVING_RESTRICTED, ERROR_NOT_SUPPORTED_PROPERTY, ERROR_NOT_SUPPORTED_VALUE_TYPE, ERROR_NOT_SUPPORTED_AREA, ERROR_EMPTY_RESULT, ERROR_SECURITY_PERMISSION, ERROR_UNDEFINED` | — |
| 권한 | SOC 관련 메서드는 `@RequiresCustomPermission(pleos.car.permission.CAR_ENERGY)`. SDK manifest가 `ai.pleos.playground.service.vehicle.VEHICLE_BINDING`을 병합함 | manifest 그대로 (CAR_ENERGY 선언됨, 설치 시 granted=true) |

참고:
- POM에서 `vehicle-data`(ErrorCode 등)와 `vehicle-aidl`이 **runtime** scope입니다. 그래서 `ErrorCode`, 예외 타입을 컴파일 시점에 import할 수 없습니다. 기존 "VehicleException이 클래스패스에 없다"는 보고의 원인이 이것입니다.
- Door 리스너도 같은 패턴(`DoorPositionListener.onPositionUpdated(Int?)`)입니다.

## 안 되는 것과 정확한 에러

| 상황 | 결과 | 의미 |
|---|---|---|
| 일반 폰 AVD (`Medium_Phone_API_37.0`, Pleos 서비스 없음) | `checkEvBatteryStateOfChargeCapabilities` → `java.lang.SecurityException: checkEvBatteryStateOfChargeCapabilities need Permission` (Pleos ErrorCode 아님) | 폰에는 `pleos.car.permission.CAR_ENERGY` 권한 정의가 없어서, SDK가 서비스 연결 전에 권한 검사에서 막힙니다. **폰 앱에서는 SDK를 호출하지 말고**(automotive 기능 확인 후에만) 서버 state를 읽어야 합니다. `EVIDENCE/logcat-phone-no-vehicle-service.txt` |
| adb로 VHAL 값 주입 (`cmd car_service get-property-value`, `list-properties`) | `java.lang.SecurityException: The command '…' requires non-user build` | Pleos 이미지가 user build라서 shell로 차량 값을 바꿀 수 없습니다. 값 변경은 Virtual Console(GUI)로만 할 수 있습니다 |
| `adb emu` 콘솔 | 차량 속성 명령 없음 (sensor/geo/power 등만 있음) | 위와 같음 |

## 3번 남은 절차 (Virtual Console)

1. 스파이크가 떠 있는 상태에서(`adb -s emulator-5554 shell am start -n ai.chabak.spike.v232/ai.chabak.spike.MainActivity`) Pleos 에뮬레이터 Virtual Console에서 EV SOC를 다른 값(예: 55)으로 바꿉니다.
2. `adb -s emulator-5554 logcat -d -s CHABAK_SPIKE`에 `✔ SOC(callback) = 55 %`와 `telemetry evSocPct=55 → 202`가 찍히면 성공입니다.
3. 서버 `GET /v1/vehicles/<vehicleId>/state`의 `evSocPct.value`가 55이고 `source`가 PLEOS인지 확인합니다. 결과는 `EVIDENCE/logcat-run2-console.txt`로 저장합니다.

## 메인 앱(`chabak-haru/android`)에 붙이는 방안

현재 메인 앱의 구조:
- Java로 되어 있고, WebView가 화면을 담당합니다.
- 네이티브 기능은 `MainActivity.createGateway()`가 돌려주는 `BackendGateway.dispatch(event, payload, requestId)`로 들어오고, 화면 갱신은 `publishState(event, payload)`로 보냅니다.
- minSdk는 26이고, SDK 요구 minSdk 23을 만족합니다.

1. **의존성**
   - `settings.gradle`의 `dependencyResolutionManagement.repositories`에 `maven { url 'https://nexus-playground.pleos.ai/repository/maven-releases/' }`를 추가합니다.
   - `app/build.gradle`에 `implementation 'ai.pleos.playground:Vehicle:2.3.2'`를 추가합니다. Kotlin stdlib은 transitive로 들어옵니다.
2. **권한**: `AndroidManifest.xml`에 `pleos.car.permission.CAR_ENERGY`(필요하면 `CAR_DOORS`, `CAR_INFO`도)를 추가합니다. **`CONTROL_CAR_*`는 승인 전에는 선언하지 않습니다.**
3. **차량에서만 호출**: `PackageManager.FEATURE_AUTOMOTIVE`가 true일 때만 `Vehicle(applicationContext).initialize()`를 부릅니다. 메인 앱은 이미 `automotive` 플래그를 가지고 있습니다. 폰에서 부르면 위의 SecurityException이 납니다.
4. **읽기 클래스 하나** (`VehicleReader.java`, Java에서 Kotlin 람다 호출):
   ```java
   vehicle.getEvBattery().getEvBatteryStateOfCharge(
       soc -> { publishSoc(soc); return kotlin.Unit.INSTANCE; },
       e -> { publishError(VehicleErrors.classify(e)); return kotlin.Unit.INSTANCE; });
   ```
   - 리스너는 `EvBatteryStateOfChargeListener`를 익명 클래스로 구현합니다(`onEvBatteryStateOfChargeUpdated`, `onFailed`).
   - 등록은 `onStart`, 해제(`unregister…`)는 `onStop`에서 하고, `release()`는 `onDestroy`에서 콜백이 끝난 뒤에 부릅니다.
5. **화면 연결**
   - CID 화면의 `vehicle.setting` / `vehicle.utility.*`에 쓰는 배터리 값은 `publishState("vehicle.settings", {socPct, source:"PLEOS"})`로 보냅니다.
   - 값이 없으면(`null`, `ERROR_EMPTY_RESULT`) 0으로 채우지 말고 "값 없음"으로 표시합니다.
6. **서버 반영**: live 모드에서는 스파이크의 `ServerClient.telemetry()`와 같이 `POST /v1/vehicles/:id/telemetry-batches`에 `source:"PLEOS"`로 올립니다(차량 장치 토큰 필요). 그러면 폰은 서버 `STATE_SNAPSHOT`으로 같은 값을 받습니다.
7. **빌드 환경**: 이 PC에는 JDK 25(Android Studio JBR)만 있어서 스파이크는 Gradle 9.2.1 + AGP 8.13.2 + Kotlin 2.2.21로 빌드했습니다. 메인 앱 wrapper(Gradle 8.13)는 JDK 17–21이 필요합니다.

## 스파이크 변경 이력

- `e8aa1bd`: 원본 그대로 (`chabak-sdk-spike.tar.gz`)
- `b9cbe99`: .gitignore
- `3314200`: 실제 시그니처로 수정, 빌드 환경 갱신
- 이후: debug applicationId에 `.v232` 접미사. 이 AVD에 서명이 다른 `ai.chabak.spike`가 이미 설치되어 있어서(11/10, 출처 미상) 덮어쓰지 않았습니다.

## 증거 파일 (`EVIDENCE/`)

토큰은 `dvt_****`로 마스킹했고, 사용자 JWT는 저장하지 않았습니다.

- `logcat-run1.txt`: 차량 AVD 실행 로그 (1·2·3-부분)
- `logcat-phone-no-vehicle-service.txt`: 폰 AVD에서의 실패 로그
- `server-state.json`: `GET /vehicles/:id/state` (evSocPct source=PLEOS)
- `server-capabilities.json`: `GET /vehicles/:id/capabilities` (= 앱이 올린 capability-report)
