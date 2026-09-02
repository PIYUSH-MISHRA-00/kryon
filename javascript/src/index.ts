/**
 * Kryon -- powerful terminal execution, everywhere.
 *
 * Kryon runs operating-system commands and manages the processes behind them, with one
 * conceptual API implemented across Python, TypeScript, Dart, Java and Kotlin. This is the
 * Node entry point.
 *
 * ```ts
 * import { Runtime } from "kryon";
 *
 * const runtime = new Runtime({ encoding: "utf8", timeout: 30_000 });
 *
 * // Run it and tell me what happened.
 * const result = await runtime.execute("git", ["status", "--porcelain"]);
 * console.log(result.stdout, result.exitCode, isOk(result));
 *
 * // Start it and let me talk to it.
 * const proc = await runtime.spawn("node", ["-e", "process.stdin.pipe(process.stdout)"]);
 * try {
 *   await proc.write("hello\n");
 *   proc.closeStdin();
 *   for await (const { data } of proc.output) console.log(data.toString());
 * } finally {
 *   await proc.close();
 * }
 * ```
 *
 * Two things worth knowing before using this in anger:
 *
 * - **Arguments are not interpreted.** `execute("echo", ["$HOME"])` prints `$HOME`. Shell
 *   semantics require the separately named `executeShell`, because a `shell: true` flag is
 *   too easy to set by accident.
 * - **Kryon is not a sandbox.** Its timeouts and output caps manage resources; they do not
 *   contain a hostile program. See `docs/security/threat-model.md`.
 *
 * For browsers, import `kryon/browser` -- it carries the types and errors without
 * `node:child_process`, because a browser cannot execute host commands at all.
 *
 * @packageDocumentation
 */

export {
  CommandNotFoundError,
  InvalidArgumentsError,
  KryonError,
  PermissionDeniedError,
  ProcessCancelledError,
  ProcessFailedError,
  ProcessStartFailedError,
  ProcessTimeoutError,
  ResourceLimitExceededError,
  ResultError,
  UnsupportedPlatformError,
} from "./errors.js";

export {
  check,
  type ExecutionOptions,
  type ExecutionResult,
  isOk,
  type OutputChunk,
  Stream,
  TerminationReason,
} from "./model.js";

export { KryonProcess, Runtime } from "./runtime.js";

/** The package version. Kept in step with `package.json` by the release workflow. */
export const VERSION = "1.0.0";
