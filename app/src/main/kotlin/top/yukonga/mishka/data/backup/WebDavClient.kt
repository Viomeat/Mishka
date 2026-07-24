package top.yukonga.mishka.data.backup

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

class WebDavException(message: String) : Exception(message)

/**
 * 极简 WebDAV 客户端：固定文件名覆盖式备份，只需 MKCOL / PUT / GET 三个动词，
 * 不做 PROPFIND 列目录（无多版本历史）。HttpClient 每次操作短生命周期构建、用完即 close。
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    password: String,
) {
    private val root = baseUrl.trim().trimEnd('/')
    private val dirUrl = "$root/$BACKUP_DIR"
    private val fileUrl = "$dirUrl/$BACKUP_FILE"
    private val authorization = "Basic " + Base64.encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
    )

    private fun buildClient() = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 300_000
        }
    }

    /** 上传备份（覆盖写）。目录不存在时先 MKCOL 创建。 */
    suspend fun upload(bytes: ByteArray) {
        buildClient().use { client ->
            client.ensureDir()
            val resp = client.put(fileUrl) {
                header(HttpHeaders.Authorization, authorization)
                header(HttpHeaders.ContentType, "application/zip")
                setBody(bytes)
            }
            if (!resp.status.isSuccess()) {
                throw WebDavException("PUT failed: HTTP ${resp.status.value}")
            }
        }
    }

    /** 下载备份；服务器上尚无备份（404）返回 null。 */
    suspend fun download(): ByteArray? {
        buildClient().use { client ->
            val resp = client.get(fileUrl) {
                header(HttpHeaders.Authorization, authorization)
            }
            return when {
                resp.status == HttpStatusCode.NotFound -> null
                resp.status.isSuccess() -> resp.body<ByteArray>()
                else -> throw WebDavException("GET failed: HTTP ${resp.status.value}")
            }
        }
    }

    /** 测试连接：确保备份目录可建/已存在。地址、凭据、权限任一有问题都会在此抛出。 */
    suspend fun testConnection() {
        buildClient().use { client ->
            client.ensureDir()
        }
    }

    private suspend fun HttpClient.ensureDir() {
        val resp = request(dirUrl) {
            method = HttpMethod("MKCOL")
            header(HttpHeaders.Authorization, authorization)
        }
        // 201 = 新建成功；405 = 目录已存在（RFC 4918：MKCOL 对已存在资源必须 405）
        val ok = resp.status.isSuccess() || resp.status == HttpStatusCode.MethodNotAllowed
        if (!ok) {
            val hint = when (resp.status) {
                HttpStatusCode.Unauthorized -> "unauthorized"
                HttpStatusCode.Conflict -> "parent directory missing"
                else -> "HTTP ${resp.status.value}"
            }
            throw WebDavException("MKCOL failed: $hint")
        }
    }

    companion object {
        const val BACKUP_DIR = "Mishka"
        const val BACKUP_FILE = "mishka-backup.zip"
    }
}
