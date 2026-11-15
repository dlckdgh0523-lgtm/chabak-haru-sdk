package ai.chabak.spike

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/**
 * chabak-server 와의 최소 계약. 의존성 없이 HttpURLConnection 만 쓴다.
 * 장치 토큰은 SharedPreferences 에 저장(스파이크). 실제 앱은 EncryptedSharedPreferences.
 */
class ServerClient(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("chabak", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val bootId = UUID.randomUUID().toString()
    private var seq = 0

    val deviceToken: String? get() = prefs.getString("deviceToken", null)
    val vehicleId: String? get() = prefs.getString("vehicleId", null)

    fun enrollIfNeeded(onDone: (String) -> Unit) = io.execute {
        if (deviceToken != null) { onDone("already enrolled vehicleId=$vehicleId"); return@execute }
        val res = post("/devices/vehicle/enroll", null, JSONObject()
            .put("enrollSecret", BuildConfig.ENROLL_SECRET)
            .put("displayName", "Pleos AVD spike")
            .put("powertrain", "EV"))
        if (res.first == 201) {
            prefs.edit().putString("deviceToken", res.second.getString("deviceToken")).putString("vehicleId", res.second.getString("vehicleId")).apply()
            onDone("enrolled vehicleId=$vehicleId")
        } else onDone("enroll failed ${res.first}: ${res.second}")
    }

    fun heartbeat(mode: String = "PLEOS_EMULATOR", powerState: String = "PARKED") = io.execute {
        val v = vehicleId ?: return@execute
        post("/vehicles/$v/heartbeats", deviceToken, JSONObject().put("executionMode", mode).put("powerState", powerState))
    }

    fun reportCapability(feature: String, accessType: String, min: Number?, max: Number?, areas: List<String>, failure: String?) = io.execute {
        val v = vehicleId ?: return@execute
        val supported = failure == null
        val f = JSONObject()
            .put("feature", feature).put("supported", if (failure == null) true else JSONObject.NULL)
            .put("readAllowed", supported && (accessType == "READ" || accessType == "READ_WRITE"))
            .put("writeAllowed", supported && (accessType == "WRITE" || accessType == "READ_WRITE"))
            .put("areas", JSONArray(areas))
        if (failure != null) f.put("reason", if (failure == "PERMISSION_DENIED") "PERMISSION_DENIED" else if (failure == "UNSUPPORTED") "UNSUPPORTED" else "NOT_VERIFIED")
        if (min != null) f.put("min", min); if (max != null) f.put("max", max)
        val body = JSONObject()
            .put("sdkVersion", "2.3.2").put("emulatorVersion", "2.1.0").put("executionMode", "PLEOS_EMULATOR")
            .put("observedAt", Instant.now().toString())
            .put("permissions", JSONArray(listOf("pleos.car.permission.CAR_ENERGY", "pleos.car.permission.CAR_DOORS", "pleos.car.permission.CAR_INFO")))
            .put("features", JSONArray().put(f))
        val r = post("/vehicles/$v/capability-reports", deviceToken, body)
        Log.i(TAG, "capability-report → ${r.first} ${r.second}")
    }

    /** value=null 이면 결측으로 보낸다. 0 으로 채우지 않는다. */
    fun telemetry(metric: String, value: Any?, unit: String) = io.execute {
        val v = vehicleId ?: return@execute
        val s = JSONObject().put("metric", metric).put("value", value ?: JSONObject.NULL).put("unit", unit)
            .put("source", "PLEOS").put("observedAt", Instant.now().toString()).put("seq", ++seq).put("bootId", bootId)
        val r = post("/vehicles/$v/telemetry-batches", deviceToken, JSONObject().put("samples", JSONArray().put(s)))
        Log.i(TAG, "telemetry $metric=$value → ${r.first} ${r.second}")
    }

    private fun post(path: String, token: String?, body: JSONObject): Pair<Int, JSONObject> = try {
        val c = (URL(BuildConfig.SERVER_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 4000; readTimeout = 4000; doOutput = true
            setRequestProperty("content-type", "application/json")
            if (token != null) setRequestProperty("authorization", "Bearer $token")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
        code to (runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) })
    } catch (e: Exception) {
        Log.w(TAG, "POST $path failed: $e"); 0 to JSONObject().put("error", e.toString())
    }

    companion object { const val TAG = "CHABAK_SPIKE" }
}
