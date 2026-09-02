package io.github.piyushmishra00.kryon.coroutines

import java.nio.file.Path
import java.util.Locale
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Unit tests for behaviour the shared conformance corpus does not reach.
 *
 * The corpus covers cross-language semantics. This class covers the Kotlin surface itself: option
 * merging, error mapping, the guards on [KryonProcess], structured cancellation, and the promise
 * that nothing is left running when a caller walks away.
 */
class ApiTest {

    private companion object {
        val windows: Boolean =
            System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

        val javaExe: String =
            Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
                .toString()

        val classpath: String = System.getProperty("kryon.helper.classpath")
    }

    private fun helper(vararg args: String): List<String> =
        listOf("-cp", classpath, ConformanceHelper::class.java.name) + args

    private fun result(
        exitCode: Int? = 0,
        termination: TerminationReason = TerminationReason.EXITED,
        stderr: ByteArray = ByteArray(0),
    ) = ExecutionResult(
        executable = "prog",
        arguments = emptyList(),
        exitCode = exitCode,
        signal = if (termination == TerminationReason.SIGNALED) 15 else null,
        stdoutBytes = ByteArray(0),
        stderrBytes = stderr,
        charset = Charsets.UTF_8,
        duration = 10.milliseconds,
        termination = termination,
        pid = 1L,
    )

    // ------------------------------------------------------------------ options

    @Test
    fun `runtime defaults apply to calls`() = runBlocking {
        val runtime = Runtime(ExecutionOptions(charset = Charsets.UTF_8))
        assertEquals("hi\n", runtime.execute(javaExe, helper("echo", "hi")).stdout)
    }

    @Test
    fun `env merges rather than replaces`() = runBlocking {
        val runtime = Runtime(
            ExecutionOptions(charset = Charsets.UTF_8, env = mapOf("KRYON_A" to "1")),
        )
        val result = runtime.execute(
            javaExe,
            helper("env", "KRYON_A"),
            ExecutionOptions(env = mapOf("KRYON_B" to "2")),
        )
        assertEquals("1\n", result.stdout, "a per-call env must not drop the runtime's env")
    }

    @Test
    fun `a boolean override can turn a default off again`() {
        val defaults = ExecutionOptions(check = true, clearEnv = true)
        val merged = defaults.mergedWith(ExecutionOptions(check = false, clearEnv = false))
        assertFalse(merged.checkOrDefault)
        assertFalse(merged.clearEnvOrDefault)
    }

    @Test
    fun `an unspecified boolean leaves the default alone`() {
        val defaults = ExecutionOptions(check = true)
        assertTrue(defaults.mergedWith(ExecutionOptions()).checkOrDefault)
    }

    @Test
    fun `a non-positive timeout is rejected up front`() {
        assertThrows<InvalidArgumentsException> { Runtime(ExecutionOptions(timeout = Duration.ZERO)) }
    }

    @Test
    fun `a non-positive output limit is rejected up front`() {
        assertThrows<InvalidArgumentsException> { Runtime(ExecutionOptions(maxOutputBytes = 0)) }
    }

    @Test
    fun `an empty executable is rejected`() = runBlocking {
        assertThrows<InvalidArgumentsException> { runBlocking { Runtime().execute("") } }
        Unit
    }

    // ---------------------------------------------------------- results and errors

    @Test
    fun `ok requires both exited and zero`() {
        assertTrue(result().ok)
        assertFalse(result(exitCode = 1).ok)
        assertFalse(result(termination = TerminationReason.TIMEOUT).ok)
    }

    @Test
    fun `checked maps each termination to its own error`() {
        assertThrows<ProcessTimeoutException> {
            result(null, TerminationReason.TIMEOUT).checked()
        }
        assertThrows<ProcessCancelledException> {
            result(null, TerminationReason.CANCELLED).checked()
        }
        assertThrows<ResourceLimitExceededException> {
            result(null, TerminationReason.OUTPUT_LIMIT).checked()
        }
        assertThrows<ProcessFailedException> {
            result(null, TerminationReason.SIGNALED).checked()
        }
    }

    @Test
    fun `checked returns the result on success`() {
        val ok = result()
        assertSame(ok, ok.checked())
    }

    @Test
    fun `errors carry the result they came from`() {
        val error = assertThrows<ProcessFailedException> {
            result(2, stderr = "the real reason\n".toByteArray()).checked()
        }
        assertEquals(2, error.result?.exitCode)
        assertContains(error.message!!, "the real reason", message = "stderr belongs in the message")
    }

    @Test
    fun `the error message excerpt is capped`() {
        val error = assertThrows<ProcessFailedException> {
            result(1, stderr = ByteArray(5000) { 'x'.code.toByte() }).checked()
        }
        assertTrue(error.message!!.length < 1000, "an error message is not a log file")
    }

    @Test
    fun `a missing executable throws rather than returning a result`() = runBlocking {
        assertThrows<CommandNotFoundException> {
            runBlocking { Runtime().execute("kryon-no-such-executable-xyzzy") }
        }
        Unit
    }

    @Test
    fun `arguments are never interpreted`() = runBlocking {
        val hostile = "\$HOME && rm -rf / ; `whoami`"
        val result = Runtime(ExecutionOptions(charset = Charsets.UTF_8))
            .execute(javaExe, helper("echo", hostile))
        assertEquals("$hostile\n", result.stdout)
    }

    // ------------------------------------------------------------------- process

    @Test
    fun `output can only be consumed once`() = runBlocking {
        Runtime().spawn(javaExe, helper("echo", "x")).use { proc ->
            proc.output.toList()
            assertThrows<IllegalStateException> { proc.output }
        }
    }

    @Test
    fun `write after closeStdin throws`() = runBlocking {
        Runtime().spawn(javaExe, helper("cat")).use { proc ->
            proc.closeStdin()
            assertThrows<IllegalStateException> { runBlocking { proc.write("too late\n") } }
        }
        Unit
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        val proc = Runtime().spawn(javaExe, helper("sleep", "30"))
        proc.closeAndJoin()
        proc.closeAndJoin()
        assertFalse(proc.running)
    }

    @Test
    fun `await timeout leaves the process running`() = runBlocking {
        Runtime().spawn(javaExe, helper("sleep", "30")).use { proc ->
            assertThrows<ProcessTimeoutException> { runBlocking { proc.await(300.milliseconds) } }
            assertTrue(proc.running, "await() is a wait, not a stop")
        }
        Unit
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `signal is unsupported on Windows`() = runBlocking {
        Runtime().spawn(javaExe, helper("sleep", "30")).use { proc ->
            assertThrows<UnsupportedPlatformException> { proc.signal(15) }
        }
        Unit
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `signal delivers`() = runBlocking {
        Runtime().spawn(javaExe, helper("sleep", "30")).use { proc ->
            proc.signal(15)
            val result = proc.await(10.seconds)
            assertFalse(proc.running)
            assertEquals(15, result.signal)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `close leaves no orphan`() = runBlocking {
        val proc = Runtime().spawn(javaExe, helper("sleep", "30"))
        val pid = proc.pid
        proc.closeAndJoin()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `an unconsumed process still closes promptly`() = runBlocking {
        val proc = Runtime().spawn(javaExe, helper("spam", "50000000"))
        val finished = withTimeoutOrNull(20.seconds) { proc.closeAndJoin() }
        assertTrue(finished != null, "close must not wait out the flood")
        assertFalse(proc.running)
    }

    @Test
    fun `chunks are tagged with their stream`() = runBlocking {
        Runtime().spawn(javaExe, helper("both", "out", "err")).use { proc ->
            val seen = proc.output.toList().map { it.stream }.toSet()
            assertEquals(setOf(StreamKind.STDOUT, StreamKind.STDERR), seen)
        }
    }

    // -------------------------------------------------------------- cancellation

    @Test
    fun `cancelling the coroutine terminates the child`() = runBlocking {
        val started = System.currentTimeMillis()
        coroutineScope {
            val job = launch {
                Runtime().execute(javaExe, helper("sleep", "60"))
            }
            delay(400)
            job.cancel()
            job.join()
        }
        assertTrue(
            System.currentTimeMillis() - started < 20_000,
            "cancellation must not wait out the process",
        )
    }

    @Test
    fun `a cancelled execute does not resolve`() = runBlocking {
        coroutineScope {
            val deferred = async { Runtime().execute(javaExe, helper("sleep", "60")) }
            delay(300)
            deferred.cancel()
            assertNull(withTimeoutOrNull(5.seconds) { runCatching { deferred.await() }.getOrNull() })
        }
    }
}
