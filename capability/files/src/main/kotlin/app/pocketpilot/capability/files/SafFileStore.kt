package app.pocketpilot.capability.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.api.files.FileEntry
import app.pocketpilot.capability.api.files.FilePaths
import app.pocketpilot.capability.api.files.FileStore
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** A folder the owner shared, by the name tools use for it. */
data class SharedFolder(
    val name: String,
    val uri: Uri,
)

/**
 * READ_FILES through the Storage Access Framework: the owner picks folders with the system picker,
 * PocketPilot keeps a persisted grant for each, and tools reach files only inside them. Folders with
 * the same name get a number added (Documents, Documents 2).
 */
class SafFileStore(
    context: Context,
) : FileStore {
    override val backendId: String = BackendIds.SHARED_FOLDERS

    private val resolver: ContentResolver = context.contentResolver
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val shared = MutableStateFlow(load())

    /** The shared folders, for the Settings card. */
    val sharedFolders: StateFlow<List<SharedFolder>> = shared.asStateFlow()

    private val hasFolders = MutableStateFlow(shared.value.isNotEmpty())
    override val available: StateFlow<Boolean> = hasFolders.asStateFlow()

    /** Keeps access to a tree the system picker returned. */
    @Synchronized
    fun add(tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        save(shared.value.map { it.uri } + tree)
    }

    @Synchronized
    fun remove(tree: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        save(shared.value.map { it.uri } - tree)
    }

    override suspend fun folders(): List<String> = shared.value.map { it.name }

    override suspend fun list(path: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val doc = resolve(path)
            if (!doc.folder) throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "$path is a file; use files.read")
            children(doc).map { FileEntry("$path/${it.name}", it.folder, if (it.folder) null else it.size, it.modified) }
        }

    override suspend fun read(
        path: String,
        maxBytes: Int,
    ): Pair<ByteArray, Long> =
        withContext(Dispatchers.IO) {
            val doc = resolve(path)
            if (doc.folder) throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "$path is a folder; use files.list")
            val bytes =
                resolver.openInputStream(doc.uri)?.use { input ->
                    // InputStream.readNBytes needs Android 13.
                    val buffer = ByteArray(maxBytes)
                    var filled = 0
                    while (filled < maxBytes) {
                        val read = input.read(buffer, filled, maxBytes - filled)
                        if (read < 0) break
                        filled += read
                    }
                    buffer.copyOf(filled)
                }
                    ?: throw ToolException(ToolErrorCode.INTERNAL_ERROR, "Could not open $path")
            bytes to (doc.size ?: bytes.size.toLong())
        }

    override suspend fun write(
        path: String,
        bytes: ByteArray,
        replace: Boolean,
    ) = withContext(Dispatchers.IO) {
        val segments = segments(path)
        val parentPath = segments.dropLast(1).joinToString("/")
        val name = segments.last()
        val parent = resolve(parentPath)
        if (!parent.folder) throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "$parentPath is not a folder")
        val existing = children(parent).firstOrNull { it.name == name }
        val target =
            when {
                existing == null -> {
                    DocumentsContract.createDocument(resolver, parent.uri, mimeFor(name), name)
                        ?: throw ToolException(ToolErrorCode.INTERNAL_ERROR, "Could not create $path")
                }

                existing.folder -> {
                    throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "$path is a folder")
                }

                !replace -> {
                    throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "$path already exists", "Pass replace: true to replace it.")
                }

                else -> {
                    existing.uri
                }
            }
        resolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
            ?: throw ToolException(ToolErrorCode.INTERNAL_ERROR, "Could not write $path")
        Unit
    }

    private class Doc(
        val uri: Uri,
        val tree: Uri,
        val name: String,
        val folder: Boolean,
        val size: Long?,
        val modified: Long?,
    )

    private fun segments(path: String): List<String> =
        FilePaths.segments(path) ?: throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "'$path' is not a valid path")

    /** Walks [path] from its shared folder down, one name at a time. */
    private fun resolve(path: String): Doc {
        val segments = segments(path)
        val root =
            shared.value.firstOrNull { it.name == segments.first() }
                ?: throw ToolException(
                    ToolErrorCode.ELEMENT_NOT_FOUND,
                    "No shared folder named ${segments.first()}",
                    "Call files.list without a path for the shared folders.",
                )
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(root.uri, DocumentsContract.getTreeDocumentId(root.uri))
        var doc = Doc(rootDoc, root.uri, root.name, folder = true, size = null, modified = null)
        for (name in segments.drop(1)) {
            doc = children(doc).firstOrNull { it.name == name }
                ?: throw ToolException(
                    ToolErrorCode.ELEMENT_NOT_FOUND,
                    "Nothing named $name in ${segments.takeWhile { it != name }.joinToString("/")}",
                )
        }
        return doc
    }

    private fun children(parent: Doc): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent.tree, DocumentsContract.getDocumentId(parent.uri))
        val columns =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Doc(
                            uri = DocumentsContract.buildDocumentUriUsingTree(parent.tree, cursor.getString(0)),
                            tree = parent.tree,
                            name = cursor.getString(1),
                            folder = cursor.getString(2) == Document.MIME_TYPE_DIR,
                            size = cursor.longOrNull(3),
                            modified = cursor.longOrNull(4),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "text/plain"

    private fun load(): List<SharedFolder> {
        val granted =
            resolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri }
                .toSet()
        val uris =
            prefs
                .getString(KEY, null)
                .orEmpty()
                .lines()
                .filter(String::isNotBlank)
                .map(Uri::parse)
                .filter { it in granted }
        return named(uris)
    }

    private fun save(uris: List<Uri>) {
        val distinct = uris.distinct()
        prefs.edit().putString(KEY, distinct.joinToString("\n")).apply()
        shared.value = named(distinct)
        hasFolders.value = distinct.isNotEmpty()
    }

    /** Gives each tree the last part of its path as a name, numbered when two share one. */
    private fun named(uris: List<Uri>): List<SharedFolder> {
        val seen = HashMap<String, Int>()
        return uris.map { uri ->
            val base =
                DocumentsContract
                    .getTreeDocumentId(uri)
                    .substringAfterLast(':')
                    .substringAfterLast('/')
                    .ifBlank { "Storage" }
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            SharedFolder(if (count == 1) base else "$base $count", uri)
        }
    }

    private companion object {
        const val PREFS = "shared_folders"
        const val KEY = "trees"
    }
}
