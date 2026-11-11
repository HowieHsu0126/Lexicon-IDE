"""Offline connection-file tests using newly generated temporary certificates.

No API key, model, Android device, service listener, or system trust store is
used. Only this suite's temporary directories receive keys and pairing tokens.
"""
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timedelta, timezone
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import ielts_pairing as pairing


class PairingBundleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.openssl = shutil.which("openssl")
        if cls.openssl is None:
            raise unittest.SkipTest("OpenSSL unavailable; certificate integration tests not executed")
        cls.temp = TemporaryDirectory(prefix="lexicon-pairing-test-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.host = cls.root / "host"
        cls.public = pairing.initialize_host("https://localhost:8443", cls.host, openssl=cls.openssl)
        cls.pem = (cls.host / "server.crt").read_text()
        cls.token = (cls.host / "pairing-token.txt").read_text().strip()
        cls.raw = (cls.host / "pairing.json").read_bytes()
        cls.material = pairing.validate_bundle(cls.raw, openssl=cls.openssl)
        cls.der = ssl.PEM_cert_to_DER_cert(cls.pem)

    def setUp(self):
        self.temporary = TemporaryDirectory(prefix="lexicon-pairing-case-")
        self.addCleanup(self.temporary.cleanup)
        self.case = Path(self.temporary.name)
        # The production helper must remain an offline handoff; even validation
        # of hostile endpoints cannot attempt an application network connection.
        self.network = mock.patch("socket.create_connection", side_effect=AssertionError("Unexpected network request"))
        self.network.start()
        self.addCleanup(self.network.stop)

    def validate(self, *, endpoint="https://localhost:8443", pem=None, token=None, now=None):
        return pairing.validate_material(endpoint, self.pem if pem is None else pem,
                                         self.token if token is None else token,
                                         openssl=self.openssl, now=now)

    def assertFailure(self, code, function, *args, **kwargs):
        with self.assertRaises(pairing.PairingError) as caught:
            function(*args, **kwargs)
        self.assertEqual(code, caught.exception.code)
        self.assertNotIn(self.token, str(caught.exception))

    def openssl_run(self, *arguments, input=None):
        result = subprocess.run([self.openssl, *arguments], input=input, cwd=self.case,
                                capture_output=True, timeout=20)
        self.assertEqual(0, result.returncode, "Temporary fixture certificate command failed")
        return result

    def certificate(self, *, basic="critical,CA:FALSE", usage="critical,digitalSignature,keyEncipherment",
                    eku="serverAuth", san="DNS:localhost", extra=None, signature="-sha256", key=None):
        arguments = ["req", "-new", "-x509", "-key", str(self.host / "server.key") if key is None else str(key), signature, "-days", "2",
                     "-subj", "/CN=localhost", "-addext", "basicConstraints=" + basic]
        for key, value in (("keyUsage", usage), ("extendedKeyUsage", eku), ("subjectAltName", san)):
            if value is not None:
                arguments += ["-addext", key + "=" + value]
        if extra is not None:
            arguments += ["-addext", extra]
        return self.openssl_run(*arguments).stdout.decode("ascii")

    def test_initialize_creates_exact_private_handoff_without_api_or_private_key(self):
        value = json.loads(self.raw)
        self.assertEqual({"schema", "endpoint", "certificate_pem", "pairing_token"}, set(value))
        self.assertEqual(pairing.SCHEMA, value["schema"])
        self.assertEqual("https://localhost:8443/", value["endpoint"])
        self.assertLessEqual(len(self.raw), pairing.MAX_BUNDLE_BYTES)
        self.assertNotIn(b"PRIVATE KEY", self.raw)
        self.assertEqual(32, len(base64.urlsafe_b64decode(value["pairing_token"] + "=")))
        self.assertEqual(hashlib.sha256(self.der).hexdigest(), self.public["certificate_sha256"])
        self.assertEqual(self.public["expires_at"], self.material.expires_at)

    def test_private_directory_and_file_permissions(self):
        self.assertEqual(0o700, self.host.stat().st_mode & 0o777)
        for name in ("server.crt", "server.key", "pairing-token.txt", "pairing.json", "certificate.cnf"):
            self.assertEqual(0o600, (self.host / name).stat().st_mode & 0o777, name)

    def test_generated_certificate_is_90_days_and_loads_with_its_private_key(self):
        before = datetime.fromisoformat(self.material.not_before.replace("Z", "+00:00"))
        after = datetime.fromisoformat(self.material.expires_at.replace("Z", "+00:00"))
        self.assertEqual(timedelta(days=90), after - before)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(self.host / "server.crt"), str(self.host / "server.key"))
        public_key = self.openssl_run("x509", "-in", str(self.host / "server.crt"), "-noout", "-text").stdout
        self.assertIn(b"Public-Key: (2048 bit)", public_key)
        self.assertIn(b"sha256WithRSAEncryption", public_key)

    def test_bundle_is_valid_and_repr_redacts_private_material(self):
        value = pairing.validate_bundle(self.raw, openssl=self.openssl)
        self.assertEqual(self.material, value)
        self.assertEqual(self.raw, value.encoded())
        self.assertNotIn(self.token, repr(value))
        self.assertNotIn(self.pem, repr(value))

    def test_fingerprint_uses_der_not_pem_or_whitespace(self):
        wrapped = self.pem.replace("\n", "\r\n")
        value = self.validate(pem="\n\t" + wrapped + "\n")
        self.assertEqual(hashlib.sha256(self.der).hexdigest(), value.certificate_sha256)
        self.assertNotEqual(hashlib.sha256(self.pem.encode()).hexdigest(), value.certificate_sha256)
        self.assertEqual(self.pem, value.certificate_pem)

    def test_endpoint_normalization(self):
        examples = {"HTTPS://LOCALHOST:443": "https://localhost/", "https://LOCALHOST:8443/": "https://localhost:8443/",
                    "https://127.0.0.1:8443": "https://127.0.0.1:8443/", "https://[0:0:0:0:0:0:0:1]:8443": "https://[::1]:8443/"}
        for value, expected in examples.items():
            with self.subTest(value=value):
                self.assertEqual(expected, pairing.normalize_endpoint(value))

    def test_unsafe_or_nonorigin_endpoints_are_rejected(self):
        values = ["http://localhost", "//localhost", "https://a@localhost", "https://localhost/path",
                  "https://localhost?", "https://localhost#", "https://localhost?q=secret", "https://localhost/#part",
                  "https://localhost:", "https://localhost:0", "https://localhost:65536", "https://local host",
                  " https://localhost", "https://localhost\n", "https://[fe80::1%25en0]", "https://%6cocalhost",
                  "https://localhost\\foo", "https://你好.example", "https://-bad.example", "https://127.1", "https://127.00.0.1"]
        for value in values:
            with self.subTest(value=value):
                self.assertFailure("https_endpoint_invalid", pairing.normalize_endpoint, value)

    def test_dns_hostname_mismatch_is_rejected(self):
        self.assertFailure("certificate_hostname_mismatch", self.validate, endpoint="https://wrong.example:8443")

    def test_ip_san_is_checked_as_ip_not_dns(self):
        pem = self.certificate(san="IP:127.0.0.1")
        self.assertEqual("https://127.0.0.1:8443/", self.validate(endpoint="https://127.0.0.1:8443", pem=pem).endpoint)
        self.assertFailure("certificate_hostname_mismatch", self.validate, endpoint="https://127.0.0.2:8443", pem=pem)
        self.assertFailure("certificate_san_required", self.validate, endpoint="https://localhost:8443", pem=pem)

    def test_ipv6_init_is_offline_and_uses_exact_ip_san(self):
        result = pairing.initialize_host("https://[::1]:8443", self.case / "ipv6", openssl=self.openssl)
        bundle = pairing.validate_bundle(Path(result["connection_file"]).read_bytes(), openssl=self.openssl)
        self.assertEqual("https://[::1]:8443/", bundle.endpoint)

    def test_cn_without_san_is_rejected(self):
        self.assertFailure("certificate_san_required", self.validate, pem=self.certificate(san=None))

    def test_ca_certificate_is_not_a_server_leaf(self):
        self.assertFailure("certificate_not_server", self.validate, pem=self.certificate(basic="critical,CA:TRUE"))

    def test_explicit_server_auth_is_required(self):
        for eku in (None, "clientAuth", "anyExtendedKeyUsage"):
            with self.subTest(eku=eku):
                self.assertFailure("certificate_not_server", self.validate, pem=self.certificate(eku=eku))

    def test_digital_signature_usage_is_required(self):
        self.assertFailure("certificate_not_server", self.validate, pem=self.certificate(usage="critical,keyEncipherment"))

    def test_key_usage_cannot_sign_certificates_or_crls(self):
        for usage in (None, "critical,digitalSignature,keyCertSign", "critical,digitalSignature,cRLSign"):
            with self.subTest(usage=usage):
                self.assertFailure("certificate_not_server", self.validate, pem=self.certificate(usage=usage))

    def test_legacy_signature_is_rejected_even_for_explicit_anchor(self):
        self.assertFailure("certificate_signature_unsupported", self.validate, pem=self.certificate(signature="-sha1"))

    def test_weak_rsa_key_is_rejected_even_for_explicit_anchor(self):
        weak = self.case / "weak.key"
        self.openssl_run("genrsa", "-out", str(weak), "1024")
        self.assertFailure("certificate_key_unsupported", self.validate, pem=self.certificate(key=weak))

    def test_modern_ec_leaf_is_supported(self):
        key = self.case / "ec.key"
        self.openssl_run("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(key))
        self.assertEqual("https://localhost:8443/", self.validate(pem=self.certificate(key=key)).endpoint)

    def test_wildcard_is_not_an_exact_private_host_identity(self):
        self.assertFailure("certificate_hostname_mismatch", self.validate, endpoint="https://reader.example",
                           pem=self.certificate(san="DNS:*.example"))

    def test_unknown_critical_extension_is_rejected(self):
        self.assertFailure("certificate_validation_failed", self.validate,
                           pem=self.certificate(extra="1.2.3.4=critical,DER:01:01:FF"))

    def test_self_signature_corruption_is_rejected(self):
        mutated = self.der[:-1] + bytes((self.der[-1] ^ 1,))
        self.assertFailure("certificate_validation_failed", self.validate, pem=ssl.DER_cert_to_PEM_cert(mutated))

    def test_time_check_rejects_expired_and_not_yet_valid_certificates(self):
        before = datetime.fromisoformat(self.material.not_before.replace("Z", "+00:00"))
        after = datetime.fromisoformat(self.material.expires_at.replace("Z", "+00:00"))
        self.assertFailure("certificate_invalid_time", self.validate, now=before - timedelta(seconds=1))
        self.assertFailure("certificate_invalid_time", self.validate, now=after)
        self.assertFailure("certificate_invalid_time", self.validate, now=after + timedelta(days=1))
        self.assertEqual(self.material.certificate_sha256, self.validate(now=before + timedelta(seconds=1)).certificate_sha256)

    def test_openssl_independently_checks_expiration_of_trusted_leaf(self):
        after = datetime.fromisoformat(self.material.expires_at.replace("Z", "+00:00"))
        result = subprocess.run([self.openssl, "verify", "-trusted", str(self.host / "server.crt"), "-partial_chain",
                                 "-purpose", "sslserver", "-attime", str(int(after.timestamp()) + 1), str(self.host / "server.crt")],
                                capture_output=True, timeout=20)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"certificate has expired", result.stderr)

    def test_non_self_signed_leaf_is_valid_as_explicit_anchor(self):
        self.openssl_run("req", "-new", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
                         "-subj", "/CN=Temporary fixture authority", "-addext", "basicConstraints=critical,CA:TRUE",
                         "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-keyout", "ca.key", "-out", "ca.crt")
        self.openssl_run("req", "-new", "-key", str(self.host / "server.key"), "-subj", "/CN=localhost", "-out", "leaf.csr")
        (self.case / "leaf.ext").write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
                                          "extendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\n")
        self.openssl_run("x509", "-req", "-in", "leaf.csr", "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial",
                         "-sha256", "-days", "2", "-extfile", "leaf.ext", "-out", "leaf.crt")
        value = self.validate(pem=(self.case / "leaf.crt").read_text())
        self.assertEqual("https://localhost:8443/", value.endpoint)

    def test_second_pem_private_key_and_trailing_prose_are_rejected(self):
        values = [self.pem + self.pem, self.pem + "untrusted prose", "untrusted prose" + self.pem,
                  self.pem + "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----\n"]
        for value in values:
            with self.subTest(length=len(value)):
                self.assertFailure("single_certificate_required", self.validate, pem=value)

    def test_der_trailing_bytes_and_oversized_der_are_rejected(self):
        self.assertFailure("certificate_invalid", self.validate, pem=ssl.DER_cert_to_PEM_cert(self.der + b"\x00"))
        self.assertFailure("certificate_size_invalid", self.validate, pem=ssl.DER_cert_to_PEM_cert(b"a" * 8193))

    def test_invalid_token_is_rejected_before_certificate_subprocess(self):
        for token in ("short", "x" * 42, "x" * 129, " " + self.token, self.token + "=", "x" * 45, 123):
            with self.subTest(kind=type(token).__name__, length=len(str(token))):
                with mock.patch.object(pairing, "_run_openssl") as command:
                    self.assertFailure("pairing_token_invalid", self.validate, token=token)
                    command.assert_not_called()

    def test_duplicate_unknown_missing_or_nonstring_json_fields_are_rejected(self):
        value = json.loads(self.raw)
        invalid = [json.dumps({**value, "extra": "x"}), json.dumps({key: val for key, val in value.items() if key != "endpoint"}),
                   json.dumps({**value, "schema": "unknown"}), "[]", "null", '{"schema":NaN}',
                   self.raw.decode().strip()[:-1] + ',"endpoint":"https://localhost/"}']
        for raw in invalid:
            with self.subTest(length=len(raw)):
                self.assertFailure("connection_file_invalid", pairing.validate_bundle, raw.encode(), openssl=self.openssl)
        self.assertFailure("certificate_invalid", pairing.validate_bundle,
                           json.dumps({**value, "certificate_pem": 123}).encode(), openssl=self.openssl)

    def test_file_size_and_utf8_validation_precede_any_subprocess(self):
        for raw, code in [(b"", "connection_file_size_invalid"), (b" " * 16385, "connection_file_size_invalid"),
                          (b"\xff", "connection_file_invalid"), ("not bytes", "connection_file_size_invalid")]:
            with mock.patch.object(pairing, "_run_openssl") as command:
                self.assertFailure(code, pairing.validate_bundle, raw, openssl=self.openssl)
                command.assert_not_called()

    def test_export_writes_new_private_file_and_refuses_overwrite(self):
        output = self.case / "connection.json"
        result = pairing.export_pairing("https://localhost:8443", self.host / "server.crt", self.host / "pairing-token.txt", output, openssl=self.openssl)
        self.assertEqual(self.raw, output.read_bytes())
        self.assertEqual(0o600, output.stat().st_mode & 0o777)
        self.assertEqual({"connection_file", "certificate_sha256", "expires_at"}, set(result))
        self.assertFailure("output_already_exists", pairing.export_pairing, "https://localhost:8443", self.host / "server.crt",
                           self.host / "pairing-token.txt", output, openssl=self.openssl)
        self.assertEqual(self.raw, output.read_bytes())

    def test_export_does_not_follow_existing_output_symlink(self):
        target = self.case / "preserve.txt"
        target.write_text("preserve")
        output = self.case / "connection.json"
        output.symlink_to(target)
        self.assertFailure("output_already_exists", pairing.export_pairing, "https://localhost:8443", self.host / "server.crt",
                           self.host / "pairing-token.txt", output, openssl=self.openssl)
        self.assertEqual("preserve", target.read_text())

    def test_init_refuses_existing_directory_or_symlink(self):
        (self.case / "existing").mkdir()
        (self.case / "link").symlink_to(self.case / "existing")
        for name in ("existing", "link"):
            with mock.patch.object(pairing, "_run_openssl") as command:
                self.assertFailure("output_already_exists", pairing.initialize_host, "https://localhost", self.case / name, openssl=self.openssl)
                command.assert_not_called()

    def test_init_invalid_endpoint_creates_nothing(self):
        output = self.case / "invalid"
        self.assertFailure("https_endpoint_invalid", pairing.initialize_host, "http://localhost", output, openssl=self.openssl)
        self.assertFalse(output.exists())

    def test_subprocess_failure_does_not_expose_stderr_or_input(self):
        failure = subprocess.CompletedProcess([], 1, b"", self.token.encode())
        with mock.patch.object(pairing, "_run_openssl", return_value=failure):
            self.assertFailure("certificate_generation_failed", pairing.initialize_host,
                               "https://localhost", self.case / "failure", openssl=self.openssl)
        self.assertFalse((self.case / "failure" / "pairing.json").exists())
        self.assertFalse((self.case / "failure" / "pairing-token.txt").exists())

    def test_cli_success_outputs_public_receipt_only(self):
        output = self.case / "cli.json"
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = pairing.main(["export", "--endpoint", "https://localhost:8443", "--tls-cert", str(self.host / "server.crt"),
                                 "--pairing-token-file", str(self.host / "pairing-token.txt"), "--out", str(output)])
        self.assertEqual(0, code)
        self.assertEqual("", stderr.getvalue())
        self.assertEqual({"connection_file", "certificate_sha256", "expires_at"}, set(json.loads(stdout.getvalue())))
        for value in (self.token, self.pem, (self.host / "server.key").read_text(), hashlib.sha256(self.raw).hexdigest()):
            self.assertNotIn(value, stdout.getvalue() + stderr.getvalue())

    def test_cli_rejects_arguments_without_echoing_secret_values(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = pairing.main(["init", "--endpoint", "https://localhost", "--out-dir", str(self.case / "out"), "--private", self.token])
        self.assertEqual(1, code)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual({"error": "arguments_invalid"}, json.loads(stderr.getvalue()))
        self.assertNotIn(self.token, stderr.getvalue())

    def test_no_system_trust_is_installed(self):
        before = ssl.create_default_context().get_ca_certs(binary_form=True)
        self.validate()
        after = ssl.create_default_context().get_ca_certs(binary_form=True)
        self.assertEqual(before, after)
        self.assertNotIn(self.der, after)


if __name__ == "__main__":
    unittest.main()
