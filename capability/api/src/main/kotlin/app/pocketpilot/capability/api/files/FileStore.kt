package app.pocketpilot.capability.api.files

import app.pocketpilot.capability.api.CapabilityBackend

/** A file or folder inside a folder the owner granted. */
data class FileEntry(
    /** Starts with the granted folder's name, such as `Documents/notes/todo.txt`. */
    val path: String,
    val folder: Boolean,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
)

/**
 * READ_FILES: files inside folders the owner granted (spec section 5, `files.*`). A path starts
 * with a granted folder's name; nothing outside those folders can be reached. Missing paths throw
 * ToolException ELEMENT_NOT_FOUND.
 */
interface FileStore : CapabilityBackend {
    /** The granted folders, by name. */
    suspend fun folders(): List<String>

    /** The entries directly inside [path], a folder. */
    suspend fun list(path: String): List<FileEntry>

    /** Up to [maxBytes] from the start of the file at [path], and its full size. */
    suspend fun read(
        path: String,
        maxBytes: Int,
    ): Pair<ByteArray, Long>

    /** Writes [bytes] to [path], creating the file in its existing folder; replaces a file only when [replace]. */
    suspend fun write(
        path: String,
        bytes: ByteArray,
        replace: Boolean,
    )
}

/** Splits a tool's path into segments, refusing anything that could climb out of a granted folder. */
object FilePaths {
    fun segments(path: String): List<String>? {
        val parts = path.trim().trim('/').split('/')
        if (parts.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) return null
        return parts
    }
}
