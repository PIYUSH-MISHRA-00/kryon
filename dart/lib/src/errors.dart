/// Kryon's error taxonomy.
///
/// The organising rule, from `spec/errors.md`:
///
/// > Failing to start is an error. Failing while running is a result.
///
/// A command that could not be found never ran, so there is no result to return
/// and [CommandNotFoundException] is thrown. A command that ran and exited `1`
/// did run -- `grep` exits `1` to mean "no match" -- so that is reported in
/// [ExecutionResult], not thrown. Callers who want the strict style pass
/// `check: true`, which turns unsuccessful results into the errors below.
library;

import 'model.dart';

/// Base class for everything Kryon throws.
///
/// Catch this to catch Kryon and nothing else.
abstract class KryonException implements Exception {
  const KryonException(this.message);

  final String message;

  @override
  String toString() => '$runtimeType: $message';
}

/// The request was malformed before any process was created.
///
/// An empty executable, a negative timeout, a null argument -- the things that
/// are cheaper to reject than to clean up after.
class InvalidArgumentsException extends KryonException {
  const InvalidArgumentsException(super.message);
}

/// The executable could not be resolved on `PATH` or at the given path.
class CommandNotFoundException extends KryonException {
  const CommandNotFoundException(super.message);
}

/// The executable exists but could not be executed, or `cwd` could not be
/// entered.
class PermissionDeniedException extends KryonException {
  const PermissionDeniedException(super.message);
}

/// The process could not be created, for a reason other than the two above.
///
/// A missing working directory, a resource limit, a platform refusal.
class ProcessStartFailedException extends KryonException {
  const ProcessStartFailedException(super.message);
}

/// Base for errors that describe a process which really ran.
///
/// Every one of these carries the [ExecutionResult] it came from. An error that
/// discards the stderr explaining what went wrong is a worse error than no
/// error at all.
abstract class ResultException extends KryonException {
  const ResultException(super.message, [this.result]);

  final ExecutionResult? result;
}

/// The process exited with a non-zero status, and `check` was set.
class ProcessFailedException extends ResultException {
  const ProcessFailedException(super.message, [super.result]);
}

/// The timeout elapsed and Kryon terminated the process.
///
/// [result] holds the output collected before termination; it is not discarded.
class ProcessTimeoutException extends ResultException {
  const ProcessTimeoutException(super.message, [super.result]);
}

/// The caller cancelled the operation and Kryon terminated the process.
class ProcessCancelledException extends ResultException {
  const ProcessCancelledException(super.message, [super.result]);
}

/// An output limit was exceeded and Kryon stopped the process.
///
/// This is a memory-management mechanism, not a security boundary. See
/// `docs/security/threat-model.md`.
class ResourceLimitExceededException extends ResultException {
  const ResourceLimitExceededException(super.message, [super.result]);
}

/// The operation cannot exist on this platform.
///
/// Distinct from a transient failure: this means *never here*, not *not right
/// now*. Sending an arbitrary signal on Windows is the current example, and on
/// iOS no process execution is possible at all.
class UnsupportedPlatformException extends KryonException {
  const UnsupportedPlatformException(super.message);
}
