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

/**
 * 同步传输：POST JSON 到自托管服务端（[SyncHttp] 协议见 sync-server/server.js）。
 *
 * 用 java.net.HttpURLConnection —— 双端都是 JVM 目标，零新增依赖；
 * Android 需 manifest 允许明文 http（自托管多为内网 http，见 AndroidManifest 注释）。
 */
class SyncHttp(private val baseUrl: String) {

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + path)
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
