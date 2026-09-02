/**
 * Unit tests for behaviour the shared conformance corpus does not reach.
 *
 * The corpus covers cross-language semantics. This file covers the TypeScript surface
 * itself: option merging, error mapping, the guards on KryonProcess, the browser build,
 * and the promise that nothing is left running when a caller walks away.
 */

import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, test } from "node:test";

import {
  CommandNotFoundError,
  InvalidArgumentsError,
  KryonError,
  ProcessCancelledError,
  ProcessFailedError,
  ProcessStartFailedError,
  ProcessTimeoutError,
  ResourceLimitExceededError,
  Runtime,
  Stream,
  TerminationReason,
  UnsupportedPlatformError,
  VERSION,
  check,
  isOk,
} from "../dist/index.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const HELPER = path.join(here, "helper.mjs");
const NODE = process.execPath;
const WINDOWS = process.platform === "win32";

const helper = (...args) => [HELPER, ...args];
const posixOnly = { skip: WINDOWS ? "POSIX-specific behaviour" : false };
const windowsOnly = { skip: WINDOWS ? false : "Windows-specific behaviour" };

const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

describe("options", () => {
  test("runtime defaults apply to calls", async () => {
    const runtime = new Runtime({ encoding: "utf8" });
    const result = await runtime.execute(NODE, helper("echo", "hi"));
    assert.equal(result.stdout, "hi\n");
  });

  test("a call overrides a runtime default", async () => {
    const runtime = new Runtime({ encoding: "utf8" });
    const result = await runtime.execute(NODE, helper("unicode"), { encoding: undefined });
    assert.ok(Buffer.isBuffer(result.stdout));
  });

  test("env merges rather than replaces", async () => {
    const runtime = new Runtime({ encoding: "utf8", env: { KRYON_A: "1" } });
    const result = await runtime.execute(NODE, helper("env", "KRYON_A"), {
      env: { KRYON_B: "2" },
    });
    assert.equal(result.stdout, "1\n", "a per-call env must not drop the runtime's env");
  });

  test("defaults are frozen", () => {
    const runtime = new Runtime({ timeout: 5000 });
    assert.throws(() => {
      runtime.defaults.timeout = 1;
    });
  });

  test("a non-positive timeout is rejected up front", () => {
    assert.throws(() => new Runtime({ timeout: 0 }), InvalidArgumentsError);
    assert.throws(() => new Runtime({ timeout: -1 }), InvalidArgumentsError);
  });

  test("a non-positive output limit is rejected up front", () => {
    assert.throws(() => new Runtime({ maxOutputBytes: 0 }), InvalidArgumentsError);
  });

  test("a negative kill grace is rejected up front", () => {
    assert.throws(() => new Runtime({ killGrace: -1 }), InvalidArgumentsError);
  });
});

describe("argument validation", () => {
  const runtime = new Runtime();

  test("passing an array as the executable says what to do instead", async () => {
    await assert.rejects(() => runtime.execute(["git", "status"]), (error) => {
      assert.ok(error instanceof InvalidArgumentsError);
      assert.match(error.message, /execute\("git", \["status"\]\)/);
      return true;
    });
  });

  test("a non-string argument is rejected with its index", async () => {
    await assert.rejects(() => runtime.execute(NODE, [HELPER, "echo", 42]), (error) => {
      assert.ok(error instanceof InvalidArgumentsError);
      assert.match(error.message, /argument 2/);
      return true;
    });
  });

  test("an empty executable is rejected", async () => {
    await assert.rejects(() => runtime.execute(""), InvalidArgumentsError);
  });

  test("executeShell requires a string", async () => {
    await assert.rejects(() => runtime.executeShell(["ls", "-l"]), InvalidArgumentsError);
  });
});

describe("results and errors", () => {
  const base = {
    executable: "prog",
    args: [],
    exitCode: 0,
    signal: null,
    stdout: Buffer.alloc(0),
    stderr: Buffer.alloc(0),
    duration: 10,
    termination: TerminationReason.EXITED,
    pid: 1,
    stdoutTruncated: false,
    stderrTruncated: false,
  };

  test("isOk requires both EXITED and zero", () => {
    assert.equal(isOk(base), true);
    assert.equal(isOk({ ...base, exitCode: 1 }), false);
    assert.equal(isOk({ ...base, termination: TerminationReason.TIMEOUT }), false);
  });

  test("check maps each termination to its own error", () => {
    const cases = [
      [TerminationReason.TIMEOUT, ProcessTimeoutError],
      [TerminationReason.CANCELLED, ProcessCancelledError],
      [TerminationReason.OUTPUT_LIMIT, ResourceLimitExceededError],
      [TerminationReason.SIGNALED, ProcessFailedError],
    ];
    for (const [termination, Expected] of cases) {
      assert.throws(() => check({ ...base, termination, exitCode: null }), Expected, termination);
    }
  });

  test("check returns the result on success", () => {
    assert.equal(check(base), base);
  });

  test("errors carry the result they came from", () => {
    assert.throws(
      () => check({ ...base, exitCode: 2, stderr: Buffer.from("the real reason\n") }),
      (error) => {
        assert.equal(error.result.exitCode, 2);
        assert.match(error.message, /the real reason/, "stderr belongs in the message");
        return true;
      },
    );
  });

  test("the error message excerpt is capped", () => {
    assert.throws(
      () => check({ ...base, exitCode: 1, stderr: Buffer.alloc(5000, 0x78) }),
      (error) => {
        assert.ok(error.message.length < 1000, "an error message is not a log file");
        return true;
      },
    );
  });

  test("every exported error descends from KryonError", () => {
    for (const Type of [
      CommandNotFoundError,
      InvalidArgumentsError,
      ProcessCancelledError,
      ProcessFailedError,
      ProcessStartFailedError,
      ProcessTimeoutError,
      ResourceLimitExceededError,
      UnsupportedPlatformError,
    ]) {
      assert.ok(Object.create(Type.prototype) instanceof KryonError, Type.name);
    }
  });

  test("errors keep a useful name", async () => {
    const runtime = new Runtime();
    await assert.rejects(() => runtime.execute("kryon-no-such-executable-xyzzy"), (error) => {
      assert.equal(error.name, "CommandNotFoundError");
      assert.ok(error instanceof CommandNotFoundError);
      return true;
    });
  });
});

describe("process", () => {
  const runtime = new Runtime();

  test("output can only be consumed once", async () => {
    const proc = await runtime.spawn(NODE, helper("echo", "x"));
    try {
      for await (const _ of proc.output) void _;
      await assert.rejects(async () => {
        for await (const _ of proc.output) void _;
      }, /already being consumed/);
    } finally {
      await proc.close();
    }
  });

  test("write after closeStdin throws", async () => {
    const proc = await runtime.spawn(NODE, helper("cat"));
    try {
      proc.closeStdin();
      await assert.rejects(() => proc.write("too late\n"), /closed/);
    } finally {
      await proc.close();
    }
  });

  test("close is idempotent", async () => {
    const proc = await runtime.spawn(NODE, helper("sleep", "30"));
    await proc.close();
    await proc.close();
    assert.equal(proc.running, false);
  });

  test("wait timeout leaves the process running", async () => {
    const proc = await runtime.spawn(NODE, helper("sleep", "30"));
    try {
      await assert.rejects(() => proc.wait(300), ProcessTimeoutError);
      assert.ok(proc.running, "wait() is a wait, not a stop");
    } finally {
      await proc.close();
    }
  });

  test("signal is unsupported on Windows", windowsOnly, async () => {
    const proc = await runtime.spawn(NODE, helper("sleep", "30"));
    try {
      assert.throws(() => proc.signal("SIGTERM"), UnsupportedPlatformError);
    } finally {
      await proc.close();
    }
  });

  test("signal delivers", posixOnly, async () => {
    const proc = await runtime.spawn(NODE, helper("sleep", "30"));
    try {
      proc.signal("SIGTERM");
      const result = await proc.wait(10_000);
      assert.equal(proc.running, false);
      assert.equal(result.signal, "SIGTERM");
    } finally {
      await proc.close();
    }
  });

  test("close leaves no orphan", posixOnly, async () => {
    const proc = await runtime.spawn(NODE, helper("sleep", "30"));
    const { pid } = proc;
    await proc.close();
    assert.equal(alive(pid), false);
  });

  test("an unconsumed process still closes promptly", posixOnly, async () => {
    const proc = await runtime.spawn(NODE, helper("spam", "50000000"));
    const { pid } = proc;
    const started = Date.now();
    await proc.close();
    assert.ok(Date.now() - started < 15_000, "close must not wait out the flood");
    assert.equal(alive(pid), false);
  });

  test("chunks are tagged with their stream", async () => {
    const proc = await runtime.spawn(NODE, helper("both", "to-out", "to-err"));
    try {
      const seen = new Set();
      for await (const { stream } of proc.output) seen.add(stream);
      assert.deepEqual([...seen].sort(), [Stream.STDERR, Stream.STDOUT].sort());
    } finally {
      await proc.close();
    }
  });
});

describe("cancellation", () => {
  test("aborting terminates the child", async () => {
    const runtime = new Runtime();
    const controller = new AbortController();
    const running = runtime.execute(NODE, helper("sleep", "30"), { signal: controller.signal });

    setTimeout(() => controller.abort(), 300);
    const started = Date.now();
    await assert.rejects(() => running, ProcessCancelledError);
    assert.ok(Date.now() - started < 15_000, "cancellation must not wait out the process");
  });

  test("an already-aborted signal stops it immediately", async () => {
    const runtime = new Runtime();
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(
      () => runtime.execute(NODE, helper("sleep", "30"), { signal: controller.signal }),
      ProcessCancelledError,
    );
  });
});

describe("browser build", () => {
  test("exports no working runtime", async () => {
    const browser = await import("../dist/browser.js");
    assert.throws(() => new browser.Runtime(), UnsupportedPlatformError);
  });

  test("exports the types and errors", async () => {
    const browser = await import("../dist/browser.js");
    assert.equal(typeof browser.isOk, "function");
    assert.equal(typeof browser.check, "function");
    assert.equal(browser.TerminationReason.TIMEOUT, "TIMEOUT");
    assert.ok(Object.create(browser.CommandNotFoundError.prototype) instanceof browser.KryonError);
  });

  test("imports no Node built-ins", async () => {
    const { readFileSync } = await import("node:fs");
    const source = readFileSync(path.join(here, "..", "dist", "browser.js"), "utf8");

    // Assert on emitted imports, not on the raw text: the module doc comment mentions
    // node:child_process precisely to explain why it is absent, so a substring search
    // would fail on the very sentence that documents the guarantee.
    const imports = source
      .split("\n")
      .filter((line) => /^\s*(?:import|export)\b/.test(line))
      .flatMap((line) => [...line.matchAll(/from\s+"([^"]+)"/g)].map((m) => m[1]));

    assert.deepEqual(
      imports.filter((spec) => spec.startsWith("node:")),
      [],
      "the browser build must stay Node-free",
    );
    assert.ok(imports.length > 0, "sanity check: the local imports should have been found");
  });
});

describe("package", () => {
  test("VERSION matches package.json", async () => {
    const { readFileSync } = await import("node:fs");
    const pkg = JSON.parse(readFileSync(path.join(here, "..", "package.json"), "utf8"));
    assert.equal(VERSION, pkg.version, "a version number that is a guess is worse than none");
  });
});
