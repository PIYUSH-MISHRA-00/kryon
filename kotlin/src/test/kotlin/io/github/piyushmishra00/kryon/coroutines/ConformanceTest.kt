package io.github.piyushmishra00.kryon.coroutines

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Runs the shared conformance corpus against the Kotlin SDK.
 *
 * The corpus lives at `tests/conformance/cases.json` in the repository root and is
 * language-neutral. This class is the Kotlin *runner*: it maps each case onto this SDK's API and
 * asserts the expectations. Every SDK writes one of these; the corpus itself is never forked.
 */
class ConformanceTest {

    private companion object {
        val windows: Boolean =
            System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

        val javaExe: String =
            Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
                .toString()

        val classpath: String = System.getProperty("kryon.helper.classpath")

        val corpus: JsonObject =
            JsonParser.parseString(
                Files.readString(Path.of(System.getProperty("kryon.corpus"))),
            ).asJsonObject

        val cases: List<JsonObject> = corpus.getAsJsonArray("cases").map { it.asJsonObject }

        val scratch: Path by lazy {
            Files.createTempDirectory("kryon-conformance-").toRealPath()
        }

        val errors: Map<String, Class<out KryonException>> = mapOf(
            "CommandNotFound" to CommandNotFoundException::class.java,
            "PermissionDenied" to PermissionDeniedException::class.java,
            "ProcessStartFailed" to ProcessStartFailedException::class.java,
            "InvalidArguments" to InvalidArgumentsException::class.java,
            "ProcessFailed" to ProcessFailedException::class.java,
            "ProcessTimeout" to ProcessTimeoutException::class.java,
            "ProcessCancelled" to ProcessCancelledException::class.java,
            "ResourceLimitExceeded" to ResourceLimitExceededException::class.java,
        )
    }

    private fun substitute(value: String) = value.replace("\${TMPDIR}", scratch.toString())

    /**
     * Why a case cannot run here, or null if it can.
     *
     * The JVM cannot set its own environment at runtime, so a case with `setup_env` needs that
     * variable supplied by whatever launched the tests. Per `spec/conformance.md`, an SDK that
     * cannot satisfy a case reports it as skipped with a reason rather than quietly dropping it.
     */
    private fun skipReason(kase: JsonObject): String? {
        kase.getAsJsonArray("platforms")?.let { platforms ->
            val wanted = if (windows) "windows" else "posix"
            if (platforms.none { it.asString == wanted }) return "not applicable to this platform"
        }
        kase.getAsJsonObject("setup_env")?.entrySet()?.forEach { (key, value) ->
            if (System.getenv(key) != value.asString) {
                return "needs $key=${value.asString} in the environment of the test runner " +
                    "itself; the JVM cannot set its own environment. Run with it set (CI does)."
            }
        }
        return null
    }

    /**
     * Translates corpus options into this SDK's spelling.
     *
     * The corpus stores durations in seconds; Kotlin's idiom is [Duration]. The semantics are
     * identical, and each SDK converts in its own runner.
     */
    private fun options(kase: JsonObject): ExecutionOptions {
        val source = kase.getAsJsonObject("options") ?: JsonObject()

        fun duration(key: String): Duration? =
            source.get(key)?.asDouble?.let { (it * 1000).roundToLong().milliseconds }

        val env = source.getAsJsonObject("env")?.entrySet()?.associate { (key, value) ->
            key to if (value.isJsonNull) null else value.asString
        } ?: emptyMap()

        return ExecutionOptions(
            cwd = source.get("cwd")?.asString?.let { Path.of(substitute(it)) },
            env = env,
            clearEnv = source.get("clear_env")?.asBoolean,
            stdin = kase.get("stdin")?.asString?.toByteArray(),
            timeout = duration("timeout"),
            killGrace = duration("kill_grace"),
            maxOutputBytes = source.get("max_output_bytes")?.asLong,
            charset = source.get("encoding")?.let { Charsets.UTF_8 },
            check = source.get("check")?.asBoolean,
        )
    }

    private data class Command(
        val executable: String,
        val arguments: List<String>,
        val shell: Boolean,
    )

    private fun command(kase: JsonObject): Command {
        kase.getAsJsonObject("shell_command")?.let {
            return Command(it.get(if (windows) "windows" else "posix").asString, emptyList(), true)
        }
        val args = kase.getAsJsonArray("args")?.map { substitute(it.asString) } ?: emptyList()
        kase.get("executable")?.let { return Command(it.asString, args, false) }

        return Command(
            javaExe,
            listOf("-cp", classpath, ConformanceHelper::class.java.name) + args,
            false,
        )
    }

    private suspend fun invoke(cmd: Command, opts: ExecutionOptions): ExecutionResult {
        val runtime = Runtime()
        return if (cmd.shell) {
            runtime.executeShell(cmd.executable, opts)
        } else {
            runtime.execute(cmd.executable, cmd.arguments, opts)
        }
    }

    private fun assertResult(kase: JsonObject, result: ExecutionResult) {
        val e = kase.getAsJsonObject("expect")
        val id = kase.get("id").asString

        e.get("exit_code")?.let { assertEquals(it.asInt, result.exitCode, id) }
        e.get("termination")?.let { assertEquals(it.asString, result.termination.name, id) }
        e.get("ok")?.let { assertEquals(it.asBoolean, result.ok, id) }
        e.get("stdout")?.let { assertEquals(it.asString, result.stdout, id) }
        e.get("stderr")?.let { assertEquals(it.asString, result.stderr, id) }
        e.get("stdout_contains")?.let { assertContains(result.stdout, it.asString, message = id) }
        e.get("stderr_contains")?.let { assertContains(result.stderr, it.asString, message = id) }
        e.get("stdout_truncated")?.let { assertEquals(it.asBoolean, result.stdoutTruncated, id) }
        e.get("stdout_bytes_at_most")?.let {
            assertTrue(result.stdoutRaw.size <= it.asLong, "$id: ${result.stdoutRaw.size} bytes")
        }
        e.get("duration_at_most")?.let {
            assertTrue(
                result.duration <= (it.asDouble * 1000).roundToLong().milliseconds,
                "$id: ${result.duration}",
            )
        }
        e.get("duration_at_least")?.let {
            assertTrue(
                result.duration >= (it.asDouble * 1000).roundToLong().milliseconds,
                "$id: ${result.duration}",
            )
        }
        e.get("signal_present")?.let { assertEquals(it.asBoolean, result.signal != null, id) }
        e.get("stdout_is_bytes")?.let {
            if (it.asBoolean) assertTrue(result.stdoutRaw.isNotEmpty(), id)
        }
        e.get("stdout_contains_bytes")?.let {
            val needle = it.asString.chunked(2).map { pair -> pair.toInt(16).toByte() }
                .toByteArray()
            assertTrue(indexOf(result.stdoutRaw, needle) >= 0, id)
        }
        e.get("stdout_is_dir")?.let {
            val expected = Path.of(substitute(it.asString)).toRealPath()
            val actual = Path.of(result.stdout.trim()).toRealPath()
            assertEquals(expected, actual, id)
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    @TestFactory
    fun execute(): List<DynamicTest> =
        cases.filter { it.get("api").asString != "spawn" }.map { kase ->
            DynamicTest.dynamicTest(kase.get("id").asString) {
                val skip = skipReason(kase)
                Assumptions.assumeTrue(skip == null, skip)

                runBlocking {
                    val cmd = command(kase)
                    val opts = options(kase)
                    val expect = kase.getAsJsonObject("expect")

                    val raises = expect.get("raises")?.asString
                    if (raises != null) {
                        val thrown = assertThrows<KryonException> {
                            runBlocking { invoke(cmd, opts) }
                        }
                        assertEquals(errors[raises], thrown.javaClass, kase.get("id").asString)
                        return@runBlocking
                    }
                    assertResult(kase, invoke(cmd, opts))
                }
            }
        }

    @TestFactory
    fun spawn(): List<DynamicTest> =
        cases.filter { it.get("api").asString == "spawn" }.map { kase ->
            DynamicTest.dynamicTest(kase.get("id").asString) {
                val skip = skipReason(kase)
                Assumptions.assumeTrue(skip == null, skip)
                runBlocking { runSpawnCase(kase) }
            }
        }

    private suspend fun runSpawnCase(kase: JsonObject) {
        val runtime = Runtime()
        val cmd = command(kase)
        val e = kase.getAsJsonObject("expect")
        val id = kase.get("id").asString

        val proc = runtime.spawn(cmd.executable, cmd.arguments, options(kase))

        if (kase.get("scope_exit_only")?.asBoolean == true) {
            assertTrue(proc.running, id)
            proc.closeAndJoin()
            assertEquals(e.get("running_after_scope").asBoolean, proc.running, id)
            return
        }

        proc.use { process ->
            kase.get("terminate_after")?.let { after ->
                delay((after.asDouble * 1000).roundToLong())
                process.terminate()
                val result = process.await(20.seconds)
                assertEquals(e.get("running_after_terminate").asBoolean, process.running, id)
                e.get("duration_at_most")?.let {
                    assertTrue(
                        result.duration <= (it.asDouble * 1000).roundToLong().milliseconds,
                        id,
                    )
                }
                return@use
            }

            kase.getAsJsonArray("write")?.forEach { process.write(it.asString) }
            if (kase.get("close_stdin")?.asBoolean == true) process.closeStdin()

            val chunks = process.output.toList()
            val result = process.await(20.seconds)
            val text = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk.data }
                .toString(Charsets.UTF_8)

            e.get("streamed_chunks_at_least")?.let {
                assertTrue(chunks.size >= it.asInt, id)
            }
            e.get("stdout_contains")?.let { assertContains(text, it.asString, message = id) }
            e.get("stdout_contains_last")?.let { assertContains(text, it.asString, message = id) }
            e.get("exit_code")?.let { assertEquals(it.asInt, result.exitCode, id) }
        }
    }

    @Test
    fun idsAreUnique() {
        val ids = cases.map { it.get("id").asString }
        assertEquals(ids.size, ids.toSet().size, "duplicate case id in the corpus")
    }

    @Test
    fun everyCaseExplainsItself() {
        val missing = cases.filter { !it.has("why") }.map { it.get("id").asString }
        assertTrue(missing.isEmpty(), "cases missing a 'why': $missing")
    }

    @Test
    fun everyCaseHasARunner() {
        val apis = cases.map { it.get("api").asString }.toSet()
        assertTrue(
            setOf("execute", "execute_shell", "spawn").containsAll(apis),
            "the corpus uses an api this runner does not handle: $apis",
        )
    }
}
