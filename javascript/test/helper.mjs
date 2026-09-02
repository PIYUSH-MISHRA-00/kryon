/**
 * The JavaScript implementation of the Kryon conformance helper.
 *
 * Implements the verb contract in `spec/conformance.md` §2. Every SDK ships one of these
 * in its own language so that the shared corpus in `tests/conformance/cases.json` means
 * the same thing everywhere -- `echo`, `sleep` and `/bin/sh` do not, and half of them do
 * not exist on Windows.
 *
 * Deliberately dependency-free and deliberately unbuffered: a helper that buffers turns
 * streaming tests into false failures.
 */

const write = (target, text) =>
  new Promise((resolve) => target.write(Buffer.from(text, "utf8"), () => resolve()));

const sleep = (seconds) => new Promise((resolve) => setTimeout(resolve, seconds * 1000));

async function main(argv) {
  const [verb, ...args] = argv;

  if (!verb) {
    await write(process.stderr, "helper: no verb given\n");
    return 64;
  }

  switch (verb) {
    case "echo":
      await write(process.stdout, `${args.join(" ")}\n`);
      break;

    case "raw":
      await write(process.stdout, args[0] ?? "");
      break;

    case "err":
      await write(process.stderr, `${args.join(" ")}\n`);
      break;

    case "both":
      await write(process.stdout, `${args[0]}\n`);
      await write(process.stderr, `${args[1]}\n`);
      break;

    case "exit":
      return Number.parseInt(args[0], 10);

    case "env":
      await write(process.stdout, `${process.env[args[0]] ?? ""}\n`);
      break;

    case "dumpenv":
      for (const key of Object.keys(process.env).sort()) {
        await write(process.stdout, `${key}=${process.env[key]}\n`);
      }
      break;

    case "cwd":
      await write(process.stdout, `${process.cwd()}\n`);
      break;

    case "sleep":
      await sleep(Number.parseFloat(args[0]));
      break;

    case "spam": {
      let remaining = Number.parseInt(args[0], 10);
      const block = Buffer.alloc(Math.min(remaining, 65536), "x");
      while (remaining > 0) {
        const chunk = block.subarray(0, Math.min(remaining, block.length));
        // Respect backpressure, or a large spam floods this process's own memory before
        // it ever reaches the pipe.
        if (!process.stdout.write(chunk)) {
          await new Promise((resolve) => process.stdout.once("drain", resolve));
        }
        remaining -= chunk.length;
      }
      break;
    }

    case "cat":
      for await (const chunk of process.stdin) {
        await new Promise((resolve) => process.stdout.write(chunk, () => resolve()));
      }
      break;

    case "lines": {
      const count = Number.parseInt(args[0], 10);
      const pause = Number.parseFloat(args[1]);
      for (let n = 0; n < count; n += 1) {
        await write(process.stdout, `line ${n}\n`);
        await sleep(pause);
      }
      break;
    }

    case "unicode":
      await write(process.stdout, "héllo · 世界 · \u{1F680}\n");
      break;

    case "ansi":
      await write(process.stdout, "\u001b[31mred\u001b[0m\n");
      break;

    case "ignoreterm":
      // POSIX only; on Windows there is no signal to ignore and TerminateProcess wins.
      if (process.platform !== "win32") process.on("SIGTERM", () => {});
      await sleep(Number.parseFloat(args[0]));
      break;

    default:
      await write(process.stderr, `helper: unknown verb ${JSON.stringify(verb)}\n`);
      return 64;
  }
  return 0;
}

main(process.argv.slice(2)).then((code) => {
  process.exitCode = code;
});
