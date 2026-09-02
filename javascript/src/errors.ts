/**
 * Kryon's error taxonomy.
 *
 * The organising rule, from `spec/errors.md`:
 *
 * > Failing to start is an error. Failing while running is a result.
 *
 * A command that could not be found never ran, so there is no result to return and
 * {@link CommandNotFoundError} is thrown. A command that ran and exited `1` did run --
 * `grep` exits `1` to mean "no match" -- so that is reported in `ExecutionResult`, not
 * thrown. Callers who want the strict style pass `check: true`, which turns unsuccessful
 * results into the errors below.
 *
 * @packageDocumentation
 */

import type { ExecutionResult } from "./model.js";

/**
 * Base class for everything Kryon throws.
 *
 * Catch this to catch Kryon and nothing else.
 */
export class KryonError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = new.target.name;
    // Without this, `instanceof` fails for subclasses when the package is compiled to a
    // target older than ES2015. Cheap insurance.
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

/**
 * The request was malformed before any process was created.
 *
 * An empty executable, a negative timeout, a non-string argument -- the things that are
 * cheaper to reject than to clean up after.
 */
export class InvalidArgumentsError extends KryonError {}

/** The executable could not be resolved on `PATH` or at the given path. */
export class CommandNotFoundError extends KryonError {}

/** The executable exists but could not be executed, or `cwd` could not be entered. */
export class PermissionDeniedError extends KryonError {}

/**
 * The process could not be created, for a reason other than the two above.
 *
 * A missing working directory, a resource limit, a platform refusal. The underlying error
 * is attached as `cause`.
 */
export class ProcessStartFailedError extends KryonError {}

/**
 * Base for errors that describe a process which really ran.
 *
 * Every one of these carries the {@link ExecutionResult} it came from. An error that
 * discards the stderr explaining what went wrong is a worse error than no error at all.
 */
export class ResultError extends KryonError {
  readonly result?: ExecutionResult;

  constructor(message: string, result?: ExecutionResult) {
    super(message);
    this.result = result;
  }
}

/** The process exited with a non-zero status, and `check` was set. */
export class ProcessFailedError extends ResultError {}

/**
 * `timeout` elapsed and Kryon terminated the process.
 *
 * `result` holds the output collected before termination; it is not discarded.
 */
export class ProcessTimeoutError extends ResultError {}

/** The caller cancelled the operation and Kryon terminated the process. */
export class ProcessCancelledError extends ResultError {}

/**
 * An output limit was exceeded and Kryon stopped the process.
 *
 * This is a memory-management mechanism, not a security boundary. See
 * `docs/security/threat-model.md`.
 */
export class ResourceLimitExceededError extends ResultError {}

/**
 * The operation cannot exist on this platform.
 *
 * Distinct from a transient failure: this means *never here*, not *not right now*.
 * `Process.signal()` on Windows and any execution at all in a browser are the current
 * examples.
 */
export class UnsupportedPlatformError extends KryonError {}
