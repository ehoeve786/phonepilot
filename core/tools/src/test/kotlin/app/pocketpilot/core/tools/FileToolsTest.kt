package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.files.FileEntry
import app.pocketpilot.capability.api.files.FileStore
import app.pocketpilot.core.audit.InMemoryAuditSink
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.SessionFactory
import app.pocketpilot.core.orchestrator.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileToolsTest {
    private class FakeFiles : FileStore {
        override val backendId = "fake"
        override val available = MutableStateFlow(true)
        val contents = mutableMapOf("Documents/todo.txt" to "milk\neggs".toByteArray(), "Documents/photo.jpg" to byteArrayOf(-1, -40, 0, 1))

        override suspend fun folders() = listOf("Documents")

        override suspend fun list(path: String) =
            contents.keys
                .filter {
                    it.startsWith("$path/")
                }.map { FileEntry(it, folder = false, sizeBytes = contents[it]!!.size.toLong(), modifiedMillis = null) }

        override suspend fun read(
            path: String,
            maxBytes: Int,
        ): Pair<ByteArray, Long> {
            val bytes = contents[path] ?: throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "No $path")
            return bytes.copyOf(minOf(maxBytes, bytes.size)) to bytes.size.toLong()
        }

        override suspend fun write(
            path: String,
            bytes: ByteArray,
            replace: Boolean,
        ) {
            if (path in contents && !replace) throw ToolException(ToolErrorCode.INVALID_ARGUMENTS, "$path exists")
            contents[path] = bytes
        }
    }

    private val files = FakeFiles()
    private val dispatcher =
        CallDispatcher(
            ToolRegistry(setOf(FilesListTool(files), FilesReadTool(files), FilesWriteTool(files)), PolicyProfile.OSS),
            InMemoryAuditSink(),
        )
    private val session = SessionFactory().localTokenSession()

    @Test
    fun `lists shared folders and their files`() =
        runTest {
            assertTrue("Documents" in dispatcher.dispatch(session, "files.list", buildJsonObject {}).content.toString())
            val inside = dispatcher.dispatch(session, "files.list", buildJsonObject { put("path", "/Documents/") }).content.toString()
            assertTrue("Documents/todo.txt" in inside)
        }

    @Test
    fun `reads text, cuts long files and refuses binary`() =
        runTest {
            val cut =
                dispatcher
                    .dispatch(
                        session,
                        "files.read",
                        buildJsonObject {
                            put("path", "Documents/todo.txt")
                            put("maxBytes", 4)
                        },
                    ).content
                    .toString()
            assertTrue("milk" in cut && "first 4 of 9 bytes" in cut)
            val photo =
                dispatcher
                    .dispatch(
                        session,
                        "files.read",
                        buildJsonObject { put("path", "Documents/photo.jpg") },
                    ).content
                    .toString()
            assertTrue("not a text file" in photo)
        }

    @Test
    fun `refuses paths that climb out of a folder`() =
        runTest {
            val result = dispatcher.dispatch(session, "files.read", buildJsonObject { put("path", "Documents/../secret") })
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode)
        }

    @Test
    fun `replaces a file only when asked`() =
        runTest {
            val args = { replace: Boolean ->
                buildJsonObject {
                    put("path", "Documents/todo.txt")
                    put("text", "bread")
                    put("replace", replace)
                }
            }
            assertTrue(dispatcher.dispatch(session, "files.write", args(false)).isError)
            assertFalse(dispatcher.dispatch(session, "files.write", args(true)).isError)
            assertEquals("bread", files.contents["Documents/todo.txt"]!!.decodeToString())
        }

    @Test
    fun `appends on a new line and keeps the rest`() =
        runTest {
            val args = { text: String ->
                buildJsonObject {
                    put("path", "Documents/todo.txt")
                    put("text", text)
                    put("append", true)
                }
            }
            assertFalse(dispatcher.dispatch(session, "files.write", args("buy coffee")).isError)
            assertEquals("milk\neggs\nbuy coffee", files.contents["Documents/todo.txt"]!!.decodeToString())
            files.contents["Documents/todo.txt"] = "milk\n".toByteArray()
            dispatcher.dispatch(session, "files.write", args("tea"))
            assertEquals("milk\ntea", files.contents["Documents/todo.txt"]!!.decodeToString())
            val missing =
                buildJsonObject {
                    put("path", "Documents/none.txt")
                    put("text", "x")
                    put("append", true)
                }
            assertFalse(dispatcher.dispatch(session, "files.write", missing).isError)
            assertEquals("x", files.contents["Documents/none.txt"]!!.decodeToString())
        }
}
