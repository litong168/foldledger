package com.foldledger.data.backup

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件加密信封（AES-256-GCM + PBKDF2）。
 *
 * 安全取舍：
 *  - 密码**不落库**：只在导出时由用户临时输入、导入时再输入；App 本身不保存密码，
 *    因此即使备份文件被拿走，没有密码也解不开（比把密钥塞进 Keystore 更适合「导出文件」场景——
 *    因为导出文件要能跨设备、跨重装使用，不能绑死本机 Keystore）。
 *  - 信封头部是一段**明文 JSON**（magic + 摘要），列表页不输入密码也能显示「这是加密备份 / 几笔流水」，
 *    但读不到具体账本内容；真正的账本数据全部在 AES-GCM 密文里。
 *  - 与旧明文 `.foldledger.json` 完全共存：旧备份照常能列、能恢复；加密只是多一种「带密码」的导出形式，
 *    内层数据结构（JSON schema）与明文版逐字段一致，没有改动导出格式本身。
 *
 * 信封字节布局：
 *   [MAGIC 5B "FLDC1"][headerLen 1B][header JSON (headerLen B UTF-8)][salt 16B][iv 12B][ciphertext + GCM tag]
 */
object BackupCipher {
    private const val MAGIC = "FLDC1"
    private const val HEADER_VERSION = 1
    private const val KDF_ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val MIN_PASSPHRASE = 4

    data class EncHeader(
        val appVersion: String,
        val exportedAt: Long,
        val ledgerCount: Int,
        val txnCount: Int
    )

    /** 明文备份 JSON + 密码 + 摘要头 → 加密信封字节 */
    fun encrypt(plaintextJson: ByteArray, passphrase: String, header: EncHeader): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE) { "备份密码至少 $MIN_PASSPHRASE 位" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val headerJson = JSONObject().apply {
            put("format", "foldledger.backup.crypt")
            put("version", HEADER_VERSION)
            put("appVersion", header.appVersion)
            put("exportedAt", header.exportedAt)
            put("ledgerCount", header.ledgerCount)
            put("txnCount", header.txnCount)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(plaintextJson)
        }

        return ByteArrayOutputStream().apply {
            write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
            write(headerJson.size)
            write(headerJson)
            write(salt)
            write(iv)
            write(ciphertext)
        }.toByteArray()
    }

    /** 是否为本信封格式（只看文件开头几个字节） */
    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.length && bytes.copyOf(MAGIC.length).decodeToString() == MAGIC

    /** 不输入密码也能从信封头部读出摘要，供列表展示 */
    fun peekHeader(bytes: ByteArray): EncHeader? {
        if (!isEncrypted(bytes) || bytes.size < MAGIC.length + 1) return null
        val headerLen = bytes[MAGIC.length].toInt() and 0xFF
        val start = MAGIC.length + 1
        if (bytes.size < start + headerLen) return null
        val headerJson = runCatching {
            JSONObject(bytes.copyOfRange(start, start + headerLen).decodeToString())
        }.getOrNull() ?: return null
        if (headerJson.optString("format") != "foldledger.backup.crypt") return null
        return EncHeader(
            appVersion = headerJson.optString("appVersion", "?"),
            exportedAt = headerJson.optLong("exportedAt", 0L),
            ledgerCount = headerJson.optInt("ledgerCount", 0),
            txnCount = headerJson.optInt("txnCount", 0)
        )
    }

    /** 信封 → 明文备份 JSON。密码错误抛 [IllegalStateException]（调用方转成「密码不正确」） */
    fun decrypt(envelope: ByteArray, passphrase: String): ByteArray {
        if (!isEncrypted(envelope)) error("这不是加密备份")
        val headerLen = envelope[MAGIC.length].toInt() and 0xFF
        val start = MAGIC.length + 1
        val saltStart = start + headerLen
        if (envelope.size < saltStart + SALT_BYTES + IV_BYTES) error("加密备份文件不完整")
        val salt = envelope.copyOfRange(saltStart, saltStart + SALT_BYTES)
        val iv = envelope.copyOfRange(saltStart + SALT_BYTES, saltStart + SALT_BYTES + IV_BYTES)
        val ciphertext = envelope.copyOfRange(saltStart + SALT_BYTES + IV_BYTES, envelope.size)
        val key = deriveKey(passphrase, salt)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (_: Exception) {
            // AEADBadTagException → 密码不对 / 文件被改
            error("备份密码不正确，无法解密")
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, KDF_ITERATIONS, 256)
        val material = factory.generateSecret(spec).encoded
        return SecretKeySpec(material, "AES")
    }
}
