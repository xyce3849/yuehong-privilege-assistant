package roro.stellar.yuehong.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import roro.stellar.yuehong.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GhostLockOtaResult private constructor(
    val successful: Boolean,
    val otaUrl: String,
    val provider: String,
    val errorMessage: String,
) {
    companion object {
        fun success(url: String, provider: String): GhostLockOtaResult =
            GhostLockOtaResult(true, url, provider, "")

        fun failure(message: String): GhostLockOtaResult =
            GhostLockOtaResult(false, "", "", message)
    }
}

fun interface GhostLockOtaCallback {
    fun onResult(result: GhostLockOtaResult)
}

/**
 * Native Android implementation of the device-information-to-OTA flow.
 * It collects the same non-sensitive build properties, signs the request with
 * the app's installation Ed25519 identity, verifies the ROM server response,
 * and returns the current full OTA URL to GhostLock's existing parser.
 */
class GhostLockOtaApi(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun resolve(callback: GhostLockOtaCallback) {
        val endpoint = BuildConfig.ROM_PROTOCOL_V2_ENDPOINT.trim()
        val publicKey = BuildConfig.ROM_PROTOCOL_V2_PUBLIC_KEY.trim()
        val keyId = BuildConfig.ROM_PROTOCOL_V2_KEY_ID.trim()
        if (endpoint.isEmpty() || publicKey.isEmpty() || keyId.isEmpty()) {
            callback.onResult(GhostLockOtaResult.failure("ROM V2 接口尚未配置"))
            return
        }

        executor.execute {
            val result = runCatching {
                resolveBlocking(endpoint, publicKey, keyId)
            }.getOrElse { error ->
                GhostLockOtaResult.failure(
                    error.message?.trim().orEmpty().ifBlank { "无法连接 ROM V2 接口" },
                )
            }
            mainHandler.post { callback.onResult(result) }
        }
    }

    private fun resolveBlocking(
        endpoint: String,
        expectedPublicKey: String,
        expectedKeyId: String,
    ): GhostLockOtaResult {
        OfficialAppSignature.requireOfficial(appContext)
        val target = URL(endpoint)
        require(target.protocol.equals("https", ignoreCase = true)) {
            "ROM V2 接口必须使用 HTTPS"
        }

        val report = GhostLockDeviceReport.collect()
        val proof = DeviceRequestProofFactory.create(
            appContext,
            "rom_ota_resolve",
            report.subject(),
        )
        val requestBody = report.toJson()
            .put("protocol", 2)
            .put("moduleId", BuildConfig.SERVER_MODULE_ID)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("versionName", BuildConfig.VERSION_NAME)
            .also { proof.putInto(it) }
            .toString()

        val connection = target.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "YueHongPrivilegeAssistant/${BuildConfig.VERSION_NAME}",
            )
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.readUtf8Limited().orEmpty()
            if (responseCode !in 200..299) {
                val serverMessage = runCatching { JSONObject(responseBody).optString("message") }.getOrDefault("")
                throw IOException(serverMessage.ifBlank { "ROM 服务返回 HTTP $responseCode" })
            }

            val payload = SignedServerResponse.verify(
                responseBody,
                proof,
                expectedPublicKey,
                expectedKeyId,
            )
            if (payload.optInt("authorized", 0) != 1) {
                throw IOException("ROM V2 服务未授权本次请求")
            }
            if (payload.optInt("ota_available", 0) != 1) {
                val message = decodeBase64UrlText(payload.optString("message_b64"))
                throw IOException(message.ifBlank { "没有找到当前系统版本对应的 OTA 完整包" })
            }
            val otaUrl = decodeBase64UrlText(payload.optString("ota_url_b64"))
            val otaProtocol = runCatching { URL(otaUrl).protocol.lowercase(Locale.ROOT) }.getOrDefault("")
            if (otaProtocol != "https" && otaProtocol != "http") {
                throw IOException("ROM V2 服务返回了无效 OTA 链接")
            }
            GhostLockOtaResult.success(otaUrl, payload.optString("provider").trim())
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
            if (total > MAX_RESPONSE_BYTES) throw IOException("ROM V2 响应过大")
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun decodeBase64UrlText(value: String): String {
        if (value.isBlank() || !BASE64_URL_PATTERN.matches(value)) return ""
        return runCatching {
            String(
                Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                Charsets.UTF_8,
            ).trim()
        }.getOrDefault("")
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 90_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
        val BASE64_URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
    }
}

private data class GhostLockDeviceReport(
    val requestedPackage: String,
    val brand: String,
    val deviceName: String,
    val model: String,
    val codename: String,
    val systemVersion: String,
    val internalOtaVersion: String,
    val region: String,
    val androidVersion: String,
    val colorOSVersion: String,
    val romVersion: String,
    val carrierId: String,
    val kernelVersion: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("requestedPackage", requestedPackage)
        .put("brand", brand)
        .put("deviceName", deviceName)
        .put("model", model)
        .put("codename", codename)
        .put("systemVersion", systemVersion)
        .put("internalOtaVersion", internalOtaVersion)
        .put("region", region)
        .put("androidVersion", androidVersion)
        .put("colorOSVersion", colorOSVersion)
        .put("romVersion", romVersion)
        .put("carrierId", carrierId)
        .put("kernelVersion", kernelVersion)

    fun subject(): String {
        val canonical = listOf(
            requestedPackage,
            brand,
            deviceName,
            model,
            codename,
            systemVersion,
            internalOtaVersion,
            region,
            androidVersion,
            colorOSVersion,
            romVersion,
            carrierId,
            kernelVersion,
        ).joinToString("\n") { it.trim().replace(REPORT_WHITESPACE, " ") }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun collect(): GhostLockDeviceReport {
            val rawBrand = firstProp("ro.product.brand", "ro.product.vendor.brand", "ro.vendor.product.brand")
            val manufacturer = firstProp(
                "ro.product.manufacturer",
                "ro.product.vendor.manufacturer",
                "ro.vendor.product.manufacturer",
            )
            val rawModel = firstProp("ro.product.model", "ro.product.vendor.model", "ro.vendor.product.model")
            var deviceName = firstProp(
                "ro.product.marketname",
                "ro.product.vendor.marketname",
                "ro.vendor.product.marketname",
                "ro.oplus.market.name",
                "ro.product.model",
            )
            val androidRelease = firstProp("ro.build.version.release")
            val probe = "$rawBrand $manufacturer $rawModel $deviceName".lowercase(Locale.ROOT)
            val kernel = runCommand("/system/bin/uname", "-r")

            if (listOf("xiaomi", "redmi", "poco").any(probe::contains)) {
                val codename = firstProp(
                    "ro.product.device",
                    "ro.product.vendor.device",
                    "ro.vendor.product.device",
                )
                val systemVersion = firstProp(
                    "ro.build.version.incremental",
                    "ro.system.build.version.incremental",
                    "ro.build.display.id",
                )
                require(codename.isNotBlank() && systemVersion.isNotBlank()) {
                    "读取不到 Xiaomi 设备代号或系统版本"
                }
                if (deviceName.isBlank()) deviceName = rawModel.ifBlank { codename }
                val upper = systemVersion.uppercase(Locale.ROOT)
                val region = when {
                    upper.endsWith("CNXM") -> "CN"
                    upper.endsWith("MIXM") -> "Global"
                    upper.endsWith("EUXM") -> "EEA"
                    upper.endsWith("INXM") -> "India"
                    upper.endsWith("IDXM") -> "Indonesia"
                    upper.endsWith("RUXM") -> "Russia"
                    upper.endsWith("TWXM") -> "Taiwan"
                    upper.endsWith("TRXM") -> "Turkey"
                    upper.endsWith("JPXM") -> "Japan"
                    else -> ""
                }
                val brand = when {
                    probe.contains("poco") -> "POCO"
                    probe.contains("redmi") -> "Redmi"
                    else -> "Xiaomi"
                }
                return GhostLockDeviceReport(
                    "current", brand, deviceName, codename, codename,
                    systemVersion, "", region, androidRelease, "", systemVersion, "", kernel,
                )
            }

            val otaVersion = firstProp("ro.build.version.ota")
            require(otaVersion.isNotBlank() && otaVersion.contains("_11.")) {
                "当前设备不是受支持的 Xiaomi 或 OPlus 系统，或读取不到内部 OTA 版本"
            }
            val modelFromOta = otaVersion.substringBefore('_').ifBlank { rawModel }
            if (deviceName.isBlank()) deviceName = rawModel.ifBlank { modelFromOta }
            val systemVersion = firstProp(
                "ro.build.ota.versionname",
                "ro.build.display.id",
                "ro.build.version.incremental",
            ).ifBlank { otaVersion }
            val oplusRom = firstProp(
                "ro.build.version.oplusrom",
                "ro.build.version.opporom",
                "ro.build.version.realmeui",
            )
            val regionRaw = firstProp(
                "persist.sys.oplus.region",
                "persist.sys.oppo.region",
                "ro.oplus.regionmark",
                "ro.vendor.oplus.regionmark",
            ).uppercase(Locale.ROOT)
            val region = when (regionRaw) {
                "CN", "CHINA" -> "CN"
                "EU", "EEA", "EUEX" -> "EU"
                "IN", "INDIA" -> "IN"
                "SG", "SINGAPORE" -> "SG"
                "RU", "RUSSIA" -> "RU"
                "TR", "TURKEY" -> "TR"
                "TH", "THAILAND" -> "TH"
                else -> "GL"
            }
            val brand = when {
                probe.contains("realme") || modelFromOta.startsWith("RMX", true) -> "Realme"
                probe.contains("oneplus") || deviceName.contains("一加") -> "OnePlus"
                else -> "OPPO"
            }
            val colorOs = when {
                oplusRom.isBlank() -> ""
                oplusRom.startsWith("ColorOS", true) -> oplusRom
                oplusRom.startsWith("V", true) -> "ColorOS${oplusRom.drop(1)}"
                else -> "ColorOS$oplusRom"
            }
            val carrier = firstProp("ro.build.oplus_nv_id").takeIf { it.all(Char::isDigit) }.orEmpty()
            return GhostLockDeviceReport(
                "current", brand, deviceName, modelFromOta, "", systemVersion,
                otaVersion, region, androidRelease.takeIf(String::isNotBlank)
                    ?.let { "Android$it" }.orEmpty(),
                colorOs, systemVersion, carrier, kernel,
            )
        }

        private fun firstProp(vararg names: String): String {
            for (name in names) {
                val value = runCommand("/system/bin/getprop", name)
                if (value.isNotBlank()) return value
            }
            return ""
        }

        private fun runCommand(vararg command: String): String {
            val process = runCatching {
                ProcessBuilder(*command).redirectErrorStream(true).start()
            }.getOrNull() ?: return ""
            return try {
                if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) ""
                else process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            } catch (_: Throwable) {
                ""
            } finally {
                runCatching { process.destroy() }
            }
        }
    }
}

private val REPORT_WHITESPACE = Regex("\\s+")
