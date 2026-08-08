package `in`.hridayan.ashell.shell

import android.content.Context
import android.util.Base64
import `in`.hridayan.ashell.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

data class AnnouncementInfo(
    val version: String,
    val author: String,
    val announcement: String,
)

data class StartupVerification(
    val announcement: AnnouncementInfo,
    val serverVersionCode: Int,
    val authorized: Boolean,
    val reason: String,
    val permissions: Int,
) {
    val channelVerificationRequired: Boolean
        get() = !authorized && reason in CHANNEL_REASONS

    private companion object {
        val CHANNEL_REASONS = setOf(
            "device_not_verified",
            "device_key_not_bound",
            "device_key_mismatch",
        )
    }
}

sealed interface StartupVerificationResult {
    data object EndpointNotConfigured : StartupVerificationResult
    data class Success(val verification: StartupVerification) : StartupVerificationResult
    data class Failure(val reason: String) : StartupVerificationResult
}

sealed interface ChannelVerificationResult {
    data object Authorized : ChannelVerificationResult
    data class Pending(
        val verifyCode: String,
        val expireInSeconds: Int,
        val message: String,
    ) : ChannelVerificationResult
    data class Failure(val reason: String) : ChannelVerificationResult
}

/**
 * DMKPZ 启动安全验证。
 *
 * 客户端为每次请求生成新的时间戳与 nonce，并使用安装级 Ed25519 设备密钥签名；
 * 启动响应必须通过内置服务端公钥验签后，版本、公告和频道授权状态才会被采用。
 */
class HttpStartupVerificationApi(context: Context) {
    private val appContext = context.applicationContext

    suspend fun verifyStartup(): StartupVerificationResult = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.SERVER_PROTOCOL_V2_ENDPOINT.trim()
        val moduleId = BuildConfig.SERVER_MODULE_ID.trim()
        val serverPublicKey = BuildConfig.SERVER_PROTOCOL_V2_PUBLIC_KEY.trim()
        val serverKeyId = BuildConfig.SERVER_PROTOCOL_V2_KEY_ID.trim()
        if (endpoint.isEmpty() || moduleId.isEmpty() ||
            serverPublicKey.isEmpty() || serverKeyId.isEmpty()) {
            return@withContext StartupVerificationResult.EndpointNotConfigured
        }

        runCatching {
            OfficialAppSignature.requireOfficial(appContext)
            val proof = createProof("bootstrap", "")
            val response = postForm(
                appendAction(endpoint, "bootstrap_v2"),
                proof.toForm(moduleId),
            )
            if (response.code !in 200..299) {
                throw VerificationException(httpFailureMessage(response.code))
            }
            parseSignedStartupResponse(
                response.body,
                proof,
                moduleId,
                serverPublicKey,
                serverKeyId,
            )
        }.getOrElse { error ->
            StartupVerificationResult.Failure(error.toSafeMessage())
        }
    }

    suspend fun requestChannelAuthorization(): ChannelVerificationResult = withContext(Dispatchers.IO) {
        requestChannelAuthorizationInternal(allowIdentityReset = true)
    }

    private fun requestChannelAuthorizationInternal(
        allowIdentityReset: Boolean,
    ): ChannelVerificationResult {
        val endpoint = BuildConfig.SERVER_CHANNEL_ENDPOINT.trim()
        val moduleId = BuildConfig.SERVER_MODULE_ID.trim()
        if (endpoint.isEmpty() || moduleId.isEmpty()) {
            return ChannelVerificationResult.Failure("频道验证接口尚未配置")
        }

        return runCatching {
            OfficialAppSignature.requireOfficial(appContext)
            val proof = createProof("channel_auth", "")
            val response = postForm(endpoint, proof.toForm(moduleId))
            val json = response.body.takeIf(String::isNotBlank)?.let(::JSONObject)
                ?: throw VerificationException(httpFailureMessage(response.code))
            val code = json.optInt("code", response.code)
            if (code == 200) return@runCatching ChannelVerificationResult.Authorized

            val reason = json.optString("reason").trim()
            if (allowIdentityReset && reason in KEY_CONFLICT_REASONS) {
                ClientIdentity.resetSigningIdentity(appContext)
                return requestChannelAuthorizationInternal(allowIdentityReset = false)
            }

            val verifyCode = json.optString("verify_code").trim()
            if (verifyCode.isNotEmpty()) {
                return@runCatching ChannelVerificationResult.Pending(
                    verifyCode = verifyCode,
                    expireInSeconds = json.optInt("expire_in", 300).coerceAtLeast(0),
                    message = json.optString("msg").trim(),
                )
            }
            throw VerificationException(channelFailureMessage(reason, response.code))
        }.getOrElse { error ->
            ChannelVerificationResult.Failure(error.toSafeMessage())
        }
    }

    private fun createProof(purpose: String, subject: String): DeviceProof {
        val identity = ClientIdentity.loadOrCreateSigningIdentity(appContext)
        val publicKeyB64 = encodeBase64Url(identity.publicKey)
        val timestamp = System.currentTimeMillis() / 1000L
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes).let(::encodeBase64Url)
        val moduleId = BuildConfig.SERVER_MODULE_ID.trim()
        val message = buildString {
            append("dmkpz-device-proof-v2\n")
            append(purpose).append('\n')
            append(subject).append('\n')
            append(moduleId).append('\n')
            append(BuildConfig.VERSION_CODE).append('\n')
            append(identity.deviceId).append('\n')
            append(publicKeyB64).append('\n')
            append(timestamp).append('\n')
            append(nonce)
        }.toByteArray(StandardCharsets.UTF_8)

        val signer = Ed25519Signer()
        signer.init(true, identity.privateKey)
        signer.update(message, 0, message.size)
        val signature = signer.generateSignature()
        return DeviceProof(
            deviceId = identity.deviceId,
            publicKeyB64 = publicKeyB64,
            timestamp = timestamp,
            nonce = nonce,
            signatureB64 = encodeBase64Url(signature),
        )
    }

    private fun DeviceProof.toForm(moduleId: String): Map<String, String> = linkedMapOf(
        "protocol" to "2",
        "module_id" to moduleId,
        "version_code" to BuildConfig.VERSION_CODE.toString(),
        "device_id" to deviceId,
        "device_public_key" to publicKeyB64,
        "device_timestamp" to timestamp.toString(),
        "device_nonce" to nonce,
        "device_signature" to signatureB64,
    )

    private fun parseSignedStartupResponse(
        body: String,
        proof: DeviceProof,
        moduleId: String,
        expectedPublicKey: String,
        expectedKeyId: String,
    ): StartupVerificationResult {
        val envelope = JSONObject(body)
        if (envelope.optInt("protocol") != 2 ||
            envelope.optString("key_id") != expectedKeyId) {
            throw VerificationException("服务器签名身份不匹配")
        }
        val payloadBytes = decodeBase64Url(envelope.optString("payload"))
        val signature = decodeBase64Url(envelope.optString("signature"))
        val publicKey = decodeBase64Url(expectedPublicKey)
        if (publicKey.size != ED25519_PUBLIC_KEY_BYTES ||
            signature.size != ED25519_SIGNATURE_BYTES) {
            throw VerificationException("服务器签名格式无效")
        }

        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(payloadBytes, 0, payloadBytes.size)
        if (!verifier.verifySignature(signature)) {
            throw VerificationException("服务器响应签名验证失败")
        }

        val payload = JSONObject(String(payloadBytes, StandardCharsets.UTF_8))
        val now = System.currentTimeMillis() / 1000L
        if (payload.optString("module_id") != moduleId ||
            payload.optString("device_id") != proof.deviceId ||
            payload.optString("request_nonce") != proof.nonce ||
            payload.optLong("issued_at", 0L) > now + CLOCK_SKEW_SECONDS ||
            payload.optLong("expires_at", 0L) < now - CLOCK_SKEW_SECONDS) {
            throw VerificationException("服务器响应与本次设备请求不匹配")
        }

        val serverVersionCode = payload.optInt("version_code", 0)
        if (serverVersionCode <= 0) {
            throw VerificationException("服务器未配置有效版本号")
        }
        val announcement = decodeAnnouncement(
            payload.optString("announcement_b64"),
            payload.optString("version_name"),
            serverVersionCode,
        )
        return StartupVerificationResult.Success(
            StartupVerification(
                announcement = announcement,
                serverVersionCode = serverVersionCode,
                authorized = payload.optInt("authorized", 0) == 1,
                reason = payload.optString("reason").trim(),
                permissions = payload.optInt("permissions", 0),
            ),
        )
    }

    private fun decodeAnnouncement(
        encoded: String,
        versionName: String,
        versionCode: Int,
    ): AnnouncementInfo {
        val json = runCatching {
            JSONObject(String(decodeBase64Url(encoded), StandardCharsets.UTF_8))
        }.getOrDefault(JSONObject())
        return AnnouncementInfo(
            version = versionName.trim().ifEmpty {
                if (versionCode == BuildConfig.VERSION_CODE) BuildConfig.VERSION_NAME
                else "versionCode $versionCode"
            },
            author = json.optString("author").trim(),
            announcement = json.optString("message")
                .ifBlank { json.optString("announcement") }
                .trim(),
        )
    }

    private fun postForm(url: String, values: Map<String, String>): HttpResponse {
        val target = URL(url)
        if (!target.protocol.equals("https", ignoreCase = true)) {
            throw VerificationException("验证接口必须使用 HTTPS")
        }
        val body = values.entries.joinToString("&") { (key, value) ->
            "${encodeForm(key)}=${encodeForm(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val connection = target.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty(
                "User-Agent",
                "YueHongPrivilegeAssistant/${BuildConfig.VERSION_NAME}",
            )
            connection.outputStream.use { it.write(body) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            HttpResponse(responseCode, stream?.readUtf8Limited().orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readUtf8Limited(): String = use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw VerificationException("服务器响应过大")
            }
            output.write(buffer, 0, read)
        }
        output.toString(StandardCharsets.UTF_8.name())
    }

    private fun appendAction(endpoint: String, action: String): String =
        endpoint + if ('?' in endpoint) "&action=$action" else "?action=$action"

    private fun encodeForm(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun encodeBase64Url(value: ByteArray): String = Base64.encodeToString(
        value,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    private fun decodeBase64Url(value: String): ByteArray {
        if (value.isBlank() || !BASE64_URL_PATTERN.matches(value)) {
            throw VerificationException("服务器签名编码无效")
        }
        return runCatching {
            Base64.decode(
                value,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
        }.getOrElse { throw VerificationException("服务器签名编码无效") }
    }

    private fun Throwable.toSafeMessage(): String = when (this) {
        is SecurityException -> message.orEmpty().ifBlank { "客户端安全验证失败" }
        is VerificationException -> message.orEmpty().ifBlank { "安全验证失败" }
        else -> "无法连接验证服务器，请检查网络后重试"
    }

    private fun httpFailureMessage(code: Int): String = when (code) {
        401, 403 -> "验证请求被服务器拒绝"
        404 -> "服务器尚未部署提权助手验证接口"
        409 -> "客户端版本或设备状态不一致"
        429 -> "验证请求过于频繁，请稍后再试"
        in 500..599 -> "验证服务器暂时不可用"
        else -> "验证服务器返回异常（HTTP $code）"
    }

    private fun channelFailureMessage(reason: String, code: Int): String = when (reason) {
        "bmd_device_key_mismatch" -> "设备密钥与服务器白名单记录不一致，请联系管理员处理"
        "device_clock_out_of_range" -> "设备时间与服务器相差过大，请校准系统时间"
        "rate_limited" -> "频道验证请求过于频繁，请稍后再试"
        else -> httpFailureMessage(code)
    }

    private data class DeviceProof(
        val deviceId: String,
        val publicKeyB64: String,
        val timestamp: Long,
        val nonce: String,
        val signatureB64: String,
    )

    private data class HttpResponse(val code: Int, val body: String)

    private class VerificationException(message: String) : Exception(message)

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val NONCE_BYTES = 24
        private const val CLOCK_SKEW_SECONDS = 30L
        private const val ED25519_PUBLIC_KEY_BYTES = 32
        private const val ED25519_SIGNATURE_BYTES = 64
        private val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
        private val KEY_CONFLICT_REASONS = setOf(
            "public_key_already_bound",
            "public_key_request_conflict",
        )
    }
}
