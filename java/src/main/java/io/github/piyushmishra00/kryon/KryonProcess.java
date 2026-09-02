package io.github.piyushmishra00.kryon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A running process you can talk to.
 *
 * <p>Output arrives through {@link #output()} as {@link OutputChunk}s in the order Kryon observed
 * them. Chunk boundaries mean nothing -- they reflect how the operating system delivered the data,
 * not lines or records.
 *
 * <p>The stream is bounded. If you stop consuming, Kryon stops reading, the pipe fills and the
 * child blocks. That is backpressure working, not a hang: a program that produces faster than you
 * consume must be slowed down somewhere, and the kernel buffer is a better place than your heap.
 *
 * <p>Obtain one from {@link Runtime#spawn}, and use try-with-resources so it cannot outlive its
 * scope:
 *
 * <pre>{@code
 * try (KryonProcess proc = runtime.spawn("node", List.of("worker.js"))) {
 *     proc.write("job-1\n");
 *     proc.closeStdin();
 *     for (OutputChunk chunk : proc.output()) {
 *         System.out.write(chunk.data());
 *     }
 *     ExecutionResult result = proc.await();
 * }
 * }</pre>
 */
public final class KryonProcess implements AutoCloseable {

    /**
     * Bounded queue depth. This is the backpressure knob: once the consumer is this far behind,
     * Kryon stops reading, the OS pipe fills, and the child blocks.
     */
    private static final int QUEUE_DEPTH = 64;

    private static final int CHUNK = 65536;

    /** Sentinel marking the end of one source stream. */
    private static final OutputChunk END = new OutputChunk(StreamKind.STDOUT, new byte[0]);

    private final Process process;
    private final String executable;
    private final List<String> arguments;
    private final ExecutionOptions options;
    private final long started = System.nanoTime();

    private final BlockingQueue<OutputChunk> queue = new ArrayBlockingQueue<>(QUEUE_DEPTH);
    private final AtomicReference<TerminationReason> reason = new AtomicReference<>();
    private final AtomicLong bytesSeen = new AtomicLong();
    private final AtomicBoolean consumed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final List<Thread> readers = new ArrayList<>();
    private final Thread deadline;
    private volatile boolean stdinClosed;

    KryonProcess(
            Process process,
            String executable,
            List<String> arguments,
            ExecutionOptions options) {

        this.process = process;
        this.executable = executable;
        this.arguments = List.copyOf(arguments);
        this.options = options;

        readers.add(reader(StreamKind.STDOUT, process.getInputStream()));
        readers.add(reader(StreamKind.STDERR, process.getErrorStream()));

        this.deadline = options.timeout().map(this::startDeadline).orElse(null);
    }

    private Thread startDeadline(Duration timeout) {
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                                        && reason.compareAndSet(null, TerminationReason.TIMEOUT)) {
                                    Runtime.stop(process, options.killGrace());
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "kryon-deadline");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * The operating-system process id.
     *
     * @return the pid
     */
    public long pid() {
        return process.pid();
    }

    /**
     * Whether the process is still alive.
     *
     * @return true while running
     */
    public boolean running() {
        return process.isAlive();
    }

    /**
     * The exit status once the process has been reaped.
     *
     * @return the exit code, or empty while still running
     */
    public OptionalInt exitCode() {
        return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    /**
     * Writes text to the child's stdin, encoded with the configured charset (UTF-8 when none is
     * set).
     *
     * @param text the text to write
     */
    public void write(String text) {
        write(text.getBytes(options.charset().orElse(StandardCharsets.UTF_8)));
    }

    /**
     * Writes bytes to the child's stdin.
     *
     * <p>Throws if stdin is already closed -- dropping input silently is the failure mode that
     * produces hangs nobody can reproduce.
     *
     * @param data the bytes to write
     */
    public void write(byte[] data) {
        if (stdinClosed) {
            throw new IllegalStateException("stdin of pid " + pid() + " is closed");
        }
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write(data);
            stdin.flush();
        } catch (IOException error) {
            // The child stopped reading. Its exit code, not this write, is the story.
        }
    }

    /** Closes stdin, signalling end-of-input to the child. */
    public void closeStdin() {
        if (stdinClosed) {
            return;
        }
        stdinClosed = true;
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    /**
     * Chunks of output until both pipes reach end-of-input.
     *
     * <p>There is one consumer. Iterating twice throws, because the second iterator would silently
     * steal chunks from the first.
     *
     * @return a one-shot iterable of output chunks
     */
    public Iterable<OutputChunk> output() {
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "output of pid "
                            + pid()
                            + " is already being consumed; a process has one output stream, and "
                            + "two readers would each get an arbitrary half of it");
        }
        return OutputIterator::new;
    }

    /**
     * Waits for exit and returns the outcome.
     *
     * <p>{@code stdout} and {@code stderr} on the result are empty: the output was streamed to you
     * through {@link #output()} and is deliberately not buffered a second time.
     *
     * @return the outcome
     */
    public ExecutionResult await() {
        return await(null);
    }

    /**
     * Waits for exit, up to {@code timeout}, and returns the outcome.
     *
     * @param timeout how long to wait, or null to wait indefinitely
     * @return the outcome
     * @throws ProcessTimeoutException if the timeout elapses. The process is left running -- this
     *     is a wait, not a stop. Call {@link #terminate()} or {@link #close()} for that.
     */
    public ExecutionResult await(Duration timeout) {
        try {
            if (timeout == null) {
                process.waitFor();
            } else if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ProcessTimeoutException(
                        "'" + executable + "' (pid " + pid() + ") still running after " + timeout,
                        null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProcessCancelledException(
                    "interrupted while waiting for '" + executable + "'", null);
        }

        Runtime.Outcome outcome = Runtime.classify(process, reason.get());
        return new ExecutionResult(
                executable,
                arguments,
                outcome.exitCode(),
                outcome.signal(),
                new byte[0],
                new byte[0],
                options.charset().orElse(null),
                Duration.ofNanos(System.nanoTime() - started),
                outcome.termination(),
                process.pid(),
                false,
                false);
    }

    /**
     * Sends a specific signal to the process.
     *
     * <p>The JDK exposes no general signal API, so this supports the two signals
     * {@link Process} can send: {@code SIGTERM} (15) and {@code SIGKILL} (9).
     *
     * @param signalNumber the POSIX signal number
     * @throws UnsupportedPlatformException on Windows, which has no signals to send
     * @throws InvalidArgumentsException for a signal the JDK cannot send
     */
    public void signal(int signalNumber) {
        if (Runtime.WINDOWS) {
            throw new UnsupportedPlatformException(
                    "Windows has no signals; use terminate(), which kills the process outright "
                            + "without letting it clean up");
        }
        switch (signalNumber) {
            case 15 -> process.destroy();
            case 9 -> process.destroyForcibly();
            default ->
                    throw new InvalidArgumentsException(
                            "the JDK can only send SIGTERM (15) and SIGKILL (9); "
                                    + signalNumber
                                    + " would need a native call Kryon does not make");
        }
    }

    /**
     * Requests a polite stop: {@code SIGTERM} on POSIX, {@code TerminateProcess} on Windows.
     *
     * <p>On Windows this is identical to {@link #kill()}. There is no graceful stop.
     */
    public void terminate() {
        process.destroy();
    }

    /** Forces a stop: {@code SIGKILL} on POSIX, {@code TerminateProcess} on Windows. */
    public void kill() {
        process.destroyForcibly();
    }

    /**
     * Terminates the process if it is still running and releases every resource.
     *
     * <p>Idempotent. This is what leaving a try-with-resources block does.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (deadline != null) {
            deadline.interrupt();
        }
        closeStdin();

        if (process.isAlive()) {
            reason.compareAndSet(null, TerminationReason.CANCELLED);
            Runtime.stop(process, options.killGrace());
        }

        // A reader blocked on a full queue never sees the pipe close, so an unconsumed process
        // would leak two threads and two file descriptors. Draining releases them.
        draining.set(true);
        long deadlineMillis =
                System.currentTimeMillis() + Math.max(options.killGrace().toMillis(), 1000L);
        while (System.currentTimeMillis() < deadlineMillis && anyReaderAlive()) {
            queue.poll();
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread reader : readers) {
            try {
                reader.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean anyReaderAlive() {
        for (Thread reader : readers) {
            if (reader.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private Thread reader(StreamKind tag, InputStream source) {
        Thread thread =
                new Thread(
                        () -> {
                            byte[] buffer = new byte[CHUNK];
                            try {
                                int read;
                                while ((read = source.read(buffer)) != -1) {
                                    long total = bytesSeen.addAndGet(read);
                                    Long limit = options.maxOutputBytes().orElse(null);
                                    if (limit != null
                                            && total > limit
                                            && reason.compareAndSet(
                                                    null, TerminationReason.OUTPUT_LIMIT)) {
                                        Runtime.stop(process, options.killGrace());
                                    }
                                    if (draining.get()) {
                                        continue;
                                    }
                                    queue.put(
                                            new OutputChunk(tag, Arrays.copyOf(buffer, read)));
                                }
                            } catch (IOException ignored) {
                                // Pipe closed underneath us during shutdown.
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } finally {
                                try {
                                    queue.put(END);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        },
                        "kryon-reader-" + tag);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Drains the bounded queue until both source streams have signalled end-of-input. */
    private final class OutputIterator implements Iterator<OutputChunk> {

        private int openStreams = 2;
        private OutputChunk next;

        @Override
        public boolean hasNext() {
            while (next == null && openStreams > 0) {
                OutputChunk chunk;
                try {
                    chunk = queue.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (chunk == END) {
                    openStreams--;
                } else {
                    next = chunk;
                }
            }
            return next != null;
        }

        @Override
        public OutputChunk next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            OutputChunk chunk = next;
            next = null;
            return chunk;
        }
    }

    @Override
    public String toString() {
        return "KryonProcess[pid="
                + pid()
                + ", "
                + executable
                + ", "
                + (running() ? "running" : "exited " + process.exitValue())
                + "]";
    }
}
