/**
 * Runnable tour of the TypeScript SDK.
 *
 *     cd javascript && npm install && npm run build
 *     node ../examples/typescript/tour.mjs
 *
 * Invokes Node itself rather than `echo` or `ls`, so it behaves identically on Linux, macOS
 * and Windows and depends on nothing being installed.
 */

import { execPath } from "node:process";

import {
  CommandNotFoundError,
  ProcessCancelledError,
  ProcessFailedError,
  Runtime,
  Stream,
  isOk,
} from "../../javascript/dist/index.js";

const runtime = new Runtime({ encoding: "utf8", timeout: 30_000 });

// ---------------------------------------------------------------- the basics

const hello = await runtime.execute(execPath, ["-e", "console.log('Hello from Kryon')"]);

console.log(`stdout:      ${JSON.stringify(hello.stdout)}`);
console.log(`exit code:   ${hello.exitCode}`);
console.log(`ok:          ${isOk(hello)}`);
console.log(`duration:    ${hello.duration}ms`);
console.log(`termination: ${hello.termination}`);

// ------------------------------------------------- arguments are never parsed

// No shell sees this. It is one argument containing spaces, semicolons and a dollar sign,
// and the program receives it exactly as written.
const hostile = "$HOME && rm -rf / ; `whoami`";
const literal = await runtime.execute(execPath, ["-e", "console.log(process.argv[1])", hostile]);
console.log(`\npassed through literally: ${literal.stdout.trim() === hostile}`);

// ------------------------------------------------ a non-zero exit is a result

const failed = await runtime.execute(execPath, ["-e", "process.exit(3)"]);
console.log(`\nexit code ${failed.exitCode}, ok=${isOk(failed)} — returned, not thrown`);
console.log("grep exits 1 to mean 'no match'. Rejecting on that makes ordinary code wrong.");

// ------------------------------------------------------ stderr, and check

const script = "console.log('out'); console.error('boom'); process.exit(2)";
const both = await runtime.execute(execPath, ["-e", script]);
console.log(`\nstdout=${JSON.stringify(both.stdout)} stderr=${JSON.stringify(both.stderr)}`);

try {
  await runtime.execute(execPath, ["-e", script], { check: true });
} catch (error) {
  if (!(error instanceof ProcessFailedError)) throw error;
  // The error carries the result. An exception that discards the stderr explaining what
  // went wrong is a worse error than no error.
  console.log(`\ncheck rejected with ${error.name}, exitCode=${error.result.exitCode}`);
}

// ------------------------------------------- failing to start is always an error

try {
  await runtime.execute("kryon-definitely-not-installed");
} catch (error) {
  if (!(error instanceof CommandNotFoundError)) throw error;
  console.log(`\n${error.name}: ${error.message}`);
  console.log("Rejected with or without `check` — nothing ran, so there is no result.");
}

// ------------------------------------------------------------------- limits

const timedOut = await runtime.execute(
  execPath,
  ["-e", "setTimeout(() => {}, 60_000)"],
  { timeout: 1000 },
);
console.log(`\ntermination: ${timedOut.termination} after ${timedOut.duration}ms`);
console.log("The process is gone, not merely abandoned.");

const flood = await runtime.execute(
  execPath,
  ["-e", "const b = Buffer.alloc(65536, 0x78); while (true) process.stdout.write(b);"],
  { maxOutputBytes: 4096, encoding: undefined, timeout: 30_000 },
);
console.log(
  `\noutput cap: ${flood.termination}, kept ${flood.stdout.length} bytes, ` +
    `truncated=${flood.stdoutTruncated}`,
);

// ---------------------------------------------------------------- streaming

console.log("\nstreaming, with timestamps so you can see it is live:");
const worker = `
  let n = 0;
  const timer = setInterval(() => {
    console.log('step ' + n);
    if (n === 2) console.error('something looked odd');
    if (++n === 5) { clearInterval(timer); }
  }, 200);
`;

const started = Date.now();
const proc = await runtime.spawn(execPath, ["-e", worker]);
try {
  for await (const { stream, data } of proc.output) {
    const where = stream === Stream.STDERR ? "err" : "out";
    for (const line of data.toString().split("\n").filter(Boolean)) {
      console.log(`  +${String(Date.now() - started).padStart(4)}ms [${where}] ${line}`);
    }
  }
  const result = await proc.wait();
  console.log(`exit ${result.exitCode}`);
} finally {
  // Leaving without this would leak the child. `await using` does it for you.
  await proc.close();
}

// ------------------------------------------------------------- cancellation

const controller = new AbortController();
const cancelling = runtime.execute(execPath, ["-e", "setTimeout(() => {}, 60_000)"], {
  signal: controller.signal,
});
setTimeout(() => controller.abort(), 300);

try {
  await cancelling;
} catch (error) {
  if (!(error instanceof ProcessCancelledError)) throw error;
  console.log("\ncancelled — and the child was terminated before this rejection arrived.");
}

console.log(`
Kryon is not a sandbox: these limits keep your process healthy,
they do not contain a hostile one. See docs/security/threat-model.md.`);
