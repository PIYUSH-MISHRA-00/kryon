# Sandboxing

Kryon is not a sandbox. This document says what actually is one, so that "we use Kryon's
timeouts" never appears in a security review as though it answered the question.

## Why Kryon cannot be a sandbox

A sandbox constrains what a process may *do*. Kryon operates entirely before that point: it
decides what to start, with which arguments, in which directory, with which environment.
Once `execve` returns, Kryon holds a file descriptor and a process id. It has no ability to
intercept a syscall, deny a file open, or block a socket.

Building that ability into a library would mean reimplementing the kernel's job, badly, five
times. The right layer already exists.

## What Kryon's limits actually are

| Feature | Real purpose | Not this |
|---|---|---|
| `timeout` | Your caller does not hang forever | Preventing damage before the kill |
| `max_output_bytes` | Your process does not run out of memory | Stopping the child writing to disk or network |
| `clear_env` | The child does not inherit your secrets | Stopping it reading them from a file |
| `cwd` | The command runs where you meant | Restricting which files it can reach |
| `terminate()` | That process stops | Its children stopping |

Every one is about keeping *your* process correct and alive. None is about containing a
hostile one.

## What is actually a sandbox

Roughly in order of strength.

### Virtual machines

A separate kernel. The strongest boundary generally available; a kernel exploit in the guest
does not reach the host. Firecracker and Cloud Hypervisor make per-session microVMs practical
— boot times in tens of milliseconds. This is what commercial code-execution products use,
and the reason they do is that everything weaker has been escaped.

### Containers

A shared kernel with namespaces and cgroups. Good isolation of filesystem, network, PIDs and
resources; weaker than a VM because the kernel is shared and kernel bugs are the escape
route. Adequate for semi-trusted code, and much better than nothing.

Minimum configuration worth having:

```bash
docker run --rm \
  --user 65534:65534 \
  --read-only --tmpfs /tmp:size=64m \
  --network none \
  --memory 256m --cpus 0.5 --pids-limit 64 \
  --cap-drop ALL --security-opt no-new-privileges \
  your-image
```

Every flag there closes a real escape or resource-exhaustion path. Dropping any of them is a
decision, not a default.

### Syscall filtering

seccomp-bpf allows or denies individual syscalls. Powerful and precise, and easy to get
wrong: a filter that is too tight breaks ordinary programs mysteriously, and one that is too
loose achieves nothing. Best used as defence in depth *inside* a container, not instead of
one.

### Mandatory access control

SELinux and AppArmor constrain what a process may touch regardless of file permissions.
Excellent when your distribution already has good profiles; a substantial project when it
does not.

### Unprivileged users and resource limits

`setuid` to a dedicated account, `chroot`, `ulimit`/`RLIMIT_*`. The classical approach.
Cheap, universally available, better than nothing — and full of sharp edges: `chroot` is not
a security boundary on its own, and `RLIMIT_NPROC` is per-user, so two sessions as the same
user share it.

### WebAssembly

For code you can compile rather than run as a binary, a Wasm runtime is a strong, portable,
capability-based sandbox with no kernel involved. It does not help you run `git`, but it is
the right answer for "execute this user's function".

## The honest cost

Running untrusted code safely is expensive. A per-session microVM costs memory, boot latency
and orchestration. There is no configuration flag that makes it free, and there is no library
— Kryon included — that makes it unnecessary.

If that cost is unacceptable for your product, the correct conclusion is *do not run
untrusted code*, not *run it with fewer precautions*.

## Where Kryon fits

Kryon is the layer that starts the process correctly once the boundary exists:

```
Container or VM (the boundary)
  └── unprivileged user
        └── Kryon (argument vectors, environment allowlist, timeout, output cap)
              └── the process
```

Inside a good boundary, Kryon's features are still worth having: they stop your session
manager from leaking memory, hanging, or handing the child your API keys. They are the inner
layer of defence in depth. They are not the boundary.

## Common mistakes

**"We validate the command, so it is safe."** Validation of a shell string is not achievable
in general. Even a strict allowlist of *executables* leaves interpreters, and `find -exec`,
and `git -c core.pager=…`.

**"It runs as a non-root user, so it is contained."** A non-root user can still read
world-readable files, reach the network, exhaust CPU and RAM, and read other processes'
command lines — which frequently contain tokens.

**"The container is the sandbox."** Only if it is configured as one. A container running as
root, with the default capability set, host networking and a writable root filesystem is a
process with extra steps.

**"The timeout limits the damage."** The timeout limits the *duration*. `rm -rf` finishes
well inside one second.

## Further reading

- [Threat model](threat-model.md) — what Kryon does and does not defend against
- [Remote execution](remote-execution.md) — the required layers for a browser-facing terminal
- [Command execution](command-execution.md) — arguments versus shell strings
