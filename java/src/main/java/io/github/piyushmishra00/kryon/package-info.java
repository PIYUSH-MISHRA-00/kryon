/**
 * Kryon -- powerful terminal execution, everywhere.
 *
 * <p>Kryon runs operating-system commands and manages the processes behind them, with one
 * conceptual API implemented across Python, TypeScript, Dart, Java and Kotlin. This is the Java
 * SDK, and it has zero runtime dependencies.
 *
 * <p>Two operations:
 *
 * <pre>{@code
 * Runtime runtime = new Runtime(ExecutionOptions.builder()
 *         .charset(StandardCharsets.UTF_8)
 *         .timeout(Duration.ofSeconds(30))
 *         .build());
 *
 * // Run it and tell me what happened.
 * ExecutionResult result = runtime.execute("git", List.of("status", "--porcelain"));
 * System.out.println(result.stdout() + " " + result.exitCode() + " " + result.ok());
 *
 * // Start it and let me talk to it.
 * try (KryonProcess proc = runtime.spawn("node", List.of("worker.js"))) {
 *     proc.write("job-1\n");
 *     proc.closeStdin();
 *     for (OutputChunk chunk : proc.output()) {
 *         System.out.write(chunk.data());
 *     }
 * }
 * }</pre>
 *
 * <p>Two things worth knowing before using this in anger:
 *
 * <ul>
 *   <li><strong>Arguments are not interpreted.</strong> {@code execute("echo", List.of("$HOME"))}
 *       prints {@code $HOME}. Shell semantics require the separately named
 *       {@link io.github.piyushmishra00.kryon.Runtime#executeShell}, because a
 *       {@code shell(true)} flag is too easy to set by accident.
 *   <li><strong>Kryon is not a sandbox.</strong> Its timeouts and output caps manage resources;
 *       they do not contain a hostile program. See {@code docs/security/threat-model.md}.
 * </ul>
 *
 * <p>The Maven coordinates are {@code io.github.piyush-mishra-00:kryon}; the Java package drops
 * the hyphens because a package identifier cannot contain them.
 */
package io.github.piyushmishra00.kryon;
