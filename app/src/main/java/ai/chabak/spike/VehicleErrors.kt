package ai.chabak.spike

/**
 * onFailed 로 받은 Exception 에서 Pleos ErrorCode 이름을 꺼낸다.
 * 문서상 VehicleException.errorCode 이지만, 2.3.2 배포 아티팩트에서 해당 타입이 컴파일 클래스패스에
 * 노출되지 않는다는 보고가 있어 리플렉션으로 읽는다. 못 읽으면 null — 절대 "미지원"으로 단정하지 않는다.
 */
object VehicleErrors {
    fun codeOf(e: Exception): String? = runCatching {
        val m = e.javaClass.methods.firstOrNull { it.name == "getErrorCode" && it.parameterCount == 0 } ?: return null
        m.invoke(e)?.toString()
    }.getOrNull()

    fun classify(e: Exception): String = when (codeOf(e)) {
        "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA" -> "UNSUPPORTED"
        "ERROR_SECURITY_PERMISSION" -> "PERMISSION_DENIED"
        "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE" -> "SERVICE_DOWN"
        "ERROR_EMPTY_RESULT" -> "NO_VALUE"
        "ERROR_DRIVING_RESTRICTED" -> "DRIVING_RESTRICTED"
        null -> "UNKNOWN_EXCEPTION(${e.javaClass.simpleName}: ${e.message})"
        else -> "OTHER(${codeOf(e)})"
    }
}
