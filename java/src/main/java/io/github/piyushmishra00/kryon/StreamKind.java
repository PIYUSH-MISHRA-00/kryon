package io.github.piyushmishra00.kryon;

/**
 * Which pipe a chunk of output arrived on.
 *
 * <p>Named {@code StreamKind} rather than {@code Stream} or {@code OutputStream} on purpose: both
 * of those collide with types practically every Java file already imports
 * ({@link java.util.stream.Stream} and {@link java.io.OutputStream}), and a Kryon type sharing
 * their simple name would force callers to fully qualify one of them forever. The specification
 * fixes semantics, not spelling.
 */
public enum StreamKind {

    /** Standard output. */
    STDOUT,

    /** Standard error. */
    STDERR
}
