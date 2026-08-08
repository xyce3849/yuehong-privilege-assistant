package `in`.hridayan.ashell.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import `in`.hridayan.ashell.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface CompatibilityResult {
    data object EndpointNotConfigured : CompatibilityResult
    data object DeviceNotSupported : CompatibilityResult
    data class Success(val responseBody: String) : CompatibilityResult
    data class Failure(val reason: String) : CompatibilityResult
}

interface CompatibilityApi {
    fun check(
        profile: DeviceProfile,
        onResult: (CompatibilityResult) -> Unit,
    )
}

class HttpCompatibilityApi(context: Context) : CompatibilityApi, AutoCloseable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun check(
        profile: DeviceProfile,
        onResult: (CompatibilityResult) -> Unit,
    ) {
        val endpoint = BuildConfig.SERVER_COMPATIBILITY_ENDPOINT.trim()
        if (endpoint.isEmpty()) {
            onResult(CompatibilityResult.EndpointNotConfigured)
            return
        }

        executor.execute {
            val result = runCatching { postProfile(endpoint, profile) }
                .getOrElse { error ->
                    CompatibilityResult.Failure(
                        error.message?.takeIf(String::isNotBlank)
                            ?: error.javaClass.simpleName,
                    )
                }
            mainHandler.post { onResult(result) }
        }
    }

    private fun postProfile(
        endpoint: String,
        profile: DeviceProfile,
    ): CompatibilityResult {
        OfficialAppSignature.requireOfficial(appContext)
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Compatibility endpoint must use HTTPS"
        }

        val proof = DeviceRequestProofFactory.create(
            appContext,
            "ashell_compatibility",
            DeviceRequestProofFactory.compatibilitySubject(profile),
        )
        val requestBody = JSONObject(profile.toJson())
            .put("moduleId", BuildConfig.SERVER_MODULE_ID)
            .put("matchMode", "exact")
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("versionName", BuildConfig.VERSION_NAME)
            .also { proof.putInto(it) }
            .toString()

        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.readUtf8Limited().orEmpty()

            when {
                responseCode in 200..299 -> {
                    val payload = SignedServerResponse.verify(body, proof)
                    if (isDeviceNotSupportedResponse(payload)) {
                        CompatibilityResult.DeviceNotSupported
                    } else {
                        CompatibilityResult.Success(payload.toString())
                    }
                }
                else -> CompatibilityResult.Failure(
                    httpFailureMessage(responseCode),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isDeviceNotSupportedResponse(json: JSONObject): Boolean {
        return json.optString("errorCode") == DEVICE_NOT_SUPPORTED_ERROR_CODE &&
            !json.optBoolean("compatible", true)
    }

    private fun InputStream.readUtf8Limited(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= MAX_RESPONSE_BYTES) { "Compatibility response is too large" }
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun httpFailureMessage(code: Int): String = when (code) {
        401, 403 -> "服务器拒绝了设备安全验证"
        409 -> "设备授权状态或兼容档案发生冲突"
        429 -> "请求过于频繁，请稍后重试"
        in 500..599 -> "提权服务器暂时不可用"
        else -> "提权服务器返回异常（HTTP $code）"
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val DEVICE_NOT_SUPPORTED_ERROR_CODE = "device_not_supported"
        private const val DEVICE_NOT_SUPPORTED_MESSAGE = "当前机型未适配"
    }
}
