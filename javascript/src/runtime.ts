/**
 * The Node runtime.
 *
 * Two operations, deliberately separate:
 *
 * - {@link Runtime.execute} runs a program to completion and hands back everything it
 *   produced. Use it when you want the answer.
 * - {@link Runtime.spawn} starts a program and hands back a {@link KryonProcess} you can
 *   write to, read from and signal while it runs. Use it when you want a conversation.
 *
 * This module imports `node:child_process` and therefore cannot run in a browser. The
 * browser entry point (`kryon/browser`) exports the types and errors without it.
 *
 * @packageDocumentation
 */

import { type ChildProcess, spawn as nodeSpawn } from "node:child_process";

import * as core from "./core.js";
import { DEFAULT_KILL_GRACE, Multiplexer, Reason, Sink } from "./core.js";
import {
  InvalidArgumentsError,
  ProcessCancelledError,
  ProcessTimeoutError,
  UnsupportedPlatformError,
} from "./errors.js";
import {
  check as checkResult,
  type ExecutionOptions,
  type ExecutionResult,
  type OutputChunk,
  Stream,
  TerminationReason,
} from "./model.js";

/**
 * An execution context holding default options.
 *
 * A `Runtime` holds configuration, not state. It is safe to share, and creating one is
 * cheap enough that you can also just make a new one:
 *
 * ```ts
 * const runtime = new Runtime({ timeout: 30_000, encoding: "utf8", cwd: "/srv/app" });
 * const result = await runtime.execute("git", ["status", "--porcelain"]);
 * ```
 *
 * Any default can be overridden per call. `env` merges with the runtime's `env`; every
 * other option is replaced.
 */
export class Runtime {
  readonly defaults: Readonly<ExecutionOptions>;

  constructor(defaults: ExecutionOptions = {}) {
    core.validate(defaults);
    this.defaults = Object.freeze({ ...defaults });
  }

  #merge(overrides: ExecutionOptions): ExecutionOptions {
    const merged: ExecutionOptions = { ...this.defaults, ...overrides };
    if (this.defaults.env && overrides.env) {
      merged.env = { ...this.defaults.env, ...overrides.env };
    }
    core.validate(merged);
    return merged;
  }

  /**
   * Run `executable` with `args` and return what happened.
   *
   * Arguments are passed to the operating system as a vector. Nothing in them is
   * interpreted -- `execute("echo", ["$HOME && rm -rf /"])` prints that text literally.
   * For shell semantics you must ask for them by name; see {@link executeShell}.
   *
   * A process that could not be started rejects. A process that started and then failed
   * resolves, because a non-zero exit is information: `grep` exits `1` to mean "no match".
   * Pass `check: true` to reject on those too.
   *
   * @throws {CommandNotFoundError} the executable could not be resolved
   * @throws {PermissionDeniedError} the executable could not be run
   * @throws {ProcessStartFailedError} the process could not be created
   * @throws {InvalidArgumentsError} the request was malformed
   */
  async execute(
    executable: string,
    args: readonly string[] = [],
    options: ExecutionOptions = {},
  ): Promise<ExecutionResult> {
    return this.#run(executable, args, options, false);
  }

  /**
   * Run `commandLine` through the system shell.
   *
   * **The shell interprets quoting, globbing, variable expansion, pipes and command
   * chaining. Building this string from untrusted input is a command-injection
   * vulnerability.** If you are interpolating a value, you almost certainly want
   * {@link execute} with an argument array instead.
   *
   * The shell is `/bin/sh -c` on POSIX and `%COMSPEC% /d /s /c` on Windows.
   *
   * This method exists as a separate name on purpose. A `shell: true` flag sitting among
   * a dozen options is easy to set by accident and easy to miss in review; a differently
   * named method is not.
   */
  async executeShell(
    commandLine: string,
    options: ExecutionOptions = {},
  ): Promise<ExecutionResult> {
    if (typeof commandLine !== "string") {
      throw new InvalidArgumentsError(`commandLine must be a string, got ${typeof commandLine}`);
    }
    return this.#run(commandLine, [], options, true);
  }

  async #run(
    executable: string,
    args: readonly string[],
    overrides: ExecutionOptions,
    shell: boolean,
  ): Promise<ExecutionResult> {
    const options = this.#merge(overrides);
    const [exe, argv] = shell ? [executable, []] : core.normalise(executable, args);
    await core.checkCwd(options.cwd);

    const started = Date.now();
    const child = await startChild(exe, argv, options, shell);

    const reason = new Reason();
    const out = new Sink(options.maxOutputBytes);
    const err = new Sink(options.maxOutputBytes);
    const killGrace = options.killGrace ?? DEFAULT_KILL_GRACE;

    const intervene = (why: TerminationReason) => {
      if (reason.set(why)) void core.stop(child, killGrace);
    };

    child.stdout?.on("data", (data: Buffer) => {
      if (out.add(data)) intervene(TerminationReason.OUTPUT_LIMIT);
    });
    child.stderr?.on("data", (data: Buffer) => {
      if (err.add(data)) intervene(TerminationReason.OUTPUT_LIMIT);
    });

    writeStdin(child, options);

    const timer =
      options.timeout === undefined
        ? undefined
        : setTimeout(() => intervene(TerminationReason.TIMEOUT), options.timeout);

    const onAbort = () => intervene(TerminationReason.CANCELLED);
    options.signal?.addEventListener("abort", onAbort, { once: true });
    // A signal that was already aborted never fires the event, so the listener alone
    // would silently miss it and run the process to completion.
    if (options.signal?.aborted) onAbort();

    try {
      await core.once(child, "close");
    } finally {
      if (timer) clearTimeout(timer);
      options.signal?.removeEventListener("abort", onAbort);
    }

    const { termination, exitCode, signal } = core.classify(child, reason.value);
    const result: ExecutionResult = {
      executable: exe,
      args: argv,
      exitCode,
      signal,
      stdout: decode(out.value(), options.encoding),
      stderr: decode(err.value(), options.encoding),
      duration: Date.now() - started,
      termination,
      pid: child.pid ?? null,
      stdoutTruncated: out.truncated,
      stderrTruncated: err.truncated,
    };

    // A cancellation is the caller's own doing, so it rejects regardless of `check` --
    // resolving would hand back a result for work the caller already abandoned.
    if (termination === TerminationReason.CANCELLED) {
      throw new ProcessCancelledError(`${JSON.stringify(exe)} was cancelled`, result);
    }
    return options.check ? checkResult(result) : result;
  }

  /**
   * Start `executable` and return a {@link KryonProcess} to interact with.
   *
   * Resolves as soon as the process has started. Use `await using` (or a `try`/`finally`
   * with `close()`) so the process cannot outlive the block:
   *
   * ```ts
   * const proc = await runtime.spawn("node", ["worker.js"]);
   * try {
   *   await proc.write("job-1\n");
   *   for await (const { stream, data } of proc.output) console.log(data.toString());
   * } finally {
   *   await proc.close();
   * }
   * ```
   */
  async spawn(
    executable: string,
    args: readonly string[] = [],
    options: ExecutionOptions = {},
  ): Promise<KryonProcess> {
    const merged = this.#merge(options);
    const [exe, argv] = core.normalise(executable, args);
    await core.checkCwd(merged.cwd);
    const child = await startChild(exe, argv, merged, false);
    return new KryonProcess(child, exe, argv, merged);
  }
}

/**
 * A running process you can talk to.
 *
 * Output arrives through {@link output} as `{ stream, data }` chunks in the order Kryon
 * observed them. Chunk boundaries mean nothing -- they reflect how the operating system
 * delivered the data, not lines or records.
 *
 * Construct via {@link Runtime.spawn}, not directly.
 */
export class KryonProcess {
  readonly #child: ChildProcess;
  readonly #executable: string;
  readonly #args: readonly string[];
  readonly #options: ExecutionOptions;
  readonly #reason = new Reason();
  readonly #mux = new Multiplexer();
  readonly #started = Date.now();
  #bytesSeen = 0;
  #closed = false;
  #timer?: NodeJS.Timeout;

  /** @internal */
  constructor(
    child: ChildProcess,
    executable: string,
    args: readonly string[],
    options: ExecutionOptions,
  ) {
    this.#child = child;
    this.#executable = executable;
    this.#args = args;
    this.#options = options;

    const limit = options.maxOutputBytes;
    if (limit !== undefined) {
      for (const source of [child.stdout, child.stderr]) {
        source?.on("data", (data: Buffer) => {
          this.#bytesSeen += data.length;
          if (this.#bytesSeen > limit && this.#reason.set(TerminationReason.OUTPUT_LIMIT)) {
            void core.stop(child, this.#killGrace);
          }
        });
      }
    }

    this.#mux.add(Stream.STDOUT, child.stdout);
    this.#mux.add(Stream.STDERR, child.stderr);

    if (options.timeout !== undefined) {
      this.#timer = setTimeout(() => {
        if (this.running && this.#reason.set(TerminationReason.TIMEOUT)) {
          void core.stop(child, this.#killGrace);
        }
      }, options.timeout);
      this.#timer.unref?.();
    }

    const onAbort = () => {
      if (this.#reason.set(TerminationReason.CANCELLED)) void core.stop(child, this.#killGrace);
    };
    options.signal?.addEventListener("abort", onAbort, { once: true });
    // An already-aborted signal never fires the event; see Runtime#run.
    if (options.signal?.aborted) onAbort();
  }

  get #killGrace(): number {
    return this.#options.killGrace ?? DEFAULT_KILL_GRACE;
  }

  /** The operating-system process id. */
  get pid(): number | null {
    return this.#child.pid ?? null;
  }

  /** Whether the process is still alive. */
  get running(): boolean {
    return this.#child.exitCode === null && this.#child.signalCode === null;
  }

  /** The exit status once the process has been reaped, otherwise `null`. */
  get exitCode(): number | null {
    return this.#child.exitCode;
  }

  /**
   * Iterate `{ stream, data }` chunks until both pipes reach end-of-input.
   *
   * There is one consumer. Iterating twice throws, because the second iterator would
   * silently steal chunks from the first.
   */
  get output(): AsyncIterable<OutputChunk> {
    return this.#mux;
  }

  /**
   * Write to the child's stdin, respecting stream backpressure.
   *
   * Throws if stdin is already closed -- dropping input silently is the failure mode that
   * produces hangs nobody can reproduce.
   */
  async write(data: string | Uint8Array): Promise<void> {
    const stdin = this.#child.stdin;
    if (!stdin || stdin.destroyed || !stdin.writable) {
      throw new Error(`stdin of pid ${this.pid} is closed`);
    }
    await new Promise<void>((resolve, reject) => {
      stdin.write(data, (error) => {
        // The child stopping reading is its business; its exit code is the story.
        if (error && (error as NodeJS.ErrnoException).code !== "EPIPE") reject(error);
        else resolve();
      });
    });
  }

  /** Close stdin, signalling end-of-input to the child. */
  closeStdin(): void {
    this.#child.stdin?.end();
  }

  /**
   * Send a specific signal to the process.
   *
   * @throws {UnsupportedPlatformError} on Windows, which has no signals to send. Use
   * {@link terminate} there, and know that it does not give the child a chance to clean up.
   */
  signal(name: NodeJS.Signals): void {
    if (core.WINDOWS) {
      throw new UnsupportedPlatformError(
        "Windows has no signals; use terminate(), which kills the process outright " +
          "without letting it clean up",
      );
    }
    this.#child.kill(name);
  }

  /**
   * Request a polite stop: `SIGTERM` on POSIX, `TerminateProcess` on Windows.
   *
   * On Windows this is identical to {@link kill}. There is no graceful stop.
   */
  terminate(): void {
    this.#child.kill("SIGTERM");
  }

  /** Force a stop: `SIGKILL` on POSIX, `TerminateProcess` on Windows. */
  kill(): void {
    this.#child.kill("SIGKILL");
  }

  /**
   * Wait for exit and return the outcome.
   *
   * `stdout` and `stderr` on the result are empty: the output was streamed to you through
   * {@link output} and is deliberately not buffered a second time.
   *
   * @throws {ProcessTimeoutError} `timeoutMs` elapsed. The process is left running -- this
   * is a wait, not a stop. Call {@link terminate} or {@link close} for that.
   */
  async wait(timeoutMs?: number): Promise<ExecutionResult> {
    if (this.running) {
      const exited = core.once(this.#child, "close").then(() => true);
      const finished =
        timeoutMs === undefined
          ? await exited
          : await Promise.race([exited, core.delay(timeoutMs).then(() => false)]);
      if (!finished) {
        throw new ProcessTimeoutError(
          `${JSON.stringify(this.#executable)} (pid ${this.pid}) still running after ${timeoutMs}ms`,
        );
      }
    }
    if (this.#timer) clearTimeout(this.#timer);

    const { termination, exitCode, signal } = core.classify(this.#child, this.#reason.value);
    const empty = this.#options.encoding ? "" : Buffer.alloc(0);
    return {
      executable: this.#executable,
      args: this.#args,
      exitCode,
      signal,
      stdout: empty,
      stderr: empty,
      duration: Date.now() - this.#started,
      termination,
      pid: this.#child.pid ?? null,
      stdoutTruncated: false,
      stderrTruncated: false,
    };
  }

  /**
   * Terminate the process if it is still running and release every resource.
   *
   * Idempotent. This is what leaving an `await using` block does.
   */
  async close(): Promise<void> {
    if (this.#closed) return;
    this.#closed = true;
    if (this.#timer) clearTimeout(this.#timer);
    this.closeStdin();

    if (this.running) {
      this.#reason.set(TerminationReason.CANCELLED);
      await core.stop(this.#child, this.#killGrace);
    }
    // A paused source never emits `end`, so an unconsumed process would keep its pipes
    // open forever. Draining releases them.
    this.#mux.drain();
    this.#child.stdout?.destroy();
    this.#child.stderr?.destroy();
  }

  /** Support for `await using`, so the process cannot outlive its scope. */
  async [Symbol.asyncDispose](): Promise<void> {
    await this.close();
  }
}

async function startChild(
  executable: string,
  args: readonly string[],
  options: ExecutionOptions,
  shell: boolean,
): Promise<ChildProcess> {
  const child = nodeSpawn(executable, [...args], {
    cwd: options.cwd,
    env: core.buildEnv(options),
    shell,
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });

  // Node reports spawn failures asynchronously, so "did it start?" is a race between the
  // `spawn` and `error` events rather than a thrown exception.
  await new Promise<void>((resolve, reject) => {
    child.once("spawn", resolve);
    child.once("error", (error) => reject(core.mapStartError(error, executable)));
  });
  return child;
}

function writeStdin(child: ChildProcess, options: ExecutionOptions): void {
  const stdin = child.stdin;
  if (!stdin) return;
  stdin.on("error", () => {
    /* the child exited before reading its input; its exit code is the story */
  });
  if (options.stdin !== undefined) stdin.write(options.stdin);
  stdin.end();
}

function decode(data: Buffer, encoding: BufferEncoding | undefined): Buffer | string {
  return encoding ? data.toString(encoding) : data;
}
