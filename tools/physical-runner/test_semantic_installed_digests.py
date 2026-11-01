#!/usr/bin/env python3
"""Regression tests for the phone's fixed semantic APK digest endpoint."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock
from urllib import error, request
from http.server import ThreadingHTTPServer

SOURCE = Path(__file__).with_name("liliya_physical_rish_bridge.py")
TOKEN = "test-token-" + "z" * 40


class SemanticInstalledDigestsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        token_path = Path(cls.temp.name) / "token"
        token_path.write_text(TOKEN, encoding="utf-8")
        old = os.environ.get("LILIYA_PHYSICAL_BRIDGE_TOKEN_FILE")
        os.environ["LILIYA_PHYSICAL_BRIDGE_TOKEN_FILE"] = str(token_path)
        try:
            spec = importlib.util.spec_from_file_location("physical_bridge_under_test", SOURCE)
            cls.bridge = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(cls.bridge)
        finally:
            if old is None:
                os.environ.pop("LILIYA_PHYSICAL_BRIDGE_TOKEN_FILE", None)
            else:
                os.environ["LILIYA_PHYSICAL_BRIDGE_TOKEN_FILE"] = old

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self.bridge.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:%d/v1/semantic-installed-apk-digests" % self.server.server_port

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def get(self, authorized=True):
        headers = {"Authorization": "Bearer " + TOKEN} if authorized else {}
        req = request.Request(self.url, headers=headers)
        try:
            with request.urlopen(req, timeout=5) as response:
                return response.status, json.loads(response.read())
        except error.HTTPError as ex:
            return ex.code, json.loads(ex.read()) if ex.code != 401 else {}

    def mock_shell(self, command, timeout=30):
        import subprocess
        semantic = self.bridge.SEMANTIC_APP_PACKAGE
        test = self.bridge.SEMANTIC_TEST_PACKAGE
        if command == "pm path " + semantic:
            return subprocess.CompletedProcess([], 0, "package:/data/app/example/base.apk\n")
        if command == "pm path " + test:
            return subprocess.CompletedProcess([], 0, "package:/data/app/example2/base.apk\n")
        if command.startswith("sha256sum "):
            return subprocess.CompletedProcess([], 0, ("a" * 64) + " /data/app/example/base.apk\n")
        raise AssertionError("unexpected command " + command)

    def test_attests_only_both_fixed_semantic_packages(self):
        with mock.patch.object(self.bridge, "run_rish", side_effect=self.mock_shell) as shell:
            status, data = self.get()
        self.assertEqual(status, 200)
        self.assertEqual(set(data["packages"]), {self.bridge.SEMANTIC_APP_PACKAGE, self.bridge.SEMANTIC_TEST_PACKAGE})
        self.assertTrue(all(v["sha256"] == "a" * 64 for v in data["packages"].values()))
        self.assertEqual(shell.call_count, 4)

    def test_requires_authorization(self):
        with mock.patch.object(self.bridge, "run_rish") as shell:
            status, _ = self.get(authorized=False)
        self.assertEqual(status, 401)
        shell.assert_not_called()

    def test_rejects_missing_or_multiple_paths(self):
        import subprocess
        for output in ("", "package:/data/app/a/base.apk\npackage:/data/app/b/base.apk\n"):
            with self.subTest(output=output):
                with mock.patch.object(self.bridge, "run_rish", return_value=subprocess.CompletedProcess([], 0, output)):
                    status, body = self.get()
                self.assertEqual(status, 409)
                self.assertIn("error", body)

    def test_isolated_digest_route_uses_only_isolated_packages(self):
        import subprocess
        self.url = self.url.replace("/v1/semantic-installed-apk-digests", "/v1/isolated-llama-installed-apk-digests")
        def isolated_shell(command, timeout=30):
            if command.startswith("pm path "):
                self.assertIn(command.removeprefix("pm path "), (self.bridge.ISOLATED_APP_PACKAGE, self.bridge.ISOLATED_TEST_PACKAGE))
                return subprocess.CompletedProcess([], 0, "package:/data/app/example/base.apk\n")
            if command.startswith("sha256sum "):
                return subprocess.CompletedProcess([], 0, ("b" * 64) + " /data/app/example/base.apk\n")
            raise AssertionError(command)
        with mock.patch.object(self.bridge, "run_rish", side_effect=isolated_shell) as shell:
            status, data = self.get()
        self.assertEqual(status, 200)
        self.assertEqual(set(data["packages"]), {self.bridge.ISOLATED_APP_PACKAGE, self.bridge.ISOLATED_TEST_PACKAGE})
        self.assertEqual(shell.call_count, 4)

    def test_isolated_install_pair_refuses_replacement(self):
        import subprocess
        self.assertIn("/v1/install/isolated-llama-app", self.bridge.UPLOADS)
        self.assertIn("/v1/install/isolated-llama-test", self.bridge.UPLOADS)
        self.assertEqual(self.bridge.UPLOADS["/v1/install/isolated-llama-app"][1], self.bridge.ISOLATED_APP_PACKAGE)
        self.assertEqual(self.bridge.UPLOADS["/v1/install/isolated-llama-test"][1], self.bridge.ISOLATED_TEST_PACKAGE)

    def test_rejects_bad_digest(self):
        import subprocess
        def bad_digest(cmd, timeout=30):
            if cmd.startswith("pm path "):
                return subprocess.CompletedProcess([], 0, "package:/data/app/example/base.apk\n")
            return subprocess.CompletedProcess([], 0, "not-a-digest\n")
        with mock.patch.object(self.bridge, "run_rish", side_effect=bad_digest):
            status, body = self.get()
        self.assertEqual(status, 409)
        self.assertIn("error", body)


if __name__ == "__main__":
    unittest.main()
