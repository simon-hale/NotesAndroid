package com.notes.notes.data

import com.notes.notes.BuildConfig
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.DirectoryEntry
import com.notes.notes.core.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DirectoryListing(
    val directories: List<DirectoryEntry>,
    val files: List<FileEntry>,
)

data class RootDirectoryResult(
    val rootId: Long,
    val listing: DirectoryListing,
)

data class FilePreviewDescriptor(
    val url: String,
    val type: String,
)

data class OssStsToken(
    val overwriteSameName: Boolean,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val accessKeySecret: String,
    val securityToken: String,
    val objectKey: String,
)

sealed class NotesServiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object MissingBaseUrl : NotesServiceException("BASE_URL is missing")
    data class Http(val statusCode: Int, val responseBody: String) :
        NotesServiceException("HTTP $statusCode")

    data class Business(val errorMessage: String) : NotesServiceException(errorMessage)
    data class Parse(val raw: String, val reason: Throwable? = null) : NotesServiceException("Parse failed", reason)
}

class NotesBackendService {

    private val baseUrl: String
        get() = BuildConfig.NOTES_BASE_URL.trim().removeSuffix("/")

    suspend fun login(username: String, password: String): String {
        val json = requestJson(
            path = "/api/user/token/",
            method = "POST",
            form = mapOf(
                "username" to username,
                "password" to password,
            ),
        )
        ensureBusinessSuccess(json)
        return json.optString("token")
    }

    suspend fun autoLogin(accessToken: String) {
        val json = requestJson(
            path = "/api/user/auto-login/",
            method = "POST",
            bearerToken = accessToken,
        )
        ensureBusinessSuccess(json)
    }

    suspend fun register(
        username: String,
        password: String,
        confirmedPassword: String,
        language: AppLanguage,
    ) {
        val json = requestJson(
            path = "/api/user/register/",
            method = "POST",
            form = mapOf(
                "username" to username,
                "password" to password,
                "confirmedPassword" to confirmedPassword,
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
    }

    suspend fun changePassword(
        accessToken: String,
        curPassword: String,
        password: String,
        confirmedPassword: String,
        language: AppLanguage,
    ) {
        val json = requestJson(
            path = "/api/user/update/password/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "cur_password" to curPassword,
                "password" to password,
                "confirmedPassword" to confirmedPassword,
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
    }

    suspend fun deleteAccount(accessToken: String, curPassword: String, language: AppLanguage) {
        val json = requestJson(
            path = "/api/user/delete/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "cur_password" to curPassword,
                "language" to language.code,
            ),
        )
        val message = json.optString("error_message")
        if (message != "success" && message != "该用户已被删除") {
            throw NotesServiceException.Business(message.ifBlank { "Unknown error" })
        }
    }

    suspend fun loadRoot(accessToken: String, language: AppLanguage): RootDirectoryResult {
        val json = requestJson(
            path = "/api/directory/init/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
        return RootDirectoryResult(
            rootId = json.optLongFlexible("root_id"),
            listing = json.toDirectoryListing(),
        )
    }

    suspend fun loadDirectory(accessToken: String, parentId: Long, language: AppLanguage): DirectoryListing {
        val json = requestJson(
            path = "/api/directory/id/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "parent_id" to parentId.toString(),
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
        return json.toDirectoryListing()
    }

    suspend fun createDirectory(
        accessToken: String,
        parentId: Long,
        name: String,
        language: AppLanguage,
    ) {
        val json = requestJson(
            path = "/api/directory/create/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "name" to name,
                "parent_id" to parentId.toString(),
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
    }

    suspend fun renameDirectory(accessToken: String, directoryId: Long, name: String, language: AppLanguage) {
        val json = requestJson(
            path = "/api/directory/modify/name/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "id" to directoryId.toString(),
                "name" to name,
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
    }

    suspend fun renameFile(
        accessToken: String,
        parentId: Long,
        fileId: Long,
        newName: String,
        language: AppLanguage,
    ) {
        val json = requestJson(
            path = "/api/file/modify/name/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "parentId" to parentId.toString(),
                "fileId" to fileId.toString(),
                "filenameNew" to newName,
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
    }

    suspend fun deleteDirectory(accessToken: String, directoryId: Long, language: AppLanguage) {
        val json = requestJson(
            path = "/api/directory/delete/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "id" to directoryId.toString(),
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
    }

    suspend fun deleteFile(accessToken: String, fileId: Long, language: AppLanguage) {
        val json = requestJson(
            path = "/api/file/delete/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "id" to fileId.toString(),
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
    }

    suspend fun getFilePreview(
        accessToken: String,
        fileId: Long,
        language: AppLanguage,
    ): FilePreviewDescriptor {
        val json = requestJson(
            path = "/api/file/url/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "id" to fileId.toString(),
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
        return FilePreviewDescriptor(
            url = json.optString("url"),
            type = json.optString("type"),
        )
    }

    suspend fun requestOssSts(
        accessToken: String,
        pathString: String,
        filename: String,
        parentId: Long,
        language: AppLanguage,
        usage: String,
    ): OssStsToken {
        val json = requestJson(
            path = "/api/oss/sts/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "string_of_path" to pathString,
                "filename" to filename,
                "parent_id" to parentId.toString(),
                "language" to language.code,
                "usage" to usage,
            ),
        )
        val message = json.optString("error_message")
        if (message != "success" && message != "same_file_name") {
            throw NotesServiceException.Business(message.ifBlank { "Unknown error" })
        }
        return OssStsToken(
            overwriteSameName = message == "same_file_name",
            region = json.optString("region"),
            bucket = json.optString("bucket"),
            accessKeyId = json.optString("accessKeyId"),
            accessKeySecret = json.optString("accessKeySecret"),
            securityToken = json.optString("securityToken"),
            objectKey = json.optString("objectKey"),
        )
    }

    suspend fun insertFileInfo(
        accessToken: String,
        pathString: String,
        filename: String,
        parentId: Long,
        language: AppLanguage,
    ) {
        val json = requestJson(
            path = "/api/file/insert/",
            method = "POST",
            bearerToken = accessToken,
            form = mapOf(
                "string_of_path" to pathString,
                "filename" to filename,
                "parent_id" to parentId.toString(),
                "language" to language.code,
            ),
        )
        ensureBusinessSuccess(json)
    }

    suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val connection = openRawConnection(url)
        try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw NotesServiceException.Http(statusCode, body)
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadToFile(url: String, destination: File) = withContext(Dispatchers.IO) {
        val parentDir = destination.parentFile?.apply { mkdirs() }
        val tempFile = File.createTempFile(
            destination.nameWithoutExtension.takeIf { it.length >= 3 } ?: "tmp",
            ".download",
            parentDir,
        )
        val connection = openRawConnection(url)
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw NotesServiceException.Http(statusCode, body)
            }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(tempFile.outputStream()).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            moveDownloadedFile(tempFile, destination)
        } finally {
            connection.disconnect()
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    suspend fun downloadToOutput(url: String, outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val connection = openRawConnection(url)
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw NotesServiceException.Http(statusCode, body)
            }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(outputStream).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestJson(
        path: String,
        method: String,
        form: Map<String, String> = emptyMap(),
        bearerToken: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        ensureBaseUrl()
        val connection = createConnection(path, method, form, bearerToken)
        try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw NotesServiceException.Http(statusCode, body)
            }
            try {
                JSONObject(body)
            } catch (exception: JSONException) {
                throw NotesServiceException.Parse(body, exception)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun createConnection(
        path: String,
        method: String,
        form: Map<String, String>,
        bearerToken: String?,
    ): HttpURLConnection {
        val encodedForm = encodeForm(form)
        val targetUrl = if (method.equals("GET", ignoreCase = true)) {
            val queryPrefix = if (encodedForm.isEmpty()) "" else "?$encodedForm"
            URL("$baseUrl$path$queryPrefix")
        } else {
            URL("$baseUrl$path")
        }
        return (targetUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (!bearerToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            if (!method.equals("GET", ignoreCase = true)) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                outputStream.use { output ->
                    output.write(encodedForm.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun openRawConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }

    private fun moveDownloadedFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun encodeForm(form: Map<String, String>): String = form.entries.joinToString("&") { entry ->
        "${entry.key.urlEncode()}=${entry.value.urlEncode()}"
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun ensureBusinessSuccess(json: JSONObject) {
        val message = json.optString("error_message")
        if (message != "success") {
            throw NotesServiceException.Business(message.ifBlank { "Unknown error" })
        }
    }

    private fun ensureBaseUrl() {
        if (baseUrl.isBlank()) {
            throw NotesServiceException.MissingBaseUrl
        }
    }

    private fun JSONObject.toDirectoryListing(): DirectoryListing = DirectoryListing(
        directories = optJSONArray("directories").toDirectoryEntries(),
        files = optJSONArray("files").toFileEntries(),
    )

    private fun JSONArray?.toDirectoryEntries(): List<DirectoryEntry> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    DirectoryEntry(
                        id = item.optLongFlexible("id"),
                        name = item.optString("name"),
                    )
                )
            }
        }
    }

    private fun JSONArray?.toFileEntries(): List<FileEntry> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    FileEntry(
                        id = item.optLongFlexible("id"),
                        name = item.optString("name"),
                        type = item.optString("type"),
                        creationTime = item.optString("creation_time"),
                        lastModifiedTime = item.optString("last_modified_time"),
                    )
                )
            }
        }
    }

    private fun JSONObject.optLongFlexible(key: String): Long {
        val raw = opt(key)
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
