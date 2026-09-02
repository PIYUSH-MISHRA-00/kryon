package io.github.piyushmishra00.kryon.coroutines

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.TreeMap
import kotlin.system.exitProcess

/**
 * The Kotlin implementation of the Kryon conformance helper.
 *
 * Implements the verb contract in `spec/conformance.md` §2. Every SDK ships one of these in its
 * own language so that the shared corpus in `tests/conformance/cases.json` means the same thing
 * everywhere -- `echo`, `sleep` and `/bin/sh` do not, and half of them do not exist on Windows.
 *
 * Deliberately dependency-free and deliberately unbuffered: a helper that buffers turns streaming
 * tests into false failures.
 */
internal object ConformanceHelper {

    private val out = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    private val err = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)

    @JvmStatic
    fun main(argv: Array<String>) {
        exitProcess(run(argv))
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun run(argv: Array<String>): Int {
        if (argv.isEmpty()) {
            err.print("helper: no verb given\n")
            return 64
        }

        val verb = argv[0]
        val args = argv.drop(1)

        when (verb) {
            "echo" -> out.print(args.joinToString(" ") + "\n")
            "raw" -> out.print(args.firstOrNull() ?: "")
            "err" -> err.print(args.joinToString(" ") + "\n")
            "both" -> {
                out.print(args[0] + "\n")
                err.print(args[1] + "\n")
            }
            "exit" -> return args[0].toInt()
            "env" -> out.print((System.getenv(args[0]) ?: "") + "\n")
            "dumpenv" ->
                TreeMap(System.getenv()).forEach { (key, value) -> out.print("$key=$value\n") }
            "cwd" -> out.print(Path.of("").toAbsolutePath().toString() + "\n")
            "sleep" -> Thread.sleep(millis(args[0]))
            "spam" -> {
                var remaining = args[0].toLong()
                val block = ByteArray(minOf(remaining, 65536L).toInt()) { 'x'.code.toByte() }
                val raw = FileOutputStream(FileDescriptor.out)
                while (remaining > 0) {
                    val take = minOf(remaining, block.size.toLong()).toInt()
                    raw.write(block, 0, take)
                    raw.flush()
                    remaining -= take
                }
            }
            "cat" -> {
                val buffer = ByteArray(4096)
                val raw = FileOutputStream(FileDescriptor.out)
                while (true) {
                    val read = System.`in`.read(buffer)
                    if (read == -1) break
                    raw.write(buffer, 0, read)
                    raw.flush()
                }
            }
            "lines" -> {
                val count = args[0].toInt()
                val pause = millis(args[1])
                repeat(count) { n ->
                    out.print("line $n\n")
                    Thread.sleep(pause)
                }
            }
            "unicode" -> out.print("héllo · 世界 · 🚀\n")
            "ansi" -> out.print("\u001b[31mred\u001b[0m\n")
            "ignoreterm" -> {
                // The JVM cannot ignore SIGTERM without sun.misc.Signal, which is not a supported
                // API. A shutdown hook that outlives the grace period achieves the same observable
                // behaviour: a polite stop does not end the process, so Kryon must escalate.
                val duration = millis(args[0])
                java.lang.Runtime.getRuntime()
                    .addShutdownHook(Thread { Thread.sleep(duration) })
                Thread.sleep(duration)
            }
            else -> {
                err.print("helper: unknown verb '$verb'\n")
                return 64
            }
        }
        return 0
    }

    private fun millis(seconds: String): Long = Math.round(seconds.toDouble() * 1000)
}
