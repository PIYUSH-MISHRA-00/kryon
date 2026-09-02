package io.github.piyushmishra00.kryon;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * The Java implementation of the Kryon conformance helper.
 *
 * <p>Implements the verb contract in {@code spec/conformance.md} §2. Every SDK ships one of these
 * in its own language so that the shared corpus in {@code tests/conformance/cases.json} means the
 * same thing everywhere -- {@code echo}, {@code sleep} and {@code /bin/sh} do not, and half of
 * them do not exist on Windows.
 *
 * <p>Deliberately dependency-free and deliberately unbuffered: a helper that buffers turns
 * streaming tests into false failures.
 *
 * <p>Run as {@code java -cp <test-classes> io.github.piyushmishra00.kryon.ConformanceHelper
 * <verb> [args...]}.
 */
public final class ConformanceHelper {

    private static final PrintStream OUT =
            new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final PrintStream ERR =
            new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);

    private ConformanceHelper() {}

    /**
     * Entry point.
     *
     * @param argv the verb and its arguments
     * @throws Exception if a verb fails in a way the helper cannot report
     */
    public static void main(String[] argv) throws Exception {
        System.exit(run(argv));
    }

    @SuppressWarnings("PMD")
    static int run(String[] argv) throws IOException, InterruptedException {
        if (argv.length == 0) {
            ERR.print("helper: no verb given\n");
            return 64;
        }

        String verb = argv[0];
        List<String> args = new ArrayList<>(Arrays.asList(argv).subList(1, argv.length));

        switch (verb) {
            case "echo" -> OUT.print(String.join(" ", args) + "\n");
            case "raw" -> OUT.print(args.isEmpty() ? "" : args.get(0));
            case "err" -> ERR.print(String.join(" ", args) + "\n");
            case "both" -> {
                OUT.print(args.get(0) + "\n");
                ERR.print(args.get(1) + "\n");
            }
            case "exit" -> {
                return Integer.parseInt(args.get(0));
            }
            case "env" -> {
                String value = System.getenv(args.get(0));
                OUT.print((value == null ? "" : value) + "\n");
            }
            case "dumpenv" -> {
                new TreeMap<>(System.getenv())
                        .forEach((key, value) -> OUT.print(key + "=" + value + "\n"));
            }
            case "cwd" -> OUT.print(Path.of("").toAbsolutePath() + "\n");
            case "sleep" -> Thread.sleep(millis(args.get(0)));
            case "spam" -> {
                long remaining = Long.parseLong(args.get(0));
                byte[] block = new byte[(int) Math.min(remaining, 65536)];
                Arrays.fill(block, (byte) 'x');
                var raw = new java.io.FileOutputStream(java.io.FileDescriptor.out);
                while (remaining > 0) {
                    int take = (int) Math.min(remaining, block.length);
                    raw.write(block, 0, take);
                    raw.flush();
                    remaining -= take;
                }
            }
            case "cat" -> {
                byte[] buffer = new byte[4096];
                var raw = new java.io.FileOutputStream(java.io.FileDescriptor.out);
                int read;
                while ((read = System.in.read(buffer)) != -1) {
                    raw.write(buffer, 0, read);
                    raw.flush();
                }
            }
            case "lines" -> {
                int count = Integer.parseInt(args.get(0));
                long pause = millis(args.get(1));
                for (int n = 0; n < count; n++) {
                    OUT.print("line " + n + "\n");
                    Thread.sleep(pause);
                }
            }
            case "unicode" -> OUT.print("héllo · 世界 · 🚀\n");
            case "ansi" -> OUT.print("\u001b[31mred\u001b[0m\n");
            case "ignoreterm" -> {
                // The JVM cannot ignore SIGTERM without sun.misc.Signal, which is not a
                // supported API. Installing a shutdown hook that outlives the grace period
                // achieves the same observable behaviour: a polite stop does not end the
                // process, so Kryon must escalate to a kill.
                long millis = millis(args.get(0));
                java.lang.Runtime.getRuntime()
                        .addShutdownHook(
                                new Thread(
                                        () -> {
                                            try {
                                                Thread.sleep(millis);
                                            } catch (InterruptedException interrupted) {
                                                Thread.currentThread().interrupt();
                                            }
                                        }));
                Thread.sleep(millis);
            }
            default -> {
                ERR.print("helper: unknown verb '" + verb + "'\n");
                return 64;
            }
        }
        return 0;
    }

    private static long millis(String seconds) {
        return Math.round(Double.parseDouble(seconds) * 1000);
    }
}
