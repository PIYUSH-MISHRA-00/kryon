/**
 * The browser entry point.
 *
 * A browser cannot execute host operating-system commands. That is not a missing feature
 * of Kryon; it is what a browser is. Any product that appears to run shell commands from a
 * web page is talking to a server.
 *
 * So this module deliberately exports **no runtime**. It carries the value types and the
 * error taxonomy -- everything you need to model results arriving over a transport from a
 * backend that does the executing -- and nothing that imports `node:child_process`.
 *
 * That separation is enforced by the package's `exports` map rather than by discipline: a
 * bundler resolving `kryon` in a browser target gets this file, so Node built-ins can
 * never be pulled into a web bundle by accident.
 *
 * The correct architecture for a browser terminal is in
 * `docs/security/remote-execution.md`. Read it before building one -- the version that is
 * easy to deploy is the version that ends up in an incident report.
 *
 * @packageDocumentation
 */

import { UnsupportedPlatformError } from "./errors.js";

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

/**
 * Throws {@link UnsupportedPlatformError}, always.
 *
 * It exists so that code which imports `Runtime` and only later discovers it is running in
 * a browser fails with a sentence explaining why, rather than with
 * `Cannot read properties of undefined`.
 */
export class Runtime {
  constructor() {
    throw new UnsupportedPlatformError(
      "a browser cannot execute host operating-system commands. Kryon's browser build " +
        "provides types and errors only. Run commands on an authenticated backend and " +
        "carry the session over a transport -- see docs/security/remote-execution.md.",
    );
  }
}

/** The package version. */
export const VERSION = "1.0.0";
