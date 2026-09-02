/**
 * Value types shared by the Node runtime and the browser build.
 *
 * These are the TypeScript spelling of the conceptual objects in `spec/execution.md`.
 * They hold no resources and perform no I/O, which is why they are safe to import into a
 * browser bundle where `node:child_process` does not exist.
 *
 * @packageDocumentation
 */

import {
  ProcessCancelledError,
  ProcessFailedError,
  ProcessTimeoutError,
  ResourceLimitExceededError,
} from "./errors.js";

/** Which pipe a chunk of output arrived on. */
export const Stream = {
  STDOUT: "stdout",
  STDERR: "stderr",
} as const;

export type Stream = (typeof Stream)[keyof typeof Stream];

/**
 * Why the process stopped.
 *
 * The three Kryon-initiated reasons take precedence over the operating system's own
 * account of the death. A process killed because it exceeded its timeout was, at the
 * kernel level, `SIGNALED` -- but `TIMEOUT` is the fact the caller needs in order to react
 * correctly, so that is what is reported.
 */
export const TerminationReason = {
  /** The process exited on its own. `exitCode` is present. */
  EXITED: "EXITED",
  /** The process was killed by a signal Kryon did not send. POSIX only. */
  SIGNALED: "SIGNALED",
  /** `timeout` elapsed and Kryon terminated the process. */
  TIMEOUT: "TIMEOUT",
  /** The caller cancelled and Kryon terminated the process. */
  CANCELLED: "CANCELLED",
  /** `maxOutputBytes` was exceeded and Kryon stopped the process. */
  OUTPUT_LIMIT: "OUTPUT_LIMIT",
} as const;

export type TerminationReason = (typeof TerminationReason)[keyof typeof TerminationReason];

/** One chunk of output, tagged with the pipe it arrived on. */
export interface OutputChunk {
  readonly stream: Stream;
  readonly data: Buffer;
}

/**
 * Options for a single execution.
 *
 * Every field is optional. A {@link Runtime} carries defaults and each call may override
 * them; `env` merges with the runtime's `env`, everything else is replaced.
 */
export interface ExecutionOptions {
  /**
   * Working directory. Inherited when unset. A path that is not a directory is an error,
   * never a silent fallback to the current directory.
   */
  cwd?: string;

  /**
   * Variables merged *over* the inherited environment. A `null` value removes the
   * variable. To control the environment strictly, combine with {@link clearEnv}.
   */
  env?: Readonly<Record<string, string | null>>;

  /**
   * Start from an empty environment instead of inheriting one. With `env`, this is an
   * allowlist. On Windows, `SystemRoot` and `SystemDrive` are still preserved, because
   * many binaries fail to start without them.
   */
  clearEnv?: boolean;

  /** Data written to the child's stdin, after which stdin is closed. */
  stdin?: string | Uint8Array;

  /**
   * Wall-clock limit in **milliseconds** -- the idiomatic unit in this ecosystem, where
   * the Python SDK uses seconds and the JVM SDKs use `Duration`. The semantics are
   * identical; only the spelling differs.
   *
   * On expiry the process is terminated politely, then killed after {@link killGrace}.
   * Output collected so far is kept.
   */
  timeout?: number;

  /**
   * Per-stream cap in bytes, counted before decoding. Exceeding it stops the process and
   * sets the matching `*Truncated` flag.
   */
  maxOutputBytes?: number;

  /**
   * When set, output is decoded with this encoding; when unset, output is a `Buffer`.
   *
   * Decoding is lossy by design: an output cap can cut a multi-byte character in half,
   * and throwing on that would turn a truncation into a crash.
   */
  encoding?: BufferEncoding;

  /** Throw instead of returning when the result is not successful. */
  check?: boolean;

  /** Milliseconds between the polite stop and the forced kill. Defaults to 5000. */
  killGrace?: number;

  /**
   * Native cancellation. Aborting terminates the child process before the promise
   * rejects -- Kryon never resolves or rejects while leaving a process running.
   */
  signal?: AbortSignal;
}

/** What happened when a process ran. Only exists for a process that actually started. */
export interface ExecutionResult {
  readonly executable: string;
  readonly args: readonly string[];
  /** Integer exit status, or `null` if the process did not exit normally. */
  readonly exitCode: number | null;
  /** The terminating signal where the platform reports one. Always `null` on Windows. */
  readonly signal: NodeJS.Signals | null;
  readonly stdout: Buffer | string;
  readonly stderr: Buffer | string;
  /** Wall-clock milliseconds from spawn to reap. */
  readonly duration: number;
  readonly termination: TerminationReason;
  readonly pid: number | null;
  readonly stdoutTruncated: boolean;
  readonly stderrTruncated: boolean;
}

/** `true` only for a process that exited on its own with status `0`. */
export function isOk(result: ExecutionResult): boolean {
  return result.termination === TerminationReason.EXITED && result.exitCode === 0;
}

/**
 * Return `result` if successful, otherwise throw the matching error.
 *
 * This is what `check: true` calls. Useful on its own when you want to inspect a result
 * first and only then insist it succeeded.
 */
export function check(result: ExecutionResult): ExecutionResult {
  if (isOk(result)) return result;

  const detail = stderrExcerpt(result);
  const name = JSON.stringify(result.executable);

  switch (result.termination) {
    case TerminationReason.TIMEOUT:
      throw new ProcessTimeoutError(`${name} timed out${detail}`, result);
    case TerminationReason.CANCELLED:
      throw new ProcessCancelledError(`${name} was cancelled${detail}`, result);
    case TerminationReason.OUTPUT_LIMIT:
      throw new ResourceLimitExceededError(`${name} exceeded its output limit${detail}`, result);
    case TerminationReason.SIGNALED:
      throw new ProcessFailedError(`${name} was killed by ${result.signal}${detail}`, result);
    default:
      throw new ProcessFailedError(`${name} exited with code ${result.exitCode}${detail}`, result);
  }
}

/**
 * A short stderr excerpt for the error message.
 *
 * Deliberately capped and deliberately stderr-only: environments and stdin routinely hold
 * credentials, and an error message is the most-pasted string a library produces.
 */
function stderrExcerpt(result: ExecutionResult): string {
  const text = (
    typeof result.stderr === "string" ? result.stderr : result.stderr.toString("utf8")
  ).trim();
  if (!text) return "";
  return `\nstderr: ${text.length > 500 ? `${text.slice(0, 500)}...` : text}`;
}
