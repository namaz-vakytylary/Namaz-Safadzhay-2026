#!/usr/bin/env python3
"""Restore the existing signing key from a CI secret, without logging its contents."""
import base64
import os
from pathlib import Path
import sys


def restore():
    required = ("NAMAZ_KEYSTORE_BASE64", "NAMAZ_KEYSTORE_PATH", "NAMAZ_STORE_PASSWORD",
                "NAMAZ_KEY_ALIAS", "NAMAZ_KEY_PASSWORD")
    if any(not os.environ.get(name, "").strip() for name in required):
        raise ValueError("Missing signing configuration")
    encoded = os.environ["NAMAZ_KEYSTORE_BASE64"]
    if len(encoded) > 65536:
        raise ValueError("Signing key exceeds size limit")
    payload = base64.b64decode(encoded, validate=True)
    if not payload:
        raise ValueError("Empty signing key")
    destination = Path(os.environ["NAMAZ_KEYSTORE_PATH"])
    if not destination.is_absolute():
        raise ValueError("Signing key path must be absolute")
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Exclusive creation also rejects a pre-existing file or symlink.
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(payload)


if __name__ == "__main__":
    try:
        restore()
    except Exception:
        # Never include exception input, secret values or key contents in logs.
        print("Unable to restore release signing key; check protected CI configuration.", file=sys.stderr)
        sys.exit(1)
