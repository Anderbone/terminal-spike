#!/usr/bin/env python3
import re
import sys


text = sys.stdin.read()
# Google-provided emulator components can print API keys even when the app uses none.
text = re.sub(
    r"(?<![0-9A-Za-z_-])AIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])",
    "<redacted-google-api-key>",
    text,
)
text = re.sub(r"-----BEGIN [^-]+PRIVATE KEY-----.*?-----END [^-]+PRIVATE KEY-----", "<redacted-private-key>", text, flags=re.DOTALL)
text = re.sub(
    r"(?i)(password|passphrase|secret|privatekeybase64)([ \t]*(?:[:=][ \t]*|[ \t]+))\S+",
    r"\1\2<redacted>",
    text,
)
text = re.sub(r"\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b", "<redacted-ip>", text)
text = re.sub(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b", "<redacted-email>", text)
sys.stdout.write(text)
