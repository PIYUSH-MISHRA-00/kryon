/// Kryon -- powerful terminal execution, everywhere.
///
/// Kryon runs operating-system commands and manages the processes behind them,
/// with one conceptual API implemented across Python, TypeScript, Dart, Java
/// and Kotlin. This is the Dart SDK.
///
/// Two operations:
///
/// ```dart
/// import 'dart:convert';
/// import 'package:kryon/kryon.dart';
///
/// final runtime = Runtime(const ExecutionOptions(encoding: utf8));
///
/// // Run it and tell me what happened.
/// final result = await runtime.execute('git', ['status', '--porcelain']);
/// print('${result.stdoutText} ${result.exitCode} ${result.ok}');
///
/// // Start it and let me talk to it.
/// final proc = await runtime.spawn('dart', ['run', 'worker.dart']);
/// try {
///   proc.write('job-1\n');
///   await proc.closeStdin();
///   await for (final chunk in proc.output) {
///     stdout.add(chunk.data);
///   }
/// } finally {
///   await proc.close();
/// }
/// ```
///
/// Two things worth knowing before using this in anger:
///
/// * **Arguments are not interpreted.** `execute('echo', [r'$HOME'])` prints
///   `$HOME`. Shell semantics require the separately named [Runtime.executeShell],
///   because a `shell: true` flag is too easy to set by accident.
/// * **Kryon is not a sandbox.** Its timeouts and output caps manage resources;
///   they do not contain a hostile program. See `docs/security/threat-model.md`.
///
/// On iOS, spawning arbitrary child processes is not permitted by the platform.
/// That is not a missing feature -- it is a platform rule, and the correct
/// architecture there is a remote transport to a server that does the executing.
library;

export 'src/errors.dart'
    show
        CommandNotFoundException,
        InvalidArgumentsException,
        KryonException,
        PermissionDeniedException,
        ProcessCancelledException,
        ProcessFailedException,
        ProcessStartFailedException,
        ProcessTimeoutException,
        ResourceLimitExceededException,
        ResultException,
        UnsupportedPlatformException;

export 'src/model.dart'
    show
        ExecutionOptions,
        ExecutionResult,
        OutputChunk,
        OutputStream,
        TerminationReason;

export 'src/runtime.dart' show KryonProcess, Runtime;

/// The package version. Kept in step with `pubspec.yaml` by the release
/// workflow, and asserted in the test suite.
const String kryonVersion = '1.0.0';
