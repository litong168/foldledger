package com.foldledger.data.sync

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 注册响应：服务端分配的设备 id + 权威时钟 */
data class RegisterResponse(val deviceId: Long, val serverTime: Long)

/** 同步响应：服务端时钟 + 增量行（含墓碑）+ 本次合并数 */
data class SyncResponse(val serverTime: Long, val rows: List<SyncRow>, val merged: Int)

/** HTTP 层错误（非 2xx） */
class SyncHttpException(val code: Int, body: String) :
    Exception("服务器返回 $code：${body.take(200)}")

/** 地址本身不合法（给用户看的话，不是给堆栈看的） */
class SyncUrlException(message: String) : Exception(message)

/**
 * 把用户填的服务器地址收拾成能用的形态（v16.4.2）。
 *
 * ### 为什么需要它
 * 中文输入法下打 `:` 和数字，很容易打出**全角字符**（`：` `０` `．` `／`）——
 * 屏幕上和半角几乎分辨不出来，但 `java.net.URL` 会直接抛
 * `MalformedURLException: Error at index 5 in: ":10097"`：一句既看不出哪里错、
 * 也看不出该怎么办的话。这个坑在 v16.4.1 里让用户连"注册此设备"都点不过去。
 *
 * 所以这里做三件事，全部是**静默自动纠正**，不打断用户：
 *  1. 全角标点/数字 → 半角；
 *  2. 裁掉首尾空白（复制粘贴最常带进来的东西）和零宽字符；
 *  3. 没写协议时补 `http://`（自托管同步服务基本都是内网 http）。
 *
 * 仍然不合法的（比如 `http://` 后面什么都没有），由 [SyncHttp.post] 抛出
 * [SyncUrlException]，把**当前值原样**带进消息里，方便一眼看出问题。
 */
internal fun normalizeServerUrl(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        // 零宽字符直接丢掉（不是换成空格 —— 它本来就"看不见也不占位"）
        if (ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF') continue
        sb.append(
            when (ch) {
                '：' -> ':'
                '／' -> '/'
                '．', '。' -> '.'
                '－' -> '-'
                '　' -> ' '
                in '０'..'９' -> '0' + (ch - '０')
                else -> ch
            }
        )
    }
    var s = sb.toString().trim().trimEnd('/')
    if (s.isEmpty()) return s
    // 没写协议就补 http://（写了 https:// 的原样保留）
    val lower = s.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
        s = "http://" + s.substringAfter("://")
    }
    return s
}

/**
 * 同步传输：POST JSON 到自托管服务端（[SyncHttp] 协议见 sync-server/server.js）。
 *
 * 用 java.net.HttpURLConnection —— 双端都是 JVM 目标，零新增依赖；
 * Android 需 manifest 允许明文 http（自托管多为内网 http，见 AndroidManifest 注释）。
 */
class SyncHttp(private val rawBaseUrl: String) {

    /** 收拾过的地址：调用方填错的全角字符/首尾空白在这一层就被纠正 */
    private val baseUrl: String = normalizeServerUrl(rawBaseUrl)

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            throw SyncUrlException("服务器地址是空的：请填 http://主机:端口")
        }
        val url = try {
            URL(baseUrl + path)
        } catch (e: Exception) {
            // 原始值原样回显 —— 全角/不可见字符肉眼看不出来，只能让用户自己去比对
            throw SyncUrlException(
                "服务器地址格式不对：请填 http://主机:端口（不要带路径）。当前值：「${rawBaseUrl.trim()}」"
            )
        }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw SyncHttpException(code, raw)
            JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun register(token: String? = null): RegisterResponse {
        val body = JSONObject()
        if (!token.isNullOrBlank()) body.put("token", token)
        val r = post("/register", body)
        return RegisterResponse(r.getLong("deviceId"), r.getLong("serverTime"))
    }

    suspend fun sync(
        deviceId: Long,
        watermarks: SyncWatermarks,
        rows: List<SyncRow>,
        token: String? = null
    ): SyncResponse {
        val body = JSONObject()
        if (!token.isNullOrBlank()) body.put("token", token)
        body.put("deviceId", deviceId)
        val wm = JSONObject()
        TableNames.ALL.forEach { t -> wm.put(t, watermarks.get(t)) }
        body.put("watermarks", wm)
        val arr = org.json.JSONArray()
        rows.forEach { r ->
            arr.put(
                JSONObject()
                    .put("table", r.table)
                    .put("id", r.id)
                    .put("updatedAt", r.updatedAt)
                    .put("updatedByDeviceId", r.updatedByDeviceId)
                    .put("deletedAt", r.deletedAt)
                    .put("payload", r.payload)
            )
        }
        body.put("rows", arr)
        val r = post("/sync", body)
        val out = ArrayList<SyncRow>()
        val ra = r.optJSONArray("rows")
        if (ra != null) {
            for (i in 0 until ra.length()) {
                val o = ra.getJSONObject(i)
                out += SyncRow(
                    table = o.getString("table"),
                    id = o.getLong("id"),
                    updatedAt = o.getLong("updatedAt"),
                    updatedByDeviceId = o.getLong("updatedByDeviceId"),
                    deletedAt = o.optLong("deletedAt", 0L),
                    payload = o.getString("payload")
                )
            }
        }
        return SyncResponse(r.getLong("serverTime"), out, r.optInt("merged", 0))
    }
}
