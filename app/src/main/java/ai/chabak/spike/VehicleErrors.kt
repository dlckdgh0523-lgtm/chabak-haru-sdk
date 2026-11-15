package ai.chabak.spike

/**
 * onFailed 로 받은 Exception 에서 Pleos ErrorCode 이름을 꺼낸다.
 * 2.3.2 아티팩트에서 예외는 난독화된 ai.pleos.playground.vehicle.callback.exception.a 이고, 코드 접근자도 a() 다.
 * ErrorCode enum 은 vehicle-data(runtime 의존성)에 있어 컴파일 클래스패스에 없다 → 반환 타입 이름으로 찾는다.
 * 못 읽으면 null — 절대 "미지원"으로 단정하지 않는다.
 */
object VehicleErrors {
    const val NO_ACCESS = "NO_VEHICLE_ACCESS"

    fun codeOf(e: Exception): String? = runCatching {
        val m = e.javaClass.methods.firstOrNull { it.parameterCount == 0 && (it.name == "getErrorCode" || it.returnType.simpleName == "ErrorCode") } ?: return null
        m.invoke(e)?.toString()
    }.getOrNull()

    fun classify(e: Exception): String = when (codeOf(e)) {
        "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA" -> "UNSUPPORTED"
        "ERROR_SECURITY_PERMISSION" -> "PERMISSION_DENIED"
        "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE" -> "SERVICE_DOWN"
        "ERROR_EMPTY_RESULT" -> "NO_VALUE"
        "ERROR_DRIVING_RESTRICTED" -> "DRIVING_RESTRICTED"
        null -> if (e is SecurityException) NO_ACCESS else "UNKNOWN_EXCEPTION(${e.javaClass.simpleName}: ${e.message})"
        else -> "OTHER(${codeOf(e)})"
    }
}
