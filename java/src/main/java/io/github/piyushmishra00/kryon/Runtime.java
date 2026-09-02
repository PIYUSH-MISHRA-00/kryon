package io.github.piyushmishra00.kryon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Kryon runtime.
 *
 * <p>Two operations, deliberately separate:
 *
 * <ul>
 *   <li>{@link #execute} runs a program to completion and hands back everything it produced. Use
 *       it when you want the answer.
 *   <li>{@link #spawn} starts a program and hands back a {@link KryonProcess} you can write to,
 *       read from and signal while it runs. Use it when you want a conversation.
 * </ul>
 *
 * <p>A {@code Runtime} holds configuration, not state. It is safe to share across threads, and
 * creating one is cheap enough that you can also just make a new one:
 *
 * <pre>{@code
 * Runtime runtime = new Runtime(ExecutionOptions.builder()
 *         .charset(StandardCharsets.UTF_8)
 *         .timeout(Duration.ofSeconds(30))
 *         .build());
 *
 * ExecutionResult result = runtime.execute("git", List.of("status", "--porcelain"));
 * }</pre>
 */
public final class Runtime {

    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    /**
     * Preserved even when {@code clearEnv} is set. Windows binaries -- including the ones in
     * {@code System32} -- routinely fail to start without these.
     */
    private static final List<String> WINDOWS_ESSENTIAL = List.of("SystemRoot", "SystemDrive");

    private static final int CHUNK = 65536;

    private final ExecutionOptions defaults;

    /** Creates a runtime with no default options. */
    public Runtime() {
        this(ExecutionOptions.defaults());
    }

    /**
     * Creates a runtime with default options applied to every call.
     *
     * @param defaults the defaults; each call may override them
     */
    public Runtime(ExecutionOptions defaults) {
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.defaults.validate();
    }

    /**
     * The options this runtime applies when a call does not override them.
     *
     * @return the defaults
     */
    public ExecutionOptions defaults() {
        return defaults;
    }

    /**
     * Runs {@code executable} with no arguments.
     *
     * @param executable the program to run
     * @return what happened
     */
    public ExecutionResult execute(String executable) {
        return execute(executable, List.of(), null);
    }

    /**
     * Runs {@code executable} with {@code arguments}.
     *
     * @param executable the program to run
     * @param arguments the argument vector
     * @return what happened
     */
    public ExecutionResult execute(String executable, List<String> arguments) {
        return execute(executable, arguments, null);
    }

    /**
     * Runs {@code executable} with {@code arguments} and returns what happened.
     *
     * <p>Arguments are passed to the operating system as a vector. Nothing in them is interpreted
     * -- {@code execute("echo", List.of("$HOME && rm -rf /"))} prints that text literally. For
     * shell semantics you must ask for them by name; see {@link #executeShell}.
     *
     * <p>A process that could not be started throws. A process that started and then failed is
     * returned, because a non-zero exit is information: {@code grep} exits {@code 1} to mean "no
     * match". Set {@code check(true)} to throw on those too.
     *
     * @param executable the program to run
     * @param arguments the argument vector
     * @param options per-call options layered over this runtime's defaults, may be null
     * @return what happened
     * @throws CommandNotFoundException if the executable could not be resolved
     * @throws PermissionDeniedException if the executable could not be run
     * @throws ProcessStartFailedException if the process could not be created
     * @throws InvalidArgumentsException if the request was malformed
     */
    public ExecutionResult execute(
            String executable, List<String> arguments, ExecutionOptions options) {
        return run(executable, arguments, options, false);
    }

    /**
     * Runs {@code commandLine} through the system shell.
     *
     * <p><strong>The shell interprets quoting, globbing, variable expansion, pipes and command
     * chaining. Building this string from untrusted input is a command-injection
     * vulnerability.</strong> If you are interpolating a value, you almost certainly want
     * {@link #execute} with an argument list instead.
     *
     * <p>The shell is {@code /bin/sh -c} on POSIX and {@code cmd.exe /d /s /c} on Windows.
     *
     * <p>This method exists as a separate name on purpose. A {@code shell(true)} flag sitting
     * among a dozen options is easy to set by accident and easy to miss in review; a differently
     * named method is not.
     *
     * @param commandLine the command line, interpreted by the shell
     * @return what happened
     */
    public ExecutionResult executeShell(String commandLine) {
        return executeShell(commandLine, null);
    }

    /**
     * Runs {@code commandLine} through the system shell.
     *
     * @param commandLine the command line, interpreted by the shell
     * @param options per-call options layered over this runtime's defaults, may be null
     * @return what happened
     * @see #executeShell(String)
     */
    public ExecutionResult executeShell(String commandLine, ExecutionOptions options) {
        if (commandLine == null) {
            throw new InvalidArgumentsException("commandLine must not be null");
        }
        return run(commandLine, List.of(), options, true);
    }

    private ExecutionResult run(
            String executable, List<String> arguments, ExecutionOptions overrides, boolean shell) {

        ExecutionOptions options = defaults.mergedWith(overrides);
        options.validate();
        List<String> argv = normalise(executable, arguments);
        checkCwd(options);

        long started = System.nanoTime();
        Process process = start(executable, argv, options, shell);

        AtomicReference<TerminationReason> reason = new AtomicReference<>();
        Sink out = new Sink(options.maxOutputBytes().orElse(null));
        Sink err = new Sink(options.maxOutputBytes().orElse(null));

        Runnable stopper = () -> stop(process, options.killGrace());

        Thread pumpOut = pump(process.getInputStream(), out, reason, stopper);
        Thread pumpErr = pump(process.getErrorStream(), err, reason, stopper);
        Thread feeder = feed(process, options);

        boolean exited;
        try {
            if (options.timeout().isPresent()) {
                Duration timeout = options.timeout().get();
                exited = process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
                if (!exited) {
                    reason.compareAndSet(null, TerminationReason.TIMEOUT);
                    stopper.run();
                    process.waitFor();
                }
            } else {
                process.waitFor();
            }
        } catch (InterruptedException interrupted) {
            // Returning control while leaving the child running is not an option.
            reason.compareAndSet(null, TerminationReason.CANCELLED);
            stopper.run();
            Thread.currentThread().interrupt();
            throw new ProcessCancelledException(
                    "'" + executable + "' was interrupted while running", null);
        } finally {
            // Bounded, because a grandchild can hold the pipes open after the child exits.
            long graceMillis = Math.max(options.killGrace().toMillis(), 1000L);
            join(pumpOut, graceMillis);
            join(pumpErr, graceMillis);
            join(feeder, graceMillis);
        }

        Outcome outcome = classify(process, reason.get());
        ExecutionResult result =
                new ExecutionResult(
                        executable,
                        argv,
                        outcome.exitCode(),
                        outcome.signal(),
                        out.value(),
                        err.value(),
                        options.charset().orElse(null),
                        Duration.ofNanos(System.nanoTime() - started),
                        outcome.termination(),
                        process.pid(),
                        out.truncated(),
                        err.truncated());

        return options.check() ? result.checked() : result;
    }

    /**
     * Starts {@code executable} and returns a {@link KryonProcess} to interact with.
     *
     * <p>Returns as soon as the process has started. {@code KryonProcess} is
     * {@link AutoCloseable}, so use try-with-resources and the process cannot outlive the block.
     *
     * @param executable the program to run
     * @param arguments the argument vector
     * @return the running process
     */
    public KryonProcess spawn(String executable, List<String> arguments) {
        return spawn(executable, arguments, null);
    }

    /**
     * Starts {@code executable} and returns a {@link KryonProcess} to interact with.
     *
     * @param executable the program to run
     * @param arguments the argument vector
     * @param options per-call options layered over this runtime's defaults, may be null
     * @return the running process
     */
    public KryonProcess spawn(
            String executable, List<String> arguments, ExecutionOptions options) {
        ExecutionOptions merged = defaults.mergedWith(options);
        merged.validate();
        List<String> argv = normalise(executable, arguments);
        checkCwd(merged);
        return new KryonProcess(start(executable, argv, merged, false), executable, argv, merged);
    }

    // ------------------------------------------------------------------ internals

    private static List<String> normalise(String executable, List<String> arguments) {
        if (executable == null) {
            throw new InvalidArgumentsException("executable must not be null");
        }
        if (executable.isEmpty()) {
            throw new InvalidArgumentsException("executable must not be empty");
        }
        List<String> argv = new ArrayList<>();
        List<String> source = arguments == null ? List.of() : arguments;
        for (int i = 0; i < source.size(); i++) {
            String value = source.get(i);
            if (value == null) {
                throw new InvalidArgumentsException(
                        "argument "
                                + i
                                + " must not be null; Kryon does not stringify arguments for you, "
                                + "because guessing how to render a value into a command line is "
                                + "how injection bugs start");
            }
            argv.add(value);
        }
        return argv;
    }

    private static void checkCwd(ExecutionOptions options) {
        if (options.cwd().isEmpty()) {
            return;
        }
        Path cwd = options.cwd().get();
        if (!Files.exists(cwd)) {
            throw new ProcessStartFailedException("working directory does not exist: " + cwd);
        }
        if (!Files.isDirectory(cwd)) {
            throw new ProcessStartFailedException("working directory is not a directory: " + cwd);
        }
    }

    private static Process start(
            String executable, List<String> argv, ExecutionOptions options, boolean shell) {

        List<String> command = new ArrayList<>();
        if (shell) {
            if (WINDOWS) {
                command.addAll(List.of(shellExecutable(), "/d", "/s", "/c", executable));
            } else {
                command.addAll(List.of(shellExecutable(), "-c", executable));
            }
        } else {
            command.add(executable);
            command.addAll(argv);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        options.cwd().ifPresent(cwd -> builder.directory(cwd.toFile()));
        applyEnvironment(builder, options);

        try {
            return builder.start();
        } catch (IOException error) {
            throw mapStartError(error, executable);
        }
    }

    private static String shellExecutable() {
        if (WINDOWS) {
            String comspec = System.getenv("COMSPEC");
            return comspec == null || comspec.isEmpty() ? "cmd.exe" : comspec;
        }
        return "/bin/sh";
    }

    private static void applyEnvironment(ProcessBuilder builder, ExecutionOptions options) {
        Map<String, String> environment = builder.environment();
        if (options.clearEnv()) {
            Map<String, String> preserved = new HashMap<>();
            if (WINDOWS) {
                for (String key : WINDOWS_ESSENTIAL) {
                    String value = environment.get(key);
                    if (value != null) {
                        preserved.put(key, value);
                    }
                }
            }
            environment.clear();
            environment.putAll(preserved);
        }
        options.env()
                .forEach(
                        (key, value) -> {
                            if (value == null) {
                                environment.remove(key);
                            } else {
                                environment.put(key, value);
                            }
                        });
    }

    private static KryonException mapStartError(IOException error, String executable) {
        String message = String.valueOf(error.getMessage());
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("no such file")
                || lower.contains("cannot find")
                || lower.contains("cannot run program")
                        && (lower.contains("error=2") || lower.contains("error=3"))) {
            return new CommandNotFoundException("executable not found: '" + executable + "'", error);
        }
        if (lower.contains("permission denied")
                || lower.contains("access is denied")
                || lower.contains("error=13")) {
            return new PermissionDeniedException(
                    "not permitted to execute '" + executable + "': " + message, error);
        }
        return new ProcessStartFailedException(
                "could not start '" + executable + "': " + message, error);
    }

    private static Thread pump(
            InputStream source,
            Sink sink,
            AtomicReference<TerminationReason> reason,
            Runnable stopper) {

        Thread thread =
                new Thread(
                        () -> {
                            byte[] buffer = new byte[CHUNK];
                            try {
                                int read;
                                while ((read = source.read(buffer)) != -1) {
                                    if (sink.add(buffer, read)
                                            && reason.compareAndSet(
                                                    null, TerminationReason.OUTPUT_LIMIT)) {
                                        stopper.run();
                                    }
                                }
                            } catch (IOException ignored) {
                                // Pipe closed underneath us during shutdown.
                            }
                        },
                        "kryon-pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static Thread feed(Process process, ExecutionOptions options) {
        byte[] data = options.stdin().orElse(new byte[0]);
        Thread thread =
                new Thread(
                        () -> {
                            try (var stdin = process.getOutputStream()) {
                                if (data.length > 0) {
                                    stdin.write(data);
                                    stdin.flush();
                                }
                            } catch (IOException ignored) {
                                // The child exited before reading its input; its exit code is the
                                // story.
                            }
                        },
                        "kryon-feed");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Terminates politely, then kills. The single termination path in this SDK.
     *
     * <p>On Windows both steps are {@code TerminateProcess}: there is no graceful stop, and the
     * child gets no chance to flush.
     */
    static void stop(Process process, Duration killGrace) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (process.waitFor(killGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
        try {
            process.waitFor(killGrace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The reported outcome: what Kryon decided, plus the raw exit status. */
    record Outcome(TerminationReason termination, Integer exitCode, Integer signal) {}

    /**
     * Maps a raw exit value plus any Kryon intervention to the reported outcome.
     *
     * <p>The JVM reports a signal death as {@code 128 + signum} on POSIX and gives no signal
     * number of its own, which is why {@code signal} is derived rather than read.
     */
    static Outcome classify(Process process, TerminationReason reason) {
        int raw = process.exitValue();
        boolean signaled = !WINDOWS && raw > 128 && raw < 192;
        TerminationReason natural =
                signaled ? TerminationReason.SIGNALED : TerminationReason.EXITED;
        return new Outcome(
                reason == null ? natural : reason,
                signaled ? null : raw,
                signaled ? raw - 128 : null);
    }

    /** A byte sink that stops growing at a limit and remembers that it did. */
    static final class Sink {

        private final Long limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated;

        Sink(Long limit) {
            this.limit = limit;
        }

        /**
         * Appends {@code length} bytes. Returns true when the limit has just been exceeded.
         *
         * <p>Data past the limit is dropped rather than counted: the point of a cap is not to hold
         * the bytes.
         */
        synchronized boolean add(byte[] data, int length) {
            if (limit == null) {
                buffer.write(data, 0, length);
                return false;
            }
            long room = limit - buffer.size();
            if (room > 0) {
                buffer.write(data, 0, (int) Math.min(room, length));
            }
            if (length > room) {
                truncated = true;
                return true;
            }
            return false;
        }

        synchronized byte[] value() {
            return buffer.toByteArray();
        }

        boolean truncated() {
            return truncated;
        }
    }
}
