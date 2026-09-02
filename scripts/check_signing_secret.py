#!/usr/bin/env python3
"""Report the *shape* of the signing secrets, without revealing any of them.

Gradle's signing plugin says "Could not read PGP secret key" for both a malformed key and
a wrong passphrase. Those need completely different fixes, and burning a CI run to guess
between them is a waste. This separates them before the build starts.

Nothing derived from the key material is ever printed -- only lengths, and whether the
expected armor markers are present.

Reads SIGNING_KEY and SIGNING_PASSWORD from the environment. Exits non-zero with a
GitHub-annotated error when the key cannot possibly work.
"""

from __future__ import annotations

import base64
import binascii
import os
import sys


def main() -> int:
    raw = os.environ.get("SIGNING_KEY", "")
    password = os.environ.get("SIGNING_PASSWORD", "")

    print(f"SIGNING_KEY       present={bool(raw)}  length={len(raw)}")
    print(f"SIGNING_PASSWORD  present={bool(password)}  length={len(password)}")

    if not raw.strip():
        print("::error::SIGNING_KEY is empty or unset.")
        return 1

    if "BEGIN PGP" in raw:
        armored = raw
        print("form: raw ASCII armor")
    else:
        try:
            # The MIME decoder tolerates the newlines a clipboard round-trip can add.
            armored = base64.b64decode(raw.strip(), validate=False).decode("utf-8", "replace")
        except (binascii.Error, ValueError) as error:
            print(f"::error::SIGNING_KEY is neither ASCII armor nor valid base64: {error}")
            return 1
        print(f"form: base64  ->  decoded to {len(armored)} characters")

    head = armored.lstrip()[:40]
    is_private = head.startswith("-----BEGIN PGP PRIVATE")
    is_public = head.startswith("-----BEGIN PGP PUBLIC")
    has_end = "END PGP PRIVATE KEY BLOCK" in armored

    print(f"starts with a PRIVATE key block: {is_private}")
    print(f"has a closing END block:         {has_end}")
    print(f"line endings:                    {'CRLF' if chr(13) + chr(10) in armored else 'LF'}")

    if is_public:
        print("::error::SIGNING_KEY holds the PUBLIC key. Maven Central signs with the private")
        print("::error::half. Re-export with --export-secret-keys, not --export.")
        return 1

    if not is_private:
        print("::error::The decoded value is not a PGP private key block.")
        print("::error::Re-export it cleanly -- let gpg write the file rather than a shell")
        print("::error::redirect, which on Windows PowerShell produces UTF-16 with a BOM:")
        print("::error::  gpg --armor --output key.asc --export-secret-keys <FINGERPRINT>")
        return 1

    if not has_end:
        print("::error::The key block is truncated -- no END marker. The secret was probably")
        print("::error::cut off when it was pasted.")
        return 1

    if not password:
        print("::warning::SIGNING_PASSWORD is empty. That is only correct if the key has no")
        print("::warning::passphrase; otherwise signing will fail with 'Could not read PGP")
        print("::warning::secret key', which looks like a bad key but is not.")

    print("::notice::The signing key parses. If signing still fails, the passphrase is wrong.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
