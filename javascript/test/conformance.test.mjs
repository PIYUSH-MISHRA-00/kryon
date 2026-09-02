/**
 * Runs the shared conformance corpus against the TypeScript SDK.
 *
 * The corpus lives at `tests/conformance/cases.json` in the repository root and is
 * language-neutral. This file is the JavaScript *runner*: it maps each case onto this
 * SDK's API and asserts the expectations. Every SDK writes one of these; the corpus itself
 * is never forked.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { mkdtempSync, realpathSync, rmSync } from "node:fs";
import { after, describe, test } from "node:test";

import {
  CommandNotFoundError,
  InvalidArgumentsError,
  KryonProcess,
  PermissionDeniedError,
  ProcessCancelledError,
  ProcessFailedError,
  ProcessStartFailedError,
  ProcessTimeoutError,
  ResourceLimitExceededError,
  Runtime,
  Stream,
} from "../dist/index.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const HELPER = path.join(here, "helper.mjs");
const CORPUS = path.join(here, "..", "..", "tests", "conformance", "cases.json");

const corpus = JSON.parse(readFileSync(CORPUS, "utf8"));
const CASES = corpus.cases;

const ERRORS = {
  CommandNotFound: CommandNotFoundError,
  PermissionDenied: PermissionDeniedError,
  ProcessStartFailed: ProcessStartFailedError,
  InvalidArguments: InvalidArgumentsError,
  ProcessFailed: ProcessFailedError,
  ProcessTimeout: ProcessTimeoutError,
  ProcessCancelled: ProcessCancelledError,
  ResourceLimitExceeded: ResourceLimitExceededError,
};

const WINDOWS = process.platform === "win32";
const scratch = realpathSync(mkdtempSync(path.join(tmpdir(), "kryon-conformance-")));
after(() => rmSync(scratch, { recursive: true, force: true }));

const applies = (c) => !c.platforms || c.platforms.includes(WINDOWS ? "windows" : "posix");

const substitute = (value) =>
  typeof value === "string" ? value.replaceAll("${TMPDIR}", scratch) : value;

/**
 * Translate corpus options into this SDK's spelling.
 *
 * The corpus stores durations in seconds; this ecosystem's idiom is milliseconds. The
 * semantics are identical, and each SDK converts in its own runner -- that is exactly the
 * kind of difference `spec/conformance.md` expects a runner to absorb.
 */
function toOptions(kase) {
  const source = kase.options ?? {};
  const options = {};

  if (source.cwd !== undefined) options.cwd = substitute(source.cwd);
  if (source.env !== undefined) options.env = source.env;
  if (source.clear_env !== undefined) options.clearEnv = source.clear_env;
  if (source.timeout !== undefined) options.timeout = source.timeout * 1000;
  if (source.kill_grace !== undefined) options.killGrace = source.kill_grace * 1000;
  if (source.max_output_bytes !== undefined) options.maxOutputBytes = source.max_output_bytes;
  if (source.check !== undefined) options.check = source.check;
  if (source.encoding !== undefined) options.encoding = source.encoding.replace("-", "");
  if (kase.stdin !== undefined) options.stdin = kase.stdin;

  return options;
}

function toCommand(kase) {
  const args = (kase.args ?? []).map(substitute);
  if (kase.shell_command) return [kase.shell_command[WINDOWS ? "windows" : "posix"], null];
  if (kase.executable !== undefined) return [kase.executable, args];
  return [process.execPath, [HELPER, ...args]];
}

const asText = (value) => (typeof value === "string" ? value : value.toString("utf8"));

function assertResult(kase, result) {
  const e = kase.expect;
  const id = kase.id;

  if ("exit_code" in e) assert.equal(result.exitCode, e.exit_code, id);
  if ("termination" in e) assert.equal(result.termination, e.termination, id);
  if ("ok" in e) {
    const ok = result.termination === "EXITED" && result.exitCode === 0;
    assert.equal(ok, e.ok, id);
  }
  if ("stdout" in e) assert.equal(asText(result.stdout), e.stdout, id);
  if ("stderr" in e) assert.equal(asText(result.stderr), e.stderr, id);
  if ("stdout_contains" in e) assert.ok(asText(result.stdout).includes(e.stdout_contains), id);
  if ("stderr_contains" in e) assert.ok(asText(result.stderr).includes(e.stderr_contains), id);
  if ("stdout_truncated" in e) assert.equal(result.stdoutTruncated, e.stdout_truncated, id);
  if ("stdout_bytes_at_most" in e) {
    assert.ok(result.stdout.length <= e.stdout_bytes_at_most, `${id}: ${result.stdout.length}`);
  }
  if ("duration_at_most" in e) {
    assert.ok(result.duration <= e.duration_at_most * 1000, `${id}: ${result.duration}ms`);
  }
  if ("duration_at_least" in e) {
    assert.ok(result.duration >= e.duration_at_least * 1000, `${id}: ${result.duration}ms`);
  }
  if ("signal_present" in e) assert.equal(result.signal !== null, e.signal_present, id);
  if (e.stdout_is_bytes) assert.ok(Buffer.isBuffer(result.stdout), id);
  if ("stdout_contains_bytes" in e) {
    const needle = Buffer.from(e.stdout_contains_bytes, "hex");
    const hay = Buffer.isBuffer(result.stdout) ? result.stdout : Buffer.from(result.stdout);
    assert.ok(hay.includes(needle), id);
  }
  if ("stdout_is_dir" in e) {
    assert.equal(realpathSync(asText(result.stdout).trim()), realpathSync(substitute(e.stdout_is_dir)), id);
  }
}

const EXECUTE_CASES = CASES.filter((c) => c.api === "execute" || c.api === "execute_shell");
const SPAWN_CASES = CASES.filter((c) => c.api === "spawn");

describe("conformance: execute", () => {
  for (const kase of EXECUTE_CASES) {
    test(kase.id, { skip: applies(kase) ? false : "not applicable to this platform" }, async () => {
      const saved = {};
      for (const [key, value] of Object.entries(kase.setup_env ?? {})) {
        saved[key] = process.env[key];
        process.env[key] = value;
      }

      try {
        const runtime = new Runtime();
        const [executable, args] = toCommand(kase);
        const options = toOptions(kase);
        const invoke = () =>
          args === null
            ? runtime.executeShell(executable, options)
            : runtime.execute(executable, args, options);

        if (kase.expect.raises) {
          await assert.rejects(invoke, ERRORS[kase.expect.raises], kase.id);
          return;
        }
        assertResult(kase, await invoke());
      } finally {
        for (const [key, value] of Object.entries(saved)) {
          if (value === undefined) delete process.env[key];
          else process.env[key] = value;
        }
      }
    });
  }
});

describe("conformance: spawn", () => {
  for (const kase of SPAWN_CASES) {
    test(kase.id, { skip: applies(kase) ? false : "not applicable to this platform" }, async () => {
      const runtime = new Runtime();
      const [executable, args] = toCommand(kase);
      const proc = await runtime.spawn(executable, args, toOptions(kase));
      const e = kase.expect;

      try {
        if (kase.scope_exit_only) {
          assert.ok(proc.running, kase.id);
          await proc.close();
          assert.equal(proc.running, e.running_after_scope, kase.id);
          return;
        }

        if (kase.terminate_after !== undefined) {
          await new Promise((r) => setTimeout(r, kase.terminate_after * 1000));
          proc.terminate();
          const result = await proc.wait(20_000);
          assert.equal(proc.running, e.running_after_terminate, kase.id);
          if ("duration_at_most" in e) {
            assert.ok(result.duration <= e.duration_at_most * 1000, kase.id);
          }
          return;
        }

        for (const chunk of kase.write ?? []) await proc.write(chunk);
        if (kase.close_stdin) proc.closeStdin();

        const chunks = [];
        for await (const chunk of proc.output) chunks.push(chunk);
        const result = await proc.wait(20_000);

        const text = Buffer.concat(chunks.map((c) => c.data)).toString("utf8");
        if ("streamed_chunks_at_least" in e) {
          assert.ok(chunks.length >= e.streamed_chunks_at_least, kase.id);
        }
        if ("stdout_contains" in e) assert.ok(text.includes(e.stdout_contains), kase.id);
        if ("stdout_contains_last" in e) assert.ok(text.includes(e.stdout_contains_last), kase.id);
        if ("exit_code" in e) assert.equal(result.exitCode, e.exit_code, kase.id);
        assert.ok(chunks.every((c) => c.stream === Stream.STDOUT || c.stream === Stream.STDERR));
      } finally {
        await proc.close();
      }
    });
  }
});

describe("corpus integrity", () => {
  test("every case has a runner", () => {
    const covered = new Set([...EXECUTE_CASES, ...SPAWN_CASES].map((c) => c.id));
    const all = CASES.map((c) => c.id);
    assert.deepEqual([...covered].sort(), [...all].sort(), "some corpus cases have no runner");
  });

  test("ids are unique", () => {
    const ids = CASES.map((c) => c.id);
    assert.equal(new Set(ids).size, ids.length, "duplicate case id in the corpus");
  });

  test("every case explains itself", () => {
    const missing = CASES.filter((c) => !c.why).map((c) => c.id);
    assert.deepEqual(missing, [], `cases missing a 'why': ${missing}`);
  });

  test("KryonProcess is the streaming type", () => {
    assert.equal(typeof KryonProcess, "function");
  });
});
