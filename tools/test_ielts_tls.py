"""Real loopback TLS handshakes and HTTP requests with synthetic durable jobs.

Temporary certificates are signed by an ephemeral test CA. Only test client
SSLContext instances explicitly trust that CA; production trust, the Keychain,
and the production TLS implementation are unchanged. No real API key, pairing
credential, model, Harness session, LAN listener, or external request is used.
Passing these tests is local TLS/HTTP evidence, not a real-model quality result.
"""
import concurrent.futures
import copy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import http.client
import io
import json
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
from tempfile import TemporaryDirectory
import threading
import time
import unittest
import warnings

# Existing gateway modules deliberately use fixed sibling imports as they do
# in the host CLI. Support both unittest discovery and direct module execution.
TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import ielts_http as api
from ielts_jobs import ExerciseJobs
from test_ielts_contract import fixture


class LoopbackHttpsConnection(http.client.HTTPSConnection):
    """Always dial 127.0.0.1; the independently chosen name is verified by TLS."""
    def __init__(self, port, context, hostname="localhost", timeout=3):
        super().__init__(hostname, port, context=context, timeout=timeout)
        self.fixture_context = context

    def connect(self):
        raw = socket.create_connection(("127.0.0.1", self.port), self.timeout)
        try:
            self.sock = self.fixture_context.wrap_socket(raw, server_hostname=self.host)
        except BaseException:
            raw.close()
            raise


class LocalTlsIntegrationTest(unittest.TestCase):
    openssl_version = None

    @classmethod
    def setUpClass(cls):
        cls.openssl = shutil.which("openssl")
        if cls.openssl is None:
            raise unittest.SkipTest("OpenSSL is unavailable; actual certificate/TLS tests not executed")
        cls.certificates = TemporaryDirectory(prefix="lexicon-test-tls-")
        cls.addClassCleanup(cls.certificates.cleanup)
        cls.cert_dir = Path(cls.certificates.name)
        cls.openssl_version = cls._openssl("version").stdout.strip()
        # Explicitly local fixtures, valid only briefly. No trust installation.
        for name in ("ca", "wrong-ca"):
            cls._openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
                         "-subj", "/CN=Lexicon ephemeral {} fixture".format(name),
                         "-addext", "basicConstraints=critical,CA:TRUE",
                         "-addext", "keyUsage=critical,keyCertSign,cRLSign",
                         "-keyout", name + ".key", "-out", name + ".crt")
        cls._openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-sha256",
                     "-subj", "/CN=localhost", "-keyout", "server.key", "-out", "server.csr")
        (cls.cert_dir / "server.ext").write_text(
            "basicConstraints=critical,CA:FALSE\n"
            "keyUsage=critical,digitalSignature,keyEncipherment\n"
            "extendedKeyUsage=serverAuth\n"
            "subjectAltName=DNS:localhost,IP:127.0.0.1\n", encoding="ascii")
        cls._openssl("x509", "-req", "-in", "server.csr", "-CA", "ca.crt", "-CAkey", "ca.key",
                     "-CAcreateserial", "-sha256", "-days", "2", "-extfile", "server.ext", "-out", "server.crt")
        for name in ("ca.key", "wrong-ca.key", "server.key"):
            (cls.cert_dir / name).chmod(0o600)

    @classmethod
    def _openssl(cls, *arguments):
        result = subprocess.run([cls.openssl, *arguments], cwd=str(cls.cert_dir),
                                capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise RuntimeError("Temporary test certificate command failed")
        return result

    @classmethod
    def trusted_context(cls, ca="ca.crt"):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_verify_locations(cafile=str(cls.cert_dir / ca))
        # Do not silently weaken either requirement for a local test service.
        if not context.check_hostname or context.verify_mode != ssl.CERT_REQUIRED:
            raise AssertionError("The TLS fixture requires certificate and hostname verification")
        return context

    def setUp(self):
        self.temp = TemporaryDirectory(prefix="lexicon-test-tls-jobs-")
        self.addCleanup(self.temp.cleanup)
        self.jobs = ExerciseJobs(Path(self.temp.name) / "synthetic-jobs.sqlite")
        self.request, self.package, _ = fixture()
        self.request["request_id"] = "tls-synthetic-request"
        self.request["article_id"] = "tls-synthetic-article"
        self.body = "\n\n".join(p["text"] for p in self.request["paragraphs"])
        self.payload = {key: copy.deepcopy(self.request[key]) for key in
                        ("request_id", "article_id", "paragraphs", "reading_requests", "writing_task2_request")}
        self.payload.update(schema=api.SUBMIT_SCHEMA, body=self.body)
        self.token = api.generate_pairing_token()
        self.capabilities = {
            "runtime_version": "synthetic-tls-fixture", "corpus_id": "synthetic-tls-fixture",
            "corpus_sha256": "b" * 64, "supported_reading_types": list(api.contract.READING_TYPES),
            "supported_writing_task2_types": list(api.contract.TASK2_TYPES),
            "corpus_scope": "official_public_samples_and_format_metadata",
        }
        self.call_lock = threading.Lock()
        self.submissions = 0
        self.commit_then_timeout = False
        self.raise_internal_error = False
        self.committed = threading.Event()
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(self.cert_dir / "server.crt"), str(self.cert_dir / "server.key"))
        self.server = api.create_server(bind="127.0.0.1", port=0, tls_context=context,
                                        token=self.token, jobs=self.jobs, on_submit=self.submit,
                                        capabilities=self.capabilities)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05},
                                       name="lexicon-local-tls-fixture", daemon=False)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.port = self.server.server_address[1]

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        if self.thread.is_alive():
            raise AssertionError("Owned loopback TLS fixture listener did not stop")

    def submit(self, payload):
        with self.call_lock:
            self.submissions += 1
        if self.raise_internal_error:
            raise RuntimeError("PRIVATE_TLS_FIXTURE_ERROR_MATERIAL")
        request = copy.deepcopy(self.request)
        for field in ("request_id", "article_id", "paragraphs", "reading_requests", "writing_task2_request"):
            request[field] = copy.deepcopy(payload[field])
        request["body_hash"] = hashlib.sha256(payload["body"].encode("utf-8")).hexdigest()
        stored = self.jobs.submit(request, payload["body"], self.capabilities["corpus_sha256"])
        self.committed.set()
        if self.commit_then_timeout:
            raise TimeoutError("PRIVATE_TLS_FIXTURE_TIMEOUT_MATERIAL")
        return stored

    def exchange(self, method="GET", path="/health", payload=None, *, authenticate=True,
                 wrong_token=False, context=None, hostname="localhost"):
        context = context or self.trusted_context()
        connection = LoopbackHttpsConnection(self.port, context, hostname)
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        headers = {"Accept": "application/json", "Connection": "close"}
        if raw is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        if authenticate:
            headers["Authorization"] = "Bearer " + (api.generate_pairing_token() if wrong_token else self.token)
        try:
            connection.connect()
            version = connection.sock.version()
            connection.request(method, path, body=raw, headers=headers)
            response = connection.getresponse()
            body = response.read(api.MAX_RESPONSE_BYTES + 1)
            if len(body) > api.MAX_RESPONSE_BYTES:
                raise AssertionError("Loopback response exceeded the transport contract")
            return response.status, json.loads(body.decode("utf-8")), dict(response.getheaders()), version
        finally:
            connection.close()

    def test_authenticated_health_uses_real_tls_and_explicit_test_ca(self):
        # Some managed Python distributions preconfigure a platform CA even on
        # a fresh context. Assert the exact local addition rather than assuming
        # the runtime starts with an empty trust store.
        baseline = set(ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT).get_ca_certs(binary_form=True))
        context = self.trusted_context()
        temporary_ca = ssl.PEM_cert_to_DER_cert((self.cert_dir / "ca.crt").read_text(encoding="ascii"))
        self.assertEqual({temporary_ca}, set(context.get_ca_certs(binary_form=True)) - baseline)
        status, value, headers, version = self.exchange(context=context)
        self.assertEqual(200, status)
        self.assertEqual({"schema": "lexicon.ielts.health.v1", "status": "ok", "capabilities": self.capabilities}, value)
        self.assertIn(version, ("TLSv1.2", "TLSv1.3"))
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual("nosniff", headers["X-Content-Type-Options"])
        self.assertEqual("close", headers["Connection"])
        self.assertFalse(self.token in json.dumps(value))
        self.assertEqual("127.0.0.1", self.server.server_address[0])

    def test_tls12_is_accepted_at_the_production_minimum(self):
        context = self.trusted_context()
        context.maximum_version = ssl.TLSVersion.TLSv1_2
        status, _, _, version = self.exchange(context=context)
        self.assertEqual(200, status); self.assertEqual("TLSv1.2", version)

    def test_system_trust_is_not_modified_or_replaced_by_local_ca(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.exchange(context=ssl.create_default_context())
        self.assertEqual(200, self.exchange()[0])
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.exchange(context=ssl.create_default_context())
        self.assertEqual(0, self.submissions)

    def test_wrong_ca_is_rejected_before_http_submission(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.exchange("POST", "/exercise-jobs", self.payload, context=self.trusted_context("wrong-ca.crt"))
        self.assertEqual(0, self.submissions)
        self.assertFalse(self.committed.is_set())

    def test_wrong_hostname_is_rejected_even_with_the_correct_ca(self):
        # No DNS lookup is performed for this deliberately wrong name.
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.exchange("POST", "/exercise-jobs", self.payload, hostname="wrong-hostname.invalid")
        self.assertEqual(0, self.submissions)

    def test_ip_subject_alternative_name_is_also_verified(self):
        self.assertEqual(200, self.exchange(hostname="127.0.0.1")[0])

    def test_tls11_is_rejected_when_the_client_can_offer_it(self):
        if not getattr(ssl, "HAS_TLSv1_1", False):
            self.skipTest("Client TLS library has no TLS 1.1 support")
        context = self.trusted_context()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            context.minimum_version = ssl.TLSVersion.TLSv1_1
            context.maximum_version = ssl.TLSVersion.TLSv1_1
        try:
            # This compatibility concession belongs only to the adversarial
            # test client. The actual create_server context stays at TLS 1.2.
            context.set_ciphers("DEFAULT:@SECLEVEL=0")
        except ssl.SSLError:
            pass
        try:
            self.exchange(context=context)
        except ssl.SSLError as error:
            if error.reason in ("NO_PROTOCOLS_AVAILABLE", "NO_CIPHERS_AVAILABLE", "UNSUPPORTED_PROTOCOL"):
                self.skipTest("Client TLS runtime could not offer a usable TLS 1.1 handshake")
            self.assertIn(error.reason, ("TLSV1_ALERT_PROTOCOL_VERSION", "SSLV3_ALERT_HANDSHAKE_FAILURE",
                                         "TLSV1_ALERT_INSUFFICIENT_SECURITY"))
        else:
            self.fail("Production TLS listener accepted TLS 1.1")

    def test_plain_http_cannot_reach_the_https_application(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as raw:
            raw.sendall(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            try:
                result = raw.recv(1024)
            except (ConnectionResetError, BrokenPipeError):
                result = b""
        self.assertFalse(result.startswith(b"HTTP/"))
        self.assertEqual(0, self.submissions)

    def test_missing_token_is_rejected_before_route_or_submission(self):
        for method, path, payload in (("GET", "/health", None), ("POST", "/exercise-jobs", self.payload),
                                      ("GET", "/exercise-jobs/by-request/unknown", None), ("GET", "/not-a-route", None)):
            status, value, headers, _ = self.exchange(method, path, payload, authenticate=False)
            self.assertEqual(401, status)
            self.assertEqual("unauthorized", value["error"]["code"])
            self.assertEqual("Bearer", headers["WWW-Authenticate"])
        self.assertEqual(0, self.submissions)
        self.assertEqual([], self.jobs.queued(limit=8))

    def test_wrong_token_is_rejected_after_successful_tls(self):
        status, value, _, version = self.exchange("POST", "/exercise-jobs", self.payload, wrong_token=True)
        self.assertEqual(401, status); self.assertEqual("unauthorized", value["error"]["code"])
        self.assertIn(version, ("TLSv1.2", "TLSv1.3")); self.assertEqual(0, self.submissions)

    def test_duplicate_authorization_headers_are_not_merged_or_accepted(self):
        connection = LoopbackHttpsConnection(self.port, self.trusted_context())
        try:
            connection.putrequest("GET", "/health")
            connection.putheader("Authorization", "Bearer " + self.token)
            connection.putheader("Authorization", "Bearer " + self.token)
            connection.putheader("Connection", "close")
            connection.endheaders()
            response = connection.getresponse(); value = json.loads(response.read())
            self.assertEqual(401, response.status); self.assertEqual("unauthorized", value["error"]["code"])
        finally:
            connection.close()

    def test_durable_submit_identical_retry_and_get_reconciliation(self):
        first_status, first, _, _ = self.exchange("POST", "/exercise-jobs", self.payload)
        self.assertEqual(202, first_status)
        job = first["job"]
        self.assertEqual("queued", job["state"])
        self.assertEqual(job, self.jobs.get(job["id"]))
        second_status, second, _, _ = self.exchange("POST", "/exercise-jobs", self.payload)
        self.assertEqual(202, second_status); self.assertEqual(first, second)
        by_request = self.exchange(path="/exercise-jobs/by-request/" + self.payload["request_id"])
        by_id = self.exchange(path="/exercise-jobs/" + job["id"])
        self.assertEqual(200, by_request[0]); self.assertEqual(200, by_id[0])
        self.assertEqual(first, by_request[1]); self.assertEqual(first, by_id[1])
        self.assertEqual(2, self.submissions)
        self.assertEqual(1, len(self.jobs.queued(limit=8)))

    def test_concurrent_identical_submits_create_one_durable_job(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(self.exchange, "POST", "/exercise-jobs", self.payload) for _ in range(4)]
            results = [future.result(timeout=8) for future in futures]
        self.assertEqual([202] * 4, [result[0] for result in results])
        self.assertEqual(1, len({result[1]["job"]["id"] for result in results}))
        self.assertEqual(1, len(self.jobs.queued(limit=8)))
        self.assertEqual(4, self.submissions)

    def test_changed_material_for_existing_request_id_conflicts(self):
        first = self.exchange("POST", "/exercise-jobs", self.payload)[1]
        changed = copy.deepcopy(self.payload); changed["body"] += " Additional synthetic text."
        status, value, _, _ = self.exchange("POST", "/exercise-jobs", changed)
        self.assertEqual(409, status); self.assertEqual("request_id_conflict", value["error"]["code"])
        self.assertEqual(first["job"], self.jobs.by_request(self.payload["request_id"]))

    def test_committed_callback_timeout_preserves_id_for_get_without_auto_retry(self):
        self.commit_then_timeout = True
        status, value, _, _ = self.exchange("POST", "/exercise-jobs", self.payload)
        self.assertEqual(504, status)
        self.assertEqual("submit_outcome_unknown", value["error"]["code"])
        self.assertEqual(self.payload["request_id"], value["error"]["request_id"])
        self.assertEqual(1, self.submissions)
        status, found, _, _ = self.exchange(path="/exercise-jobs/by-request/" + self.payload["request_id"])
        self.assertEqual(200, status); self.assertEqual("queued", found["job"]["state"])
        self.assertEqual(1, self.submissions)
        self.assertFalse("PRIVATE" in json.dumps(value))

    def test_slow_client_handshake_does_not_block_another_authenticated_client(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=3):
            started = time.monotonic()
            status, _, _, _ = self.exchange()
            elapsed = time.monotonic() - started
        self.assertEqual(200, status)
        self.assertLess(elapsed, api.SOCKET_TIMEOUT)

    def test_cancelled_synthetic_job_cannot_publish_a_package(self):
        _, created, _, _ = self.exchange("POST", "/exercise-jobs", self.payload)
        base = "/exercise-jobs/" + created["job"]["id"]
        status, cancelled, _, _ = self.exchange("POST", base + "/cancel")
        self.assertEqual(202, status); self.assertEqual("cancelled", cancelled["job"]["state"])
        for suffix in ("/package", "/candidate"):
            status, value, _, _ = self.exchange(path=base + suffix)
            self.assertEqual(409, status); self.assertEqual("package_unavailable", value["error"]["code"])

    def test_response_and_server_logs_do_not_echo_fixture_credentials_or_errors(self):
        output, errors = io.StringIO(), io.StringIO()
        self.raise_internal_error = True
        with redirect_stdout(output), redirect_stderr(errors):
            self.exchange()
            self.exchange(wrong_token=True)
            status, value, headers, _ = self.exchange("POST", "/exercise-jobs", self.payload)
        self.assertEqual(500, status); self.assertEqual("internal_error", value["error"]["code"])
        self.assertEqual("", output.getvalue() + errors.getvalue())
        serialized = json.dumps({"body": value, "headers": headers})
        self.assertFalse(self.token in serialized)
        self.assertFalse("PRIVATE_TLS_FIXTURE" in serialized)
        self.assertFalse(self.body in serialized)


if __name__ == "__main__":
    unittest.main()
