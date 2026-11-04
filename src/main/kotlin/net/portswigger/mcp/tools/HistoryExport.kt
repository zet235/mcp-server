package net.portswigger.mcp.tools

import burp.api.montoya.http.message.HttpMessage
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

const val MAX_SAVE_HISTORY_ITEMS = 50

private const val MAX_FILE_NAME_COMPONENT_LENGTH = 100
private const val MAX_SUMMARY_PATH_LENGTH = 300

private val exportJson = Json { prettyPrint = true }

/** Full, untruncated representation of one proxy history item as written to disk. */
@Serializable
data class SavedProxyHistoryItem(
    val historyIndex: Int,
    val id: Int?,
    val time: String?,
    val host: String,
    val port: Int,
    val secure: Boolean,
    val httpVersion: String?,
    val method: String,
    val path: String,
    val url: String?,
    val statusCode: Int?,
    val edited: Boolean,
    val notes: String?,
    val requestBase64: String,
    val responseBase64: String?,
    val requestText: String,
    val responseText: String?,
    /** Only set when Burp reports the item as edited: the request actually sent to the server. */
    val finalRequestBase64: String?,
    /** Only set when Burp reports the item as edited: the response as received from the server. */
    val originalResponseBase64: String?
)

/** Per-item result returned to the MCP client; deliberately excludes message contents. */
@Serializable
data class SavedProxyHistoryItemSummary(
    val historyIndex: Int,
    val file: String?,
    val host: String?,
    val method: String?,
    val path: String?,
    val status: Int?,
    val error: String?
)

@Serializable
data class SaveProxyHistoryResult(
    val directory: String,
    val saved: Int,
    val failed: Int,
    val items: List<SavedProxyHistoryItemSummary>
)

fun userHomeDirectory(): Path = Path.of(System.getProperty("user.home"))

/**
 * Lexical validation of a client-supplied export directory: must be absolute and, after
 * normalisation, inside [home]. Does not touch the filesystem.
 */
fun validateExportDirectory(directory: String, home: Path): Path {
    val path = try {
        Path.of(directory)
    } catch (e: InvalidPathException) {
        throw IllegalArgumentException("Invalid directory path: ${e.message}")
    }
    require(directory.isNotBlank() && path.isAbsolute) { "directory must be an absolute path" }

    val normalized = path.normalize()
    val homeLexical = home.toAbsolutePath().normalize()
    val homeReal = runCatching { home.toRealPath() }.getOrDefault(homeLexical)
    require(normalized.startsWith(homeLexical) || normalized.startsWith(homeReal)) {
        "directory must be inside the user's home directory ($homeLexical)"
    }
    return normalized
}

/**
 * Creates [directory] if needed and verifies, with symlinks resolved, that it is a directory
 * inside [home]. The deepest existing ancestor is checked before anything is created so a
 * symlink inside home cannot be used to create directories elsewhere.
 */
fun prepareExportDirectory(directory: Path, home: Path): Path {
    val homeReal = home.toRealPath()

    var existing: Path? = directory
    while (existing != null && !Files.exists(existing)) {
        existing = existing.parent
    }
    requireNotNull(existing) { "No existing ancestor for $directory" }
    require(existing.toRealPath().startsWith(homeReal)) {
        "directory resolves outside the user's home directory"
    }

    Files.createDirectories(directory)
    val real = directory.toRealPath()
    require(real.startsWith(homeReal)) { "directory resolves outside the user's home directory" }
    require(Files.isDirectory(real)) { "$real is not a directory" }
    return real
}

internal fun sanitizeFileNameComponent(value: String?): String =
    (value ?: "")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .take(MAX_FILE_NAME_COMPONENT_LENGTH)
        .ifEmpty { "unknown" }

private fun HttpMessage.rawBytes(): ByteArray = toByteArray().bytes

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun ByteArray.utf8(): String = String(this, Charsets.UTF_8)

fun ProxyHttpRequestResponse.toSavedForm(historyIndex: Int): SavedProxyHistoryItem {
    val request = request()
    val response = if (hasResponse()) response() else null
    val requestBytes = request.rawBytes()
    val responseBytes = response?.rawBytes()
    val edited = runCatching { edited() }.getOrDefault(false)

    return SavedProxyHistoryItem(
        historyIndex = historyIndex,
        id = runCatching { id() }.getOrNull(),
        time = runCatching { time()?.toOffsetDateTime()?.toString() }.getOrNull(),
        host = host(),
        port = port(),
        secure = secure(),
        httpVersion = runCatching { requestHttpVersion() }.getOrNull() ?: runCatching { request.httpVersion() }.getOrNull(),
        method = method(),
        path = path(),
        url = runCatching { url() }.getOrNull(),
        statusCode = response?.statusCode()?.toInt(),
        edited = edited,
        notes = runCatching { annotations().notes() }.getOrNull(),
        requestBase64 = requestBytes.base64(),
        responseBase64 = responseBytes?.base64(),
        requestText = requestBytes.utf8(),
        responseText = responseBytes?.utf8(),
        finalRequestBase64 = if (edited) runCatching { finalRequest()?.rawBytes()?.base64() }.getOrNull() else null,
        originalResponseBase64 = if (edited) runCatching { originalResponse()?.rawBytes()?.base64() }.getOrNull() else null
    )
}

/**
 * Writes one item atomically to `<directory>/<id>-<host>-<method>.json` (falling back to the
 * history index when Burp does not expose an id). Temp files are created owner-only on POSIX.
 */
fun writeSavedItem(directory: Path, item: SavedProxyHistoryItem): Path {
    val index = item.id ?: item.historyIndex
    val fileName = "$index-${sanitizeFileNameComponent(item.host)}-${sanitizeFileNameComponent(item.method)}.json"
    val target = directory.resolve(fileName)
    check(target.parent == directory) { "Refusing to write outside $directory" }

    val temp = Files.createTempFile(directory, ".burp-mcp-", ".tmp")
    try {
        Files.writeString(temp, exportJson.encodeToString(SavedProxyHistoryItem.serializer(), item))
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
    return target
}

fun saveProxyHistoryItems(
    history: List<ProxyHttpRequestResponse>,
    matches: (ProxyHttpRequestResponse) -> Boolean,
    offset: Int,
    count: Int,
    directory: Path
): List<SavedProxyHistoryItemSummary> =
    history.asSequence()
        .withIndex()
        .filter { matches(it.value) }
        .drop(offset)
        .take(count)
        .map { (index, item) ->
            try {
                val saved = item.toSavedForm(index)
                val file = writeSavedItem(directory, saved)
                SavedProxyHistoryItemSummary(
                    historyIndex = index,
                    file = file.toString(),
                    host = saved.host,
                    method = saved.method,
                    path = saved.path.let {
                        if (it.length > MAX_SUMMARY_PATH_LENGTH) it.take(MAX_SUMMARY_PATH_LENGTH) + "..." else it
                    },
                    status = saved.statusCode,
                    error = null
                )
            } catch (e: Exception) {
                SavedProxyHistoryItemSummary(index, null, null, null, null, null, e.message ?: e.javaClass.simpleName)
            }
        }
        .toList()
