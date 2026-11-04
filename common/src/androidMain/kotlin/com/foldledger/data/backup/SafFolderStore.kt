package com.foldledger.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

data class RemoteFile(
    val docId: String,
    val name: String,
    val size: Long,
    val lastModified: Long
)

/**
 * 用 SAF（Storage Access Framework）操作一个被授权的目录。
 *
 * 为什么不直接用 MediaStore 写 Download：
 * 官方文档明确 —— 一个 App **只能读它自己创建**的 MediaStore.Downloads 文件；
 * 卸载重装后新实例不继承旧实例的文件所有权，旧备份会变得不可读。
 * 因此「卸载后仍能恢复」必须走 SAF + takePersistableUriPermission。
 *
 * 授权只在首次（以及重装后首次）需要，之后全程一键。
 */
class SafFolderStore(private val context: Context) {

    fun takePersistablePermission(treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    }.getOrDefault(false)

    fun releasePermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** 是否仍然持有该目录的持久化授权（重装后会失效） */
    fun hasPermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    /** "primary:Download" -> "Download"，用于界面上显示备份位置 */
    fun folderLabel(treeUri: Uri): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val path = docId.substringAfter(':', docId)
        if (path.isBlank()) "存储根目录" else path.trimEnd('/').substringAfterLast('/')
    }.getOrDefault("已选目录")

    fun listFiles(treeUri: Uri): List<RemoteFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = mutableListOf<RemoteFile>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out += RemoteFile(
                        docId = docId,
                        name = name,
                        size = c.getLong(2),
                        lastModified = c.getLong(3)
                    )
                }
            }
        }
        return out
    }

    fun findFile(treeUri: Uri, name: String): RemoteFile? =
        listFiles(treeUri).firstOrNull { it.name == name }

    /**
     * 覆盖写入：父目录里同名的先删掉再新建。
     * 不这么做的话，多数 DocumentsProvider 会自动改名成 "xxx (1).json"，
     * 自动备份就会越积越多。
     */
    fun writeFile(treeUri: Uri, name: String, mime: String, bytes: ByteArray): Boolean {
        findFile(treeUri, name)?.let { delete(treeUri, it.docId) }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val created = runCatching {
            DocumentsContract.createDocument(context.contentResolver, parentUri, mime, name)
        }.getOrNull() ?: return false

        return runCatching {
            context.contentResolver.openOutputStream(created)?.use { out ->
                out.write(bytes)
                out.flush()
            }
            true
        }.getOrDefault(false)
    }

    fun read(treeUri: Uri, docId: String): ByteArray? = runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
    }.getOrNull()

    fun delete(treeUri: Uri, docId: String): Boolean = runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        DocumentsContract.deleteDocument(context.contentResolver, docUri)
    }.getOrDefault(false)
}
