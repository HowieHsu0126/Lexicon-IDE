"""Create an explicit, offline trust handoff for a personal IELTS HTTPS host.

The connection file contains a public leaf certificate and an independent
pairing token. It never contains a TLS private key or a DeepSeek API key. No
network request, trust-store change, listener, or credential discovery occurs.
Only an explicit CLI call or one of the public functions performs file I/O.
"""
from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys
from tempfile import TemporaryDirectory
from typing import Optional
from urllib.parse import urlsplit


SCHEMA = "lexicon.ielts.pairing.v1"
MAX_BUNDLE_BYTES = 16_384
MAX_CERTIFICATE_DER_BYTES = 8_192
DEFAULT_VALIDITY_DAYS = 90
_FIELDS = {"schema", "endpoint", "certificate_pem", "pairing_token"}
_TOKEN = re.compile(r"[A-Za-z0-9_-]{43,128}\Z")
_DNS_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")
_PEM = re.compile(
    r"\A[ \t\r\n]*-----BEGIN CERTIFICATE-----\r?\n"
    r"([A-Za-z0-9+/=\r\n]+)\r?\n-----END CERTIFICATE-----[ \t\r\n]*\Z"
)
_SIGNATURES = {"sha256WithRSAEncryption", "sha384WithRSAEncryption", "sha512WithRSAEncryption",
               "ecdsa-with-SHA256", "ecdsa-with-SHA384", "ecdsa-with-SHA512"}
_DATE = re.compile(r"([A-Z][a-z]{2})\s+(\d{1,2}) (\d{2}):(\d{2}):(\d{2}) (\d{4}) GMT\Z")
_MONTHS = {name: number for number, name in enumerate(
    ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"), 1)}


class PairingError(ValueError):
    """A fixed diagnostic code; never attach input, tokens, or subprocess output."""
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True, repr=False)
class ValidatedPairing:
    endpoint: str
    certificate_pem: str
    pairing_token: str
    certificate_sha256: str
    not_before: str
    expires_at: str

    def __repr__(self):
        return "<ValidatedPairing: private connection material>"

    def encoded(self) -> bytes:
        value = {"schema": SCHEMA, "endpoint": self.endpoint,
                 "certificate_pem": self.certificate_pem, "pairing_token": self.pairing_token}
        raw = (json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
        if len(raw) > MAX_BUNDLE_BYTES:
            raise PairingError("connection_file_too_large")
        return raw


def normalize_endpoint(value: str) -> str:
    """The same HTTPS-origin restriction used by the Android gateway."""
    try:
        if not isinstance(value, str) or not 1 <= len(value) <= 2048:
            raise ValueError()
        if any(c.isspace() or ord(c) < 32 or ord(c) > 126 for c in value):
            raise ValueError()
        parsed = urlsplit(value)
        if (parsed.scheme.lower() != "https" or not parsed.netloc or
                parsed.username is not None or parsed.password is not None or
                "@" in parsed.netloc or "%" in parsed.netloc or "\\" in parsed.netloc or
                "?" in value or "#" in value or parsed.path not in ("", "/")):
            raise ValueError()
        host = parsed.hostname
        if not host:
            raise ValueError()
        host = host.lower()
        port = parsed.port
        if port is not None and not 1 <= port <= 65535:
            raise ValueError()
        # Reject an empty explicit port, not merely a parsed None value.
        if parsed.netloc.endswith(":"):
            raise ValueError()
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            if ":" in host or len(host) > 253 or re.fullmatch(r"[0-9.]+", host):
                raise ValueError()
            labels = host[:-1].split(".") if host.endswith(".") else host.split(".")
            if not all(_DNS_LABEL.fullmatch(label) for label in labels):
                raise ValueError()
            authority = host
        else:
            authority = "[{}]".format(address.compressed) if address.version == 6 else str(address)
        if port not in (None, 443):
            authority += ":{}".format(port)
        return "https://{}/".format(authority)
    except (ValueError, TypeError, UnicodeError):
        raise PairingError("https_endpoint_invalid") from None


def _pairing_token(value: str) -> str:
    if not isinstance(value, str) or not _TOKEN.fullmatch(value):
        raise PairingError("pairing_token_invalid")
    try:
        decoded = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except ValueError:
        raise PairingError("pairing_token_invalid") from None
    if len(decoded) < 32:
        raise PairingError("pairing_token_invalid")
    return value


def _openssl_path(explicit: Optional[str] = None) -> str:
    value = explicit if explicit is not None else shutil.which("openssl")
    if not value or not Path(value).is_file() or not os.access(value, os.X_OK):
        raise PairingError("certificate_tool_unavailable")
    return str(Path(value).resolve())


def _run_openssl(executable: str, arguments, *, raw: Optional[bytes] = None, cwd: Optional[Path] = None):
    environment = os.environ.copy()
    environment["LC_ALL"] = "C"
    environment["TZ"] = "UTC"
    try:
        result = subprocess.run([executable, *arguments], input=raw, cwd=cwd,
                                capture_output=True, timeout=20, env=environment, umask=0o077)
    except (OSError, subprocess.TimeoutExpired):
        raise PairingError("certificate_tool_failed") from None
    if len(result.stdout) > 131_072 or len(result.stderr) > 131_072:
        raise PairingError("certificate_tool_failed")
    return result


def _certificate_der(value: str):
    if not isinstance(value, str) or len(value) > MAX_BUNDLE_BYTES:
        raise PairingError("certificate_invalid")
    matched = _PEM.fullmatch(value)
    if matched is None:
        raise PairingError("single_certificate_required")
    try:
        encoded = matched.group(1).replace("\r", "").replace("\n", "")
        der = base64.b64decode(encoded, validate=True)
    except (ValueError, UnicodeError):
        raise PairingError("certificate_invalid") from None
    if not 1 <= len(der) <= MAX_CERTIFICATE_DER_BYTES:
        raise PairingError("certificate_size_invalid")
    if base64.b64encode(der).decode("ascii") != encoded:
        raise PairingError("certificate_invalid")
    return der, ssl.DER_cert_to_PEM_cert(der)


def _date(value: str) -> datetime:
    match = _DATE.fullmatch(value)
    try:
        if match is None:
            raise ValueError()
        month, day, hour, minute, second, year = match.groups()
        return datetime(int(year), _MONTHS[month], int(day), int(hour), int(minute), int(second), tzinfo=timezone.utc)
    except (KeyError, TypeError, ValueError):
        raise PairingError("certificate_invalid") from None


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _metadata(value: bytes):
    try:
        text = value.decode("ascii", errors="strict")
    except UnicodeError:
        raise PairingError("certificate_invalid") from None
    dates, extensions = {}, {}
    current = None
    for line in text.splitlines():
        if line.startswith(("notBefore=", "notAfter=")):
            key, content = line.split("=", 1)
            if key in dates:
                raise PairingError("certificate_invalid")
            dates[key] = _date(content)
        elif line.startswith("X509v3 "):
            header = re.fullmatch(r"X509v3 ([A-Za-z ]+):(?: critical)?\s*", line)
            if header is None or header.group(1) in extensions:
                raise PairingError("certificate_invalid")
            current = header.group(1)
            extensions[current] = []
        elif line.strip():
            if current is None or not line[0].isspace():
                raise PairingError("certificate_invalid")
            extensions[current].append(line.strip())
    if set(dates) != {"notBefore", "notAfter"}:
        raise PairingError("certificate_invalid")
    return dates, {key: " ".join(lines) for key, lines in extensions.items()}


def _key_policy(value: bytes):
    try:
        text = value.decode("ascii", errors="strict")
    except UnicodeError:
        raise PairingError("certificate_invalid") from None
    if not re.search(r"^\s*Version: 3 \(0x2\)\s*$", text, re.MULTILINE):
        raise PairingError("certificate_not_server")
    signatures = re.findall(r"^\s*Signature Algorithm: ([^\r\n]+)$", text, re.MULTILINE)
    if len(signatures) != 2 or len(set(signatures)) != 1 or signatures[0] not in _SIGNATURES:
        raise PairingError("certificate_signature_unsupported")
    algorithms = re.findall(r"^\s*Public Key Algorithm: ([^\r\n]+)$", text, re.MULTILINE)
    strengths = re.findall(r"^\s*Public-Key: \((\d+) bit\)\s*$", text, re.MULTILINE)
    if len(algorithms) != 1 or len(strengths) != 1:
        raise PairingError("certificate_key_unsupported")
    size = int(strengths[0])
    if not ((algorithms[0] == "rsaEncryption" and size >= 2048) or
            (algorithms[0] == "id-ecPublicKey" and size >= 256)):
        raise PairingError("certificate_key_unsupported")


def _exact_san(host: str, names):
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return any(name.startswith("DNS:") and "*" not in name and name[4:].lower() == host.lower() for name in names)
    for name in names:
        if name.startswith("IP Address:"):
            try:
                if ipaddress.ip_address(name[len("IP Address:"):]) == address:
                    return True
            except ValueError:
                pass
    return False


def validate_material(endpoint: str, certificate_pem: str, pairing_token: str, *,
                      openssl: Optional[str] = None, now: Optional[datetime] = None) -> ValidatedPairing:
    """Validate local material without connecting to the endpoint.

    ``now`` and the executable override support deterministic local tests; the
    CLI exposes neither. OpenSSL validates the leaf as the explicit trust
    anchor, including purpose, hostname, unknown critical extensions and time.
    Time is also checked directly because a leaf can itself be a trust anchor.
    """
    endpoint = normalize_endpoint(endpoint)
    token = _pairing_token(pairing_token)
    der, pem = _certificate_der(certificate_pem)
    executable = _openssl_path(openssl)
    moment = datetime.now(timezone.utc) if now is None else now
    if not isinstance(moment, datetime) or moment.tzinfo is None:
        raise PairingError("validation_time_invalid")
    # Re-encoding proves that input bytes are exactly one DER certificate, with
    # no second object or trailing data hidden inside the PEM block.
    canonical = _run_openssl(executable, ["x509", "-inform", "DER", "-outform", "DER"], raw=der)
    if canonical.returncode != 0 or canonical.stdout != der:
        raise PairingError("certificate_invalid")
    public_text = _run_openssl(executable, ["x509", "-inform", "DER", "-noout", "-text"], raw=der)
    if public_text.returncode != 0:
        raise PairingError("certificate_invalid")
    _key_policy(public_text.stdout)
    meta = _run_openssl(executable, ["x509", "-inform", "DER", "-noout", "-dates", "-ext",
                                   "subjectAltName,basicConstraints,keyUsage,extendedKeyUsage"], raw=der)
    if meta.returncode != 0:
        raise PairingError("certificate_invalid")
    dates, extensions = _metadata(meta.stdout)
    if not dates["notBefore"] <= moment < dates["notAfter"]:
        raise PairingError("certificate_invalid_time")
    usage = extensions.get("Key Usage", "").split(", ")
    if (extensions.get("Basic Constraints") != "CA:FALSE" or "Digital Signature" not in usage or
            "Certificate Sign" in usage or "CRL Sign" in usage or
            "TLS Web Server Authentication" not in extensions.get("Extended Key Usage", "").split(", ")):
        raise PairingError("certificate_not_server")
    host = urlsplit(endpoint).hostname
    names = extensions.get("Subject Alternative Name", "").split(", ")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if not any(name.startswith("DNS:") for name in names):
            raise PairingError("certificate_san_required")
        hostname_arguments = ["-verify_hostname", host]
    else:
        if not any(name.startswith("IP Address:") for name in names):
            raise PairingError("certificate_san_required")
        hostname_arguments = ["-verify_ip", host]
    if not _exact_san(host, names):
        raise PairingError("certificate_hostname_mismatch")
    with TemporaryDirectory(prefix="lexicon-ielts-certificate-") as directory:
        certificate = Path(directory) / "leaf.pem"
        _write_new(certificate, pem.encode("ascii"))
        verified = _run_openssl(executable, ["verify", "-trusted", str(certificate), "-partial_chain",
                               "-check_ss_sig", "-purpose", "sslserver", "-attime", str(int(moment.timestamp())),
                               *hostname_arguments, str(certificate)])
    if verified.returncode != 0:
        raise PairingError("certificate_validation_failed")
    result = ValidatedPairing(endpoint, pem, token, hashlib.sha256(der).hexdigest(),
                             _iso(dates["notBefore"]), _iso(dates["notAfter"]))
    result.encoded()
    return result


def validate_bundle(raw: bytes, *, openssl: Optional[str] = None,
                    now: Optional[datetime] = None) -> ValidatedPairing:
    if not isinstance(raw, bytes) or not 1 <= len(raw) <= MAX_BUNDLE_BYTES:
        raise PairingError("connection_file_size_invalid")
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise PairingError("connection_file_invalid")
            result[key] = value
        return result
    def nonfinite(_):
        raise PairingError("connection_file_invalid")
    try:
        value = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=pairs, parse_constant=nonfinite)
    except (ValueError, UnicodeError, RecursionError):
        raise PairingError("connection_file_invalid") from None
    if not isinstance(value, dict) or set(value) != _FIELDS or value["schema"] != SCHEMA:
        raise PairingError("connection_file_invalid")
    return validate_material(value["endpoint"], value["certificate_pem"], value["pairing_token"], openssl=openssl, now=now)


def _read_bounded(path: Path, maximum: int) -> bytes:
    try:
        with Path(path).open("rb") as stream:
            value = stream.read(maximum + 1)
    except OSError:
        raise PairingError("configured_file_unavailable") from None
    if len(value) > maximum:
        raise PairingError("configured_file_too_large")
    return value


def _write_new(path: Path, raw: bytes):
    descriptor = None
    try:
        descriptor = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            descriptor = None
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
    except FileExistsError:
        raise PairingError("output_already_exists") from None
    except OSError:
        raise PairingError("output_unavailable") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _receipt(material: ValidatedPairing, output: Path):
    # No bundle digest is returned: that would fingerprint a secret-bearing file.
    return {"connection_file": str(output.resolve()), "certificate_sha256": material.certificate_sha256,
            "expires_at": material.expires_at}


def export_pairing(endpoint: str, tls_cert: Path, pairing_token_file: Path, out: Path, *,
                   openssl: Optional[str] = None):
    try:
        pem = _read_bounded(tls_cert, MAX_BUNDLE_BYTES).decode("ascii", errors="strict")
        token = _read_bounded(pairing_token_file, 4096).decode("utf-8", errors="strict").strip()
    except UnicodeError:
        raise PairingError("configured_file_invalid") from None
    material = validate_material(endpoint, pem, token, openssl=openssl)
    _write_new(Path(out), material.encoded())
    return _receipt(material, Path(out))


def initialize_host(endpoint: str, out_dir: Path, *, openssl: Optional[str] = None):
    """Create a new private host directory; never start or configure a service.

    The same function can make ephemeral integration fixtures. Tests must keep
    its output private, never print the token/key, and remove their own directory.
    Existing directories are refused, including symlinks. Partial failures are
    left in the new private directory for inspection; they never report success.
    """
    endpoint = normalize_endpoint(endpoint)
    executable = _openssl_path(openssl)
    directory = Path(out_dir)
    try:
        directory.mkdir(mode=0o700, exist_ok=False)
        directory.chmod(0o700)
    except FileExistsError:
        raise PairingError("output_already_exists") from None
    except OSError:
        raise PairingError("output_unavailable") from None
    host = urlsplit(endpoint).hostname
    try:
        ipaddress.ip_address(host)
    except ValueError:
        san = "DNS:" + host
    else:
        san = "IP:" + host
    configuration = ("[req]\nprompt=no\ndistinguished_name=subject\nx509_extensions=server\n"
                     "[subject]\nCN=Lexicon personal IELTS host\n"
                     "[server]\nbasicConstraints=critical,CA:FALSE\n"
                     "keyUsage=critical,digitalSignature,keyEncipherment\n"
                     "extendedKeyUsage=serverAuth\nsubjectAltName=" + san + "\n")
    config_path = directory / "certificate.cnf"
    cert_path, key_path = directory / "server.crt", directory / "server.key"
    token_path, bundle_path = directory / "pairing-token.txt", directory / "pairing.json"
    _write_new(config_path, configuration.encode("ascii"))
    # Precreate the key file with exact permissions before OpenSSL writes it.
    _write_new(key_path, b"")
    _write_new(cert_path, b"")
    generated = _run_openssl(executable, ["req", "-new", "-x509", "-newkey", "rsa:2048", "-nodes",
                             "-sha256", "-days", str(DEFAULT_VALIDITY_DAYS), "-batch",
                             "-config", str(config_path.resolve()), "-keyout", str(key_path.resolve()),
                             "-out", str(cert_path.resolve())])
    if generated.returncode != 0:
        raise PairingError("certificate_generation_failed")
    try:
        key_path.chmod(0o600)
        cert_path.chmod(0o600)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(cert_path), str(key_path), password=lambda: "")
    except (OSError, ValueError, ssl.SSLError):
        raise PairingError("generated_tls_material_invalid") from None
    _write_new(token_path, (secrets.token_urlsafe(32) + "\n").encode("ascii"))
    return export_pairing(endpoint, cert_path, token_path, bundle_path, openssl=executable)


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        raise PairingError("arguments_invalid")


def main(argv=None):
    parser = _Parser(description="Create an offline connection file for your personal IELTS HTTPS host.")
    commands = parser.add_subparsers(dest="command", required=True)
    initialize = commands.add_parser("init", help="Create a new private certificate and pairing directory; do not start a service")
    initialize.add_argument("--endpoint", required=True)
    initialize.add_argument("--out-dir", required=True, type=Path)
    export = commands.add_parser("export", help="Export existing server certificate and independent pairing token")
    export.add_argument("--endpoint", required=True)
    export.add_argument("--tls-cert", required=True, type=Path)
    export.add_argument("--pairing-token-file", required=True, type=Path)
    export.add_argument("--out", required=True, type=Path)
    try:
        args = parser.parse_args(argv)
        if args.command == "init":
            result = initialize_host(args.endpoint, args.out_dir)
        else:
            result = export_pairing(args.endpoint, args.tls_cert, args.pairing_token_file, args.out)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except PairingError as error:
        print(json.dumps({"error": error.code}), file=sys.stderr)
        return 1
    except Exception:
        print(json.dumps({"error": "pairing_failed"}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
