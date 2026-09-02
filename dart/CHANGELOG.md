# Changelog

All notable changes to the Dart SDK are documented here. The project as a whole
shares one version number and one changelog; see
[the root CHANGELOG](https://github.com/PIYUSH-MISHRA-00/kryon/blob/main/CHANGELOG.md).

## 1.0.0

First release of the Dart SDK.

- `Runtime.execute` and `Runtime.executeShell` -- argument-vector execution by
  default, with shell semantics behind a separate name.
- `Runtime.spawn` and `KryonProcess` -- streaming output with real backpressure,
  stdin, signals, and a single termination path.
- The full error taxonomy, each error carrying the result it came from.
- Timeouts, output caps, environment allowlisting and working directories.
- Passes the shared cross-language conformance corpus.
- Zero runtime dependencies.

PTY, terminal emulation and remote transports are specified but not implemented.
