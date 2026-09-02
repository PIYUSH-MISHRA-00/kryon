/**
 * Plumbing shared by `execute` and `spawn`.
 *
 * Nothing here is public API. It exists so the buffered and streaming paths cannot drift
 * apart on the details that matter -- how the environment is built, how a start failure is
 * classified, how a process is stopped.
 *
 * @packageDocumentation
 */

import type { ChildProcess } from "node:child_process";
import type { Readable } from "node:stream";

import {
  CommandNotFoundError,
  InvalidArgumentsError,
  PermissionDeniedError,
  ProcessStartFailedError,
} from "./errors.js";
import {
  type ExecutionOptions,
  type OutputChunk,
  type Stream,
  TerminationReason,
} from "./model.js";

export const WINDOWS = process.platform === "win32";

/** Default milliseconds between the polite stop and the forced kill. */
export const DEFAULT_KILL_GRACE = 5000;

/**
 * Bounded queue depth for streaming output. This is the backpressure knob: once the
 * consumer is this far behind, Kryon pauses the source streams, the OS pipe fills, and
 * the child blocks. Bounded memory beats a fast producer every time.
 */
export const QUEUE_DEPTH = 64;

/**
 * Preserved even when `clearEnv` is set. Windows binaries -- including the ones in
 * `System32` -- routinely fail to start without these.
 */
const WINDOWS_ESSENTIAL = ["SystemRoot", "SystemDrive"] as const;

/** Validate and coerce the command, or throw {@link InvalidArgumentsError}. */
export function normalise(executable: unknown, args: unknown): [string, string[]] {
  if (Array.isArray(executable)) {
    throw new InvalidArgumentsError(
      "executable must be a single program; pass its arguments as the second parameter: " +
        `execute(${JSON.stringify(executable[0])}, ${JSON.stringify(executable.slice(1))})`,
    );
  }
  if (typeof executable !== "string") {
    throw new InvalidArgumentsError(`executable must be a string, got ${describe(executable)}`);
  }
  if (executable.length === 0) {
    throw new InvalidArgumentsError("executable must not be empty");
  }

  const list = args ?? [];
  if (!Array.isArray(list)) {
    throw new InvalidArgumentsError(`args must be an array, got ${describe(list)}`);
  }

  return [
    executable,
    list.map((value, index) => {
      if (typeof value !== "string") {
        throw new InvalidArgumentsError(
          `argument ${index} must be a string, got ${describe(value)}; Kryon does not ` +
            "stringify arguments for you, because guessing how to render a value into a " +
            "command line is how injection bugs start",
        );
      }
      return value;
    }),
  ];
}

function describe(value: unknown): string {
  return value === null ? "null" : typeof value;
}

/** Reject a malformed request before anything is spawned. */
export function validate(options: ExecutionOptions): void {
  if (options.timeout !== undefined && options.timeout <= 0) {
    throw new InvalidArgumentsError(`timeout must be positive, got ${options.timeout}`);
  }
  if (options.maxOutputBytes !== undefined && options.maxOutputBytes <= 0) {
    throw new InvalidArgumentsError(
      `maxOutputBytes must be positive, got ${options.maxOutputBytes}`,
    );
  }
  if (options.killGrace !== undefined && options.killGrace < 0) {
    throw new InvalidArgumentsError(`killGrace must not be negative, got ${options.killGrace}`);
  }
}

/** Materialise the child environment, or `undefined` to inherit unchanged. */
export function buildEnv(options: ExecutionOptions): NodeJS.ProcessEnv | undefined {
  const overrides = options.env;
  if (!options.clearEnv && (!overrides || Object.keys(overrides).length === 0)) {
    return undefined;
  }

  let env: NodeJS.ProcessEnv;
  if (options.clearEnv) {
    env = {};
    if (WINDOWS) {
      for (const key of WINDOWS_ESSENTIAL) {
        if (process.env[key] !== undefined) env[key] = process.env[key];
      }
    }
  } else {
    env = { ...process.env };
  }

  for (const [key, value] of Object.entries(overrides ?? {})) {
    if (value === null || value === undefined) delete env[key];
    else env[key] = value;
  }
  return env;
}

/**
 * Reject a bad working directory up front.
 *
 * Without this, a missing `cwd` surfaces as the same `ENOENT` as a missing executable, and
 * the caller is told the wrong thing.
 */
export async function checkCwd(cwd: string | undefined): Promise<void> {
  if (cwd === undefined) return;
  const { stat } = await import("node:fs/promises");
  try {
    const info = await stat(cwd);
    if (!info.isDirectory()) {
      throw new ProcessStartFailedError(`working directory is not a directory: ${cwd}`);
    }
  } catch (cause) {
    if (cause instanceof ProcessStartFailedError) throw cause;
    throw new ProcessStartFailedError(`working directory does not exist: ${cwd}`, { cause });
  }
}

/** Turn a spawn failure into the right Kryon error. */
export function mapStartError(error: NodeJS.ErrnoException, executable: string): Error {
  if (error.code === "ENOENT") {
    return new CommandNotFoundError(`executable not found: ${JSON.stringify(executable)}`, {
      cause: error,
    });
  }
  if (error.code === "EACCES" || error.code === "EPERM") {
    return new PermissionDeniedError(
      `not permitted to execute ${JSON.stringify(executable)}: ${error.message}`,
      { cause: error },
    );
  }
  return new ProcessStartFailedError(
    `could not start ${JSON.stringify(executable)}: ${error.message}`,
    { cause: error },
  );
}

/**
 * Map a raw exit plus any Kryon intervention to the reported outcome.
 *
 * A signal name is POSIX's way of reporting how the process died. Windows never produces
 * one, which is why `signal` is always `null` there.
 */
export function classify(
  child: ChildProcess,
  reason: TerminationReason | null,
): { termination: TerminationReason; exitCode: number | null; signal: NodeJS.Signals | null } {
  const signal = child.signalCode;
  const natural = signal ? TerminationReason.SIGNALED : TerminationReason.EXITED;
  return {
    termination: reason ?? natural,
    exitCode: signal ? null : child.exitCode,
    signal,
  };
}

/**
 * Terminate politely, then kill. The single termination path in this SDK.
 *
 * On Windows both steps are `TerminateProcess`: there is no graceful stop, and the child
 * gets no chance to flush. That difference is real and is documented rather than papered
 * over.
 */
export async function stop(child: ChildProcess, killGrace: number): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return;

  child.kill("SIGTERM");

  const exited = await Promise.race([
    once(child, "exit").then(() => true),
    delay(killGrace).then(() => false),
  ]);
  if (exited) return;

  child.kill("SIGKILL");
  await Promise.race([once(child, "exit"), delay(killGrace)]);
}

/**
 * First-writer-wins holder for why Kryon intervened.
 *
 * A timeout, an output cap and a cancellation can race; whichever fires first is the
 * reason the caller is told about, and later ones do not overwrite it.
 */
export class Reason {
  #value: TerminationReason | null = null;

  /** Record `reason` if none is set yet. Returns whether this call won. */
  set(reason: TerminationReason): boolean {
    if (this.#value !== null) return false;
    this.#value = reason;
    return true;
  }

  get value(): TerminationReason | null {
    return this.#value;
  }
}

/** A byte sink that stops growing at a limit and remembers that it did. */
export class Sink {
  #parts: Buffer[] = [];
  #size = 0;
  truncated = false;

  constructor(private readonly limit?: number) {}

  /**
   * Append `data`. Returns `true` when the limit has just been exceeded.
   *
   * Data past the limit is dropped rather than counted: the point of a cap is not to hold
   * the bytes, so reporting an exact total would defeat it.
   */
  add(data: Buffer): boolean {
    if (this.limit === undefined) {
      this.#parts.push(data);
      this.#size += data.length;
      return false;
    }
    const room = this.limit - this.#size;
    if (room > 0) {
      this.#parts.push(data.subarray(0, room));
      this.#size += Math.min(room, data.length);
    }
    if (data.length > room) {
      this.truncated = true;
      return true;
    }
    return false;
  }

  value(): Buffer {
    return Buffer.concat(this.#parts);
  }
}

/**
 * Merges two readable streams into one bounded async iterable of tagged chunks.
 *
 * The bound is the whole point: stop consuming and Kryon pauses both sources, so the child
 * blocks rather than the heap growing. That is backpressure working, not a hang.
 */
export class Multiplexer implements AsyncIterable<OutputChunk> {
  #buffered: OutputChunk[] = [];
  #waiting: Array<(result: IteratorResult<OutputChunk>) => void> = [];
  #sources: Readable[] = [];
  #open = 0;
  #consumed = false;

  add(stream: Stream, source: Readable | null): void {
    if (!source) return;
    this.#open += 1;
    this.#sources.push(source);

    source.on("data", (data: Buffer) => this.#push({ stream, data }));
    source.once("end", () => this.#closeOne());
    source.once("error", () => this.#closeOne());
  }

  #push(chunk: OutputChunk): void {
    const waiter = this.#waiting.shift();
    if (waiter) {
      waiter({ value: chunk, done: false });
      return;
    }
    this.#buffered.push(chunk);
    if (this.#buffered.length >= QUEUE_DEPTH) {
      for (const source of this.#sources) source.pause();
    }
  }

  #closeOne(): void {
    this.#open -= 1;
    if (this.#open > 0) return;
    for (const waiter of this.#waiting.splice(0)) {
      waiter({ value: undefined, done: true });
    }
  }

  /** Discard everything still buffered and stop reading. Used during shutdown. */
  drain(): void {
    this.#buffered.length = 0;
    for (const source of this.#sources) source.resume();
  }

  async *[Symbol.asyncIterator](): AsyncIterator<OutputChunk> {
    if (this.#consumed) {
      throw new Error(
        "output is already being consumed; a process has one output stream, and two " +
          "readers would each get an arbitrary half of it",
      );
    }
    this.#consumed = true;

    for (;;) {
      if (this.#buffered.length > 0) {
        const chunk = this.#buffered.shift()!;
        if (this.#buffered.length < QUEUE_DEPTH) {
          for (const source of this.#sources) source.resume();
        }
        yield chunk;
        continue;
      }
      if (this.#open === 0) return;

      const next = await new Promise<IteratorResult<OutputChunk>>((resolve) => {
        this.#waiting.push(resolve);
      });
      if (next.done) return;
      yield next.value;
    }
  }
}

export function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    // Do not hold the event loop open for a grace period nobody is waiting on.
    timer.unref?.();
  });
}

export function once(emitter: ChildProcess, event: string): Promise<void> {
  return new Promise((resolve) => emitter.once(event, () => resolve()));
}
