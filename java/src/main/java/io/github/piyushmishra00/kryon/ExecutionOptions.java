package io.github.piyushmishra00.kryon;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable options for a single execution.
 *
 * <p>Built with {@link #builder()}. A {@link Runtime} carries defaults and each call may override
 * them; {@code env} merges, everything else is replaced.
 *
 * <pre>{@code
 * ExecutionOptions options = ExecutionOptions.builder()
 *         .timeout(Duration.ofSeconds(30))
 *         .charset(StandardCharsets.UTF_8)
 *         .clearEnv(true)
 *         .env("PATH", "/usr/bin:/bin")
 *         .build();
 * }</pre>
 */
public final class ExecutionOptions {

    private static final ExecutionOptions DEFAULTS = builder().build();

    private final Path cwd;
    private final Map<String, String> env;
    private final Boolean clearEnv;
    private final byte[] stdin;
    private final Duration timeout;
    private final Long maxOutputBytes;
    private final Charset charset;
    private final Boolean check;
    private final Duration killGrace;

    private ExecutionOptions(Builder builder) {
        this.cwd = builder.cwd;
        this.env = Collections.unmodifiableMap(new LinkedHashMap<>(builder.env));
        this.clearEnv = builder.clearEnv;
        this.stdin = builder.stdin;
        this.timeout = builder.timeout;
        this.maxOutputBytes = builder.maxOutputBytes;
        this.charset = builder.charset;
        this.check = builder.check;
        this.killGrace = builder.killGrace;
    }

    /**
     * A fresh builder.
     *
     * @return a builder with nothing set
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Options with nothing set.
     *
     * @return the shared empty instance
     */
    public static ExecutionOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Working directory. Empty means inherited. A path that is not a directory is an error, never
     * a silent fallback to the current directory.
     *
     * @return the working directory, if set
     */
    public Optional<Path> cwd() {
        return Optional.ofNullable(cwd);
    }

    /**
     * Variables merged <em>over</em> the inherited environment.
     *
     * <p>A value of {@code null} removes the variable. To control the environment strictly,
     * combine with {@link #clearEnv()}.
     *
     * @return the overrides, never null
     */
    public Map<String, String> env() {
        return env;
    }

    /**
     * Whether to start from an empty environment instead of inheriting one.
     *
     * <p>With {@link #env()}, this is an allowlist. On Windows, {@code SystemRoot} and
     * {@code SystemDrive} are still preserved, because many binaries fail to start without them.
     *
     * @return true to clear
     */
    public boolean clearEnv() {
        return clearEnv != null && clearEnv;
    }

    /**
     * Data written to the child's stdin, after which stdin is closed.
     *
     * @return a copy of the bytes, if set
     */
    public Optional<byte[]> stdin() {
        return Optional.ofNullable(stdin).map(byte[]::clone);
    }

    /**
     * Wall-clock limit. On expiry the process is terminated politely, then killed after
     * {@link #killGrace()}. Output collected so far is kept.
     *
     * @return the timeout, if set
     */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * Per-stream cap in bytes, counted before decoding.
     *
     * @return the cap, if set
     */
    public Optional<Long> maxOutputBytes() {
        return Optional.ofNullable(maxOutputBytes);
    }

    /**
     * The charset used to decode output, if any.
     *
     * <p>When absent, output stays as bytes. Decoding is lossy by design: an output cap can cut a
     * multi-byte character in half, and throwing on that would turn a truncation into a crash.
     *
     * @return the charset, if set
     */
    public Optional<Charset> charset() {
        return Optional.ofNullable(charset);
    }

    /**
     * Whether to throw instead of returning when the result is not successful.
     *
     * @return true to throw
     */
    public boolean check() {
        return check != null && check;
    }

    /**
     * Time between the polite stop and the forced kill.
     *
     * @return the grace period; five seconds unless set
     */
    public Duration killGrace() {
        return killGrace == null ? Duration.ofSeconds(5) : killGrace;
    }

    /**
     * Returns a copy with {@code overrides} applied. {@code env} merges rather than replaces.
     *
     * @param overrides the options to layer on top, may be null
     * @return the merged options
     */
    public ExecutionOptions mergedWith(ExecutionOptions overrides) {
        if (overrides == null) {
            return this;
        }
        Builder merged = builder();
        merged.cwd = overrides.cwd != null ? overrides.cwd : cwd;
        merged.env.putAll(env);
        merged.env.putAll(overrides.env);
        merged.clearEnv = overrides.clearEnv != null ? overrides.clearEnv : clearEnv;
        merged.stdin = overrides.stdin != null ? overrides.stdin : stdin;
        merged.timeout = overrides.timeout != null ? overrides.timeout : timeout;
        merged.maxOutputBytes =
                overrides.maxOutputBytes != null ? overrides.maxOutputBytes : maxOutputBytes;
        merged.charset = overrides.charset != null ? overrides.charset : charset;
        merged.check = overrides.check != null ? overrides.check : check;
        merged.killGrace = overrides.killGrace != null ? overrides.killGrace : killGrace;
        return merged.build();
    }

    /** Rejects a malformed request before anything is spawned. */
    void validate() {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new InvalidArgumentsException("timeout must be positive, got " + timeout);
        }
        if (maxOutputBytes != null && maxOutputBytes <= 0) {
            throw new InvalidArgumentsException(
                    "maxOutputBytes must be positive, got " + maxOutputBytes);
        }
        if (killGrace != null && killGrace.isNegative()) {
            throw new InvalidArgumentsException(
                    "killGrace must not be negative, got " + killGrace);
        }
    }

    /** Builder for {@link ExecutionOptions}. */
    public static final class Builder {

        private Path cwd;
        private final Map<String, String> env = new LinkedHashMap<>();
        private Boolean clearEnv;
        private byte[] stdin;
        private Duration timeout;
        private Long maxOutputBytes;
        private Charset charset;
        private Boolean check;
        private Duration killGrace;

        private Builder() {}

        /**
         * Sets the working directory.
         *
         * @param value the directory
         * @return this builder
         */
        public Builder cwd(Path value) {
            this.cwd = value;
            return this;
        }

        /**
         * Adds one environment variable override.
         *
         * @param name the variable name
         * @param value the value, or null to remove the variable from the child
         * @return this builder
         */
        public Builder env(String name, String value) {
            this.env.put(name, value);
            return this;
        }

        /**
         * Adds several environment variable overrides.
         *
         * @param values the overrides; a null value removes that variable
         * @return this builder
         */
        public Builder env(Map<String, String> values) {
            this.env.putAll(values);
            return this;
        }

        /**
         * Starts from an empty environment instead of inheriting one.
         *
         * @param value true to clear
         * @return this builder
         */
        public Builder clearEnv(boolean value) {
            this.clearEnv = value;
            return this;
        }

        /**
         * Sets the data written to the child's stdin.
         *
         * @param value the bytes; copied
         * @return this builder
         */
        public Builder stdin(byte[] value) {
            this.stdin = value == null ? null : value.clone();
            return this;
        }

        /**
         * Sets the data written to the child's stdin, encoded with the configured charset (UTF-8
         * when none is set).
         *
         * @param value the text
         * @return this builder
         */
        public Builder stdin(String value) {
            this.stdin =
                    value == null
                            ? null
                            : value.getBytes(charset == null ? java.nio.charset.StandardCharsets.UTF_8 : charset);
            return this;
        }

        /**
         * Sets the wall-clock limit.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        /**
         * Sets the per-stream output cap in bytes.
         *
         * @param value the cap
         * @return this builder
         */
        public Builder maxOutputBytes(long value) {
            this.maxOutputBytes = value;
            return this;
        }

        /**
         * Sets the charset used to decode output. Leave unset for bytes.
         *
         * @param value the charset
         * @return this builder
         */
        public Builder charset(Charset value) {
            this.charset = value;
            return this;
        }

        /**
         * Throws instead of returning when the result is not successful.
         *
         * @param value true to throw
         * @return this builder
         */
        public Builder check(boolean value) {
            this.check = value;
            return this;
        }

        /**
         * Sets the time between the polite stop and the forced kill.
         *
         * @param value the grace period
         * @return this builder
         */
        public Builder killGrace(Duration value) {
            this.killGrace = value;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the immutable options
         */
        public ExecutionOptions build() {
            return new ExecutionOptions(this);
        }
    }
}
