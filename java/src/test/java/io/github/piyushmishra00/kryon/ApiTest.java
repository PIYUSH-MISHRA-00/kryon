package io.github.piyushmishra00.kryon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Unit tests for behaviour the shared conformance corpus does not reach.
 *
 * <p>The corpus covers cross-language semantics. This class covers the Java surface itself:
 * option merging, error mapping, the guards on {@link KryonProcess}, and the promise that nothing
 * is left running when a caller walks away.
 */
class ApiTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final String JAVA =
            Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();

    private static final String HELPER_CLASSES = System.getProperty("kryon.helper.classes");

    private static List<String> helper(String... args) {
        List<String> argv = new ArrayList<>(List.of("-cp", HELPER_CLASSES, ConformanceHelper.class.getName()));
        argv.addAll(List.of(args));
        return argv;
    }

    private static ExecutionResult result(
            Integer exitCode, TerminationReason termination, byte[] stderr) {
        return new ExecutionResult(
                "prog",
                List.of(),
                exitCode,
                termination == TerminationReason.SIGNALED ? 15 : null,
                new byte[0],
                stderr,
                StandardCharsets.UTF_8,
                Duration.ofMillis(10),
                termination,
                1L,
                false,
                false);
    }

    // ------------------------------------------------------------------ options

    @Test
    void runtimeDefaultsApplyToCalls() {
        Runtime runtime =
                new Runtime(ExecutionOptions.builder().charset(StandardCharsets.UTF_8).build());
        assertEquals("hi\n", runtime.execute(JAVA, helper("echo", "hi")).stdout());
    }

    @Test
    void envMergesRatherThanReplaces() {
        Runtime runtime =
                new Runtime(
                        ExecutionOptions.builder()
                                .charset(StandardCharsets.UTF_8)
                                .env("KRYON_A", "1")
                                .build());
        ExecutionResult result =
                runtime.execute(
                        JAVA,
                        helper("env", "KRYON_A"),
                        ExecutionOptions.builder().env("KRYON_B", "2").build());
        assertEquals("1\n", result.stdout(), "a per-call env must not drop the runtime's env");
    }

    @Test
    void aBooleanOverrideCanTurnADefaultOffAgain() {
        ExecutionOptions defaults = ExecutionOptions.builder().check(true).clearEnv(true).build();
        ExecutionOptions merged =
                defaults.mergedWith(ExecutionOptions.builder().check(false).clearEnv(false).build());
        assertFalse(merged.check());
        assertFalse(merged.clearEnv());
    }

    @Test
    void anUnspecifiedBooleanLeavesTheDefaultAlone() {
        ExecutionOptions defaults = ExecutionOptions.builder().check(true).build();
        assertTrue(defaults.mergedWith(ExecutionOptions.builder().build()).check());
    }

    @Test
    void aNonPositiveTimeoutIsRejectedUpFront() {
        assertThrows(
                InvalidArgumentsException.class,
                () -> new Runtime(ExecutionOptions.builder().timeout(Duration.ZERO).build()));
    }

    @Test
    void aNonPositiveOutputLimitIsRejectedUpFront() {
        assertThrows(
                InvalidArgumentsException.class,
                () -> new Runtime(ExecutionOptions.builder().maxOutputBytes(0).build()));
    }

    // ------------------------------------------------------- argument validation

    @Test
    void anEmptyExecutableIsRejected() {
        assertThrows(InvalidArgumentsException.class, () -> new Runtime().execute(""));
    }

    @Test
    void aNullArgumentIsRejectedWithItsIndex() {
        List<String> args = new ArrayList<>();
        args.add("echo");
        args.add(null);
        InvalidArgumentsException error =
                assertThrows(
                        InvalidArgumentsException.class,
                        () -> new Runtime().execute(JAVA, args));
        assertTrue(error.getMessage().contains("argument 1"), error.getMessage());
    }

    // ---------------------------------------------------------- results and errors

    @Test
    void okRequiresBothExitedAndZero() {
        assertTrue(result(0, TerminationReason.EXITED, new byte[0]).ok());
        assertFalse(result(1, TerminationReason.EXITED, new byte[0]).ok());
        assertFalse(result(0, TerminationReason.TIMEOUT, new byte[0]).ok());
    }

    @Test
    void checkedMapsEachTerminationToItsOwnError() {
        assertThrows(
                ProcessTimeoutException.class,
                () -> result(null, TerminationReason.TIMEOUT, new byte[0]).checked());
        assertThrows(
                ProcessCancelledException.class,
                () -> result(null, TerminationReason.CANCELLED, new byte[0]).checked());
        assertThrows(
                ResourceLimitExceededException.class,
                () -> result(null, TerminationReason.OUTPUT_LIMIT, new byte[0]).checked());
        assertThrows(
                ProcessFailedException.class,
                () -> result(null, TerminationReason.SIGNALED, new byte[0]).checked());
    }

    @Test
    void checkedReturnsTheResultOnSuccess() {
        ExecutionResult ok = result(0, TerminationReason.EXITED, new byte[0]);
        assertSame(ok, ok.checked());
    }

    @Test
    void errorsCarryTheResultTheyCameFrom() {
        ProcessFailedException error =
                assertThrows(
                        ProcessFailedException.class,
                        () ->
                                result(
                                                2,
                                                TerminationReason.EXITED,
                                                "the real reason\n".getBytes(StandardCharsets.UTF_8))
                                        .checked());
        assertNotNull(error.result());
        assertEquals(2, error.result().exitCode().orElseThrow());
        assertTrue(
                error.getMessage().contains("the real reason"),
                "stderr belongs in the message");
    }

    @Test
    void theErrorMessageExcerptIsCapped() {
        byte[] noisy = new byte[5000];
        java.util.Arrays.fill(noisy, (byte) 'x');
        ProcessFailedException error =
                assertThrows(
                        ProcessFailedException.class,
                        () -> result(1, TerminationReason.EXITED, noisy).checked());
        assertTrue(error.getMessage().length() < 1000, "an error message is not a log file");
    }

    @Test
    void aMissingExecutableThrowsRatherThanReturningAResult() {
        assertThrows(
                CommandNotFoundException.class,
                () -> new Runtime().execute("kryon-no-such-executable-xyzzy"));
    }

    @Test
    void everyKryonErrorDescendsFromKryonException() {
        List<Class<?>> types =
                List.of(
                        CommandNotFoundException.class,
                        InvalidArgumentsException.class,
                        PermissionDeniedException.class,
                        ProcessCancelledException.class,
                        ProcessFailedException.class,
                        ProcessStartFailedException.class,
                        ProcessTimeoutException.class,
                        ResourceLimitExceededException.class,
                        UnsupportedPlatformException.class);
        for (Class<?> type : types) {
            assertTrue(KryonException.class.isAssignableFrom(type), type.getName());
        }
    }

    // ------------------------------------------------------------------- process

    @Test
    void outputCanOnlyBeConsumedOnce() {
        try (KryonProcess proc = new Runtime().spawn(JAVA, helper("echo", "x"))) {
            for (OutputChunk ignored : proc.output()) {
                // drain
            }
            assertThrows(IllegalStateException.class, proc::output);
        }
    }

    @Test
    void writeAfterCloseStdinThrows() {
        try (KryonProcess proc = new Runtime().spawn(JAVA, helper("cat"))) {
            proc.closeStdin();
            assertThrows(IllegalStateException.class, () -> proc.write("too late\n"));
        }
    }

    @Test
    void closeIsIdempotent() {
        KryonProcess proc = new Runtime().spawn(JAVA, helper("sleep", "30"));
        proc.close();
        proc.close();
        assertFalse(proc.running());
    }

    @Test
    void awaitTimeoutLeavesTheProcessRunning() {
        try (KryonProcess proc = new Runtime().spawn(JAVA, helper("sleep", "30"))) {
            assertThrows(ProcessTimeoutException.class, () -> proc.await(Duration.ofMillis(300)));
            assertTrue(proc.running(), "await() is a wait, not a stop");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void signalIsUnsupportedOnWindows() {
        try (KryonProcess proc = new Runtime().spawn(JAVA, helper("sleep", "30"))) {
            assertThrows(UnsupportedPlatformException.class, () -> proc.signal(15));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void signalDelivers() {
        try (KryonProcess proc = new Runtime().spawn(JAVA, helper("sleep", "30"))) {
            proc.signal(15);
            ExecutionResult result = proc.await(Duration.ofSeconds(10));
            assertFalse(proc.running());
            assertEquals(15, result.signal().orElseThrow());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void closeLeavesNoOrphan() throws Exception {
        KryonProcess proc = new Runtime().spawn(JAVA, helper("sleep", "30"));
        long pid = proc.pid();
        proc.close();
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void anUnconsumedProcessStillClosesPromptly() {
        KryonProcess proc = new Runtime().spawn(JAVA, helper("spam", "50000000"));
        long started = System.currentTimeMillis();
        proc.close();
        assertTrue(
                System.currentTimeMillis() - started < 20_000,
                "close must not wait out the flood");
        assertFalse(proc.running());
    }

    @Test
    void chunksAreTaggedWithTheirStream() {
        try (KryonProcess proc = new Runtime().spawn(JAVA, helper("both", "out", "err"))) {
            Set<StreamKind> seen = new HashSet<>();
            for (OutputChunk chunk : proc.output()) {
                seen.add(chunk.stream());
            }
            assertEquals(Set.of(StreamKind.STDOUT, StreamKind.STDERR), seen);
        }
    }

    @Test
    void argumentsAreNeverInterpreted() {
        ExecutionResult result =
                new Runtime()
                        .execute(
                                JAVA,
                                helper("echo", "$HOME && rm -rf / ; `whoami`"),
                                ExecutionOptions.builder().charset(StandardCharsets.UTF_8).build());
        assertEquals("$HOME && rm -rf / ; `whoami`\n", result.stdout());
    }
}
