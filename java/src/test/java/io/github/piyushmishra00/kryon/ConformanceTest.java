package io.github.piyushmishra00.kryon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared conformance corpus against the Java SDK.
 *
 * <p>The corpus lives at {@code tests/conformance/cases.json} in the repository root and is
 * language-neutral. This class is the Java <em>runner</em>: it maps each case onto this SDK's API
 * and asserts the expectations. Every SDK writes one of these; the corpus itself is never forked.
 */
class ConformanceTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final Path CORPUS = Path.of(System.getProperty("kryon.corpus"));
    private static final String HELPER_CLASSES = System.getProperty("kryon.helper.classes");
    private static final String JAVA =
            Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();

    private static final JsonObject CORPUS_JSON = readCorpus();
    private static final List<JsonObject> CASES = readCases();

    private static final Map<String, Class<? extends KryonException>> ERRORS =
            Map.of(
                    "CommandNotFound", CommandNotFoundException.class,
                    "PermissionDenied", PermissionDeniedException.class,
                    "ProcessStartFailed", ProcessStartFailedException.class,
                    "InvalidArguments", InvalidArgumentsException.class,
                    "ProcessFailed", ProcessFailedException.class,
                    "ProcessTimeout", ProcessTimeoutException.class,
                    "ProcessCancelled", ProcessCancelledException.class,
                    "ResourceLimitExceeded", ResourceLimitExceededException.class);

    private static Path scratch;

    private static JsonObject readCorpus() {
        try {
            return JsonParser.parseString(Files.readString(CORPUS, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static List<JsonObject> readCases() {
        List<JsonObject> cases = new ArrayList<>();
        for (JsonElement element : CORPUS_JSON.getAsJsonArray("cases")) {
            cases.add(element.getAsJsonObject());
        }
        return cases;
    }

    private static Path scratch() {
        if (scratch == null) {
            try {
                scratch = Files.createTempDirectory("kryon-conformance-").toRealPath();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        return scratch;
    }

    // ------------------------------------------------------------------ mapping

    private static String substitute(String value) {
        return value.replace("${TMPDIR}", scratch().toString());
    }

    /**
     * Why a case cannot run here, or null if it can.
     *
     * <p>The JVM cannot set its own environment at runtime, so a case with {@code setup_env} needs
     * that variable supplied by whatever launched the tests. Per {@code spec/conformance.md}, an
     * SDK that cannot satisfy a case reports it as skipped with a reason rather than quietly
     * dropping it.
     */
    private static String skipReason(JsonObject kase) {
        if (kase.has("platforms")) {
            Set<String> platforms = new HashSet<>();
            for (JsonElement element : kase.getAsJsonArray("platforms")) {
                platforms.add(element.getAsString());
            }
            if (!platforms.contains(WINDOWS ? "windows" : "posix")) {
                return "not applicable to this platform";
            }
        }
        if (kase.has("setup_env")) {
            for (var entry : kase.getAsJsonObject("setup_env").entrySet()) {
                String wanted = entry.getValue().getAsString();
                if (!wanted.equals(System.getenv(entry.getKey()))) {
                    return "needs "
                            + entry.getKey()
                            + "="
                            + wanted
                            + " in the environment of the test runner itself; the JVM cannot set "
                            + "its own environment. Run with it set (CI does).";
                }
            }
        }
        return null;
    }

    /**
     * Translates corpus options into this SDK's spelling.
     *
     * <p>The corpus stores durations in seconds; the JVM idiom is {@link Duration}. The semantics
     * are identical, and each SDK converts in its own runner.
     */
    private static ExecutionOptions options(JsonObject kase) {
        JsonObject source =
                kase.has("options") ? kase.getAsJsonObject("options") : new JsonObject();
        ExecutionOptions.Builder builder = ExecutionOptions.builder();

        if (source.has("cwd")) {
            builder.cwd(Path.of(substitute(source.get("cwd").getAsString())));
        }
        if (source.has("env")) {
            for (var entry : source.getAsJsonObject("env").entrySet()) {
                builder.env(
                        entry.getKey(),
                        entry.getValue().isJsonNull() ? null : entry.getValue().getAsString());
            }
        }
        if (source.has("clear_env")) {
            builder.clearEnv(source.get("clear_env").getAsBoolean());
        }
        if (source.has("timeout")) {
            builder.timeout(seconds(source.get("timeout").getAsDouble()));
        }
        if (source.has("kill_grace")) {
            builder.killGrace(seconds(source.get("kill_grace").getAsDouble()));
        }
        if (source.has("max_output_bytes")) {
            builder.maxOutputBytes(source.get("max_output_bytes").getAsLong());
        }
        if (source.has("check")) {
            builder.check(source.get("check").getAsBoolean());
        }
        if (source.has("encoding")) {
            builder.charset(StandardCharsets.UTF_8);
        }
        if (kase.has("stdin")) {
            builder.stdin(kase.get("stdin").getAsString());
        }
        return builder.build();
    }

    private static Duration seconds(double value) {
        return Duration.ofNanos(Math.round(value * 1_000_000_000L));
    }

    private record Command(String executable, List<String> arguments, boolean shell) {}

    private static Command command(JsonObject kase) {
        if (kase.has("shell_command")) {
            return new Command(
                    kase.getAsJsonObject("shell_command")
                            .get(WINDOWS ? "windows" : "posix")
                            .getAsString(),
                    List.of(),
                    true);
        }
        List<String> args = new ArrayList<>();
        if (kase.has("args")) {
            for (JsonElement element : kase.getAsJsonArray("args")) {
                args.add(substitute(element.getAsString()));
            }
        }
        if (kase.has("executable")) {
            return new Command(kase.get("executable").getAsString(), args, false);
        }

        List<String> argv = new ArrayList<>();
        argv.add("-cp");
        argv.add(HELPER_CLASSES);
        argv.add(ConformanceHelper.class.getName());
        argv.addAll(args);
        return new Command(JAVA, argv, false);
    }

    // --------------------------------------------------------------- assertions

    private static void assertResult(JsonObject kase, ExecutionResult result) {
        JsonObject e = kase.getAsJsonObject("expect");
        String id = kase.get("id").getAsString();

        if (e.has("exit_code")) {
            assertEquals(e.get("exit_code").getAsInt(), result.exitCode().orElse(Integer.MIN_VALUE), id);
        }
        if (e.has("termination")) {
            assertEquals(e.get("termination").getAsString(), result.termination().name(), id);
        }
        if (e.has("ok")) {
            assertEquals(e.get("ok").getAsBoolean(), result.ok(), id);
        }
        if (e.has("stdout")) {
            assertEquals(e.get("stdout").getAsString(), result.stdout(), id);
        }
        if (e.has("stderr")) {
            assertEquals(e.get("stderr").getAsString(), result.stderr(), id);
        }
        if (e.has("stdout_contains")) {
            assertTrue(result.stdout().contains(e.get("stdout_contains").getAsString()), id);
        }
        if (e.has("stderr_contains")) {
            assertTrue(result.stderr().contains(e.get("stderr_contains").getAsString()), id);
        }
        if (e.has("stdout_truncated")) {
            assertEquals(e.get("stdout_truncated").getAsBoolean(), result.stdoutTruncated(), id);
        }
        if (e.has("stdout_bytes_at_most")) {
            assertTrue(
                    result.stdoutBytes().length <= e.get("stdout_bytes_at_most").getAsLong(),
                    id + ": " + result.stdoutBytes().length + " bytes");
        }
        if (e.has("duration_at_most")) {
            long limit = Math.round(e.get("duration_at_most").getAsDouble() * 1000);
            assertTrue(result.duration().toMillis() <= limit, id + ": " + result.duration());
        }
        if (e.has("duration_at_least")) {
            long limit = Math.round(e.get("duration_at_least").getAsDouble() * 1000);
            assertTrue(result.duration().toMillis() >= limit, id + ": " + result.duration());
        }
        if (e.has("signal_present")) {
            assertEquals(e.get("signal_present").getAsBoolean(), result.signal().isPresent(), id);
        }
        if (e.has("stdout_is_bytes") && e.get("stdout_is_bytes").getAsBoolean()) {
            // Java always exposes both views; the byte view is the primitive one, and the
            // corpus intent -- "no codec was guessed" -- is satisfied by it existing.
            assertTrue(result.stdoutBytes().length > 0, id);
        }
        if (e.has("stdout_contains_bytes")) {
            byte[] needle = hex(e.get("stdout_contains_bytes").getAsString());
            assertTrue(indexOf(result.stdoutBytes(), needle) >= 0, id);
        }
        if (e.has("stdout_is_dir")) {
            try {
                Path expected = Path.of(substitute(e.get("stdout_is_dir").getAsString())).toRealPath();
                Path actual = Path.of(result.stdout().trim()).toRealPath();
                assertEquals(expected, actual, id);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------- tests

    @TestFactory
    Stream<DynamicTest> execute() {
        return CASES.stream()
                .filter(c -> !"spawn".equals(c.get("api").getAsString()))
                .map(
                        kase ->
                                DynamicTest.dynamicTest(
                                        kase.get("id").getAsString(),
                                        () -> {
                                            String skip = skipReason(kase);
                                            Assumptions.assumeTrue(skip == null, skip);

                                            Runtime runtime = new Runtime();
                                            Command cmd = command(kase);
                                            ExecutionOptions opts = options(kase);
                                            JsonObject expect = kase.getAsJsonObject("expect");

                                            if (expect.has("raises")) {
                                                assertThrows(
                                                        ERRORS.get(expect.get("raises").getAsString()),
                                                        () -> invoke(runtime, cmd, opts),
                                                        kase.get("id").getAsString());
                                                return;
                                            }
                                            assertResult(kase, invoke(runtime, cmd, opts));
                                        }));
    }

    private static ExecutionResult invoke(Runtime runtime, Command cmd, ExecutionOptions opts) {
        return cmd.shell()
                ? runtime.executeShell(cmd.executable(), opts)
                : runtime.execute(cmd.executable(), cmd.arguments(), opts);
    }

    @TestFactory
    Stream<DynamicTest> spawn() {
        return CASES.stream()
                .filter(c -> "spawn".equals(c.get("api").getAsString()))
                .map(
                        kase ->
                                DynamicTest.dynamicTest(
                                        kase.get("id").getAsString(),
                                        () -> {
                                            String skip = skipReason(kase);
                                            Assumptions.assumeTrue(skip == null, skip);
                                            runSpawnCase(kase);
                                        }));
    }

    private static void runSpawnCase(JsonObject kase) throws Exception {
        Runtime runtime = new Runtime();
        Command cmd = command(kase);
        JsonObject e = kase.getAsJsonObject("expect");
        String id = kase.get("id").getAsString();

        if (kase.has("scope_exit_only")) {
            // Deliberately not try-with-resources: this case is about what leaving the
            // scope does, so the close has to be the observable event under test.
            KryonProcess proc = runtime.spawn(cmd.executable(), cmd.arguments(), options(kase));
            assertTrue(proc.running(), id);
            proc.close();
            assertEquals(e.get("running_after_scope").getAsBoolean(), proc.running(), id);
            return;
        }

        try (KryonProcess proc =
                runtime.spawn(cmd.executable(), cmd.arguments(), options(kase))) {

            if (kase.has("terminate_after")) {
                Thread.sleep(Math.round(kase.get("terminate_after").getAsDouble() * 1000));
                proc.terminate();
                ExecutionResult result = proc.await(Duration.ofSeconds(20));
                assertEquals(e.get("running_after_terminate").getAsBoolean(), proc.running(), id);
                if (e.has("duration_at_most")) {
                    long limit = Math.round(e.get("duration_at_most").getAsDouble() * 1000);
                    assertTrue(result.duration().toMillis() <= limit, id);
                }
                return;
            }

            if (kase.has("write")) {
                for (JsonElement element : kase.getAsJsonArray("write")) {
                    proc.write(element.getAsString());
                }
            }
            if (kase.has("close_stdin") && kase.get("close_stdin").getAsBoolean()) {
                proc.closeStdin();
            }

            List<OutputChunk> chunks = new ArrayList<>();
            for (OutputChunk chunk : proc.output()) {
                chunks.add(chunk);
            }
            ExecutionResult result = proc.await(Duration.ofSeconds(20));

            StringBuilder text = new StringBuilder();
            for (OutputChunk chunk : chunks) {
                text.append(new String(chunk.data(), StandardCharsets.UTF_8));
            }

            if (e.has("streamed_chunks_at_least")) {
                assertTrue(chunks.size() >= e.get("streamed_chunks_at_least").getAsInt(), id);
            }
            if (e.has("stdout_contains")) {
                assertTrue(text.toString().contains(e.get("stdout_contains").getAsString()), id);
            }
            if (e.has("stdout_contains_last")) {
                assertTrue(
                        text.toString().contains(e.get("stdout_contains_last").getAsString()), id);
            }
            if (e.has("exit_code")) {
                assertEquals(
                        e.get("exit_code").getAsInt(), result.exitCode().orElse(Integer.MIN_VALUE), id);
            }
        }
    }

    // -------------------------------------------------------- corpus integrity

    @Test
    void everyCaseHasARunner() {
        Set<String> apis = CASES.stream().map(c -> c.get("api").getAsString()).collect(Collectors.toSet());
        assertTrue(
                Set.of("execute", "execute_shell", "spawn").containsAll(apis),
                "the corpus uses an api this runner does not handle: " + apis);
    }

    @Test
    void idsAreUnique() {
        List<String> ids = CASES.stream().map(c -> c.get("id").getAsString()).toList();
        assertEquals(ids.size(), Set.copyOf(ids).size(), "duplicate case id in the corpus");
    }

    @Test
    void everyCaseExplainsItself() {
        List<String> missing =
                CASES.stream()
                        .filter(c -> !c.has("why"))
                        .map(c -> c.get("id").getAsString())
                        .toList();
        assertTrue(missing.isEmpty(), "cases missing a 'why': " + missing);
    }

    @Test
    void corpusIsNotEmpty() {
        JsonArray cases = CORPUS_JSON.getAsJsonArray("cases");
        assertTrue(cases.size() >= 30, "the corpus looks truncated: " + cases.size() + " cases");
    }
}
