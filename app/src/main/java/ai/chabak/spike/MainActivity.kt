package ai.chabak.spike

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.pleos.playground.vehicle.Vehicle
import java.time.LocalTime

/**
 * 0단계 SDK 스파이크 — 증명하려는 것 딱 세 가지:
 *   (1) Vehicle 서비스에 연결된다       → initialize 후 capability 콜백이 온다
 *   (2) SOC 를 실제로 읽는다            → getEvBatteryStateOfCharge onComplete 에 숫자가 온다
 *   (3) 값 변경이 콜백으로 온다         → Virtual Console 에서 SOC 를 바꾸면 register 리스너가 찍힌다
 * 부수: 읽은 값을 chabak-server 에 source=PLEOS 로 올린다 → GET /state 에 PLEOS 로 찍힘.
 *
 * 로그 필터:  adb logcat -s CHABAK_SPIKE
 */
class MainActivity : AppCompatActivity() {
    private lateinit var vehicle: Vehicle
    private lateinit var server: ServerClient
    private lateinit var out: TextView
    private val lines = StringBuilder()

    // 시그니처는 Vehicle-2.3.2.aar 의 classes.jar 를 javap 로 확인했다 (SDK-REPORT.md):
    //   checkEvBatteryStateOfChargeCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed)  — area 맵 없음
    //   getEvBatteryStateOfCharge(onComplete: (Int?) -> Unit, onFailed)                          — area 인자 없음, 값은 정수 %
    //   EvBatteryStateOfChargeListener.onEvBatteryStateOfChargeUpdated(Int?) + onFailed(Exception)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 14f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(out) })
        server = ServerClient(this)

        log("SDK 2.3.2 / server ${BuildConfig.SERVER_BASE}")
        server.enrollIfNeeded { log("server: $it"); server.heartbeat() }

        vehicle = Vehicle(applicationContext)
        vehicle.initialize()
        log("vehicle.initialize() called")

        checkSocCapability()
    }

    /** (1) capability — 성공하면 서비스 연결 + 권한 + 지원 셋 다 증명 */
    private fun checkSocCapability() {
        val ev = vehicle.getEvBattery()
        ev.checkEvBatteryStateOfChargeCapabilities(
            onComplete = { config ->
                val access = config.accessType.toString()
                log("✔ capability SOC access=$access (전역 속성 — area 없음)")
                server.reportCapability("ev.soc", access, null, null, emptyList(), null)
                if (access == "READ" || access == "READ_WRITE") { readSoc(); subscribeSoc() }
                else log("✘ SOC 읽기 권한 없음 (accessType=$access) — 읽기 생략")
            },
            onFailed = { e ->
                val cls = VehicleErrors.classify(e)
                log("✘ capability SOC failed: $cls")
                server.reportCapability("ev.soc", "NONE", null, null, emptyList(), cls)
                // 서비스 미연결이면 initialize 직후 너무 빨리 호출한 것일 수 있다 → 2초 후 1회 재시도
                if (cls == "SERVICE_DOWN") out.postDelayed({ checkSocCapability() }, 2000)
            },
        )
    }

    /** (2) 단발 읽기 */
    private fun readSoc() {
        val ev = vehicle.getEvBattery()
        ev.getEvBatteryStateOfCharge(
            onComplete = { soc -> onSoc("get", soc) },
            onFailed = { e -> log("✘ get SOC failed: ${VehicleErrors.classify(e)}") },
        )
    }

    /** (3) 변경 콜백 — Virtual Console 에서 SOC 를 바꿔서 확인 */
    private fun subscribeSoc() {
        val ev = vehicle.getEvBattery()
        val listener = object : ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = onSoc("callback", soc)
            override fun onFailed(exception: Exception) { log("✘ SOC listener failed: ${VehicleErrors.classify(exception)}") }
        }
        ev.registerEvBatteryStateOfCharge(listener)
        log("registered SOC listener — Virtual Console 에서 SOC 값을 바꿔 보세요")
    }

    private fun onSoc(via: String, soc: Number?) {
        if (soc == null) log("⚠ SOC($via) = null — ERROR_EMPTY_RESULT 와 구분해 '값 없음' 으로 기록")
        else log("✔ SOC($via) = $soc %")
        server.telemetry("evSocPct", soc, "%")
    }

    override fun onDestroy() {
        // 콜백이 진행 중일 때 release() 하지 말라는 가이드 — 스파이크는 단순히 마지막에 해제
        runCatching { vehicle.release() }
        super.onDestroy()
    }

    private fun log(msg: String) {
        Log.i(ServerClient.TAG, msg)
        lines.append(LocalTime.now().withNano(0)).append("  ").append(msg).append('\n')
        runOnUiThread { out.text = lines }
    }
}
