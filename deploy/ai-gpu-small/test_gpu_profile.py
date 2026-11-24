"""Meaningful failure/serialization/report tests; no GPU/model/provider requests."""
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import time
from types import SimpleNamespace as NS
import unittest
from unittest.mock import patch

import gpu_runtime as runtime
import verify_trial as trial
import verify_source
import verify_compose


def status():
    return {"documents": dict(runtime.DOCUMENTS), "dense_index_scopes": list(runtime.DOCUMENTS),
            "embedding_enabled": True, "reranker_enabled": True,
            "embedding_model": "BGE-m3-ko", "reranker_model": "KURE-Reranker-nano",
            "embedding_error": None, "reranker_error": None, "index_errors": {}}


def parameter(device="cuda", dtype="torch.float16"):
    return NS(device=NS(type=device), dtype=dtype, is_floating_point=lambda: True)


class Models(unittest.TestCase):
    def test_cpu_device_or_wrong_dtype_is_rejected(self):
        for device, dtype in [("cpu", "torch.float16"), ("cuda", "torch.float32")]:
            with self.subTest(device=device), self.assertRaises(runtime.ProfileError):
                runtime.model_cuda(NS(parameters=lambda: [parameter(device, dtype)]))

    def test_parameters_checked_instead_of_requested_device(self):
        runtime.model_cuda(NS(parameters=lambda: [parameter()]))
        runtime.model_cuda(NS(model=NS(parameters=lambda: [parameter()])), reranker=True)
        with self.assertRaises(runtime.ProfileError):
            runtime.model_cuda(NS(parameters=lambda: []))

    def test_gpu_unavailable_is_not_cpu_success(self):
        with self.assertRaisesRegex(runtime.ProfileError, "GPU_UNAVAILABLE"):
            runtime.cuda_info(NS(cuda=NS(is_available=lambda: False)))

    def test_shared_gate_serializes_different_models(self):
        gate = runtime.Gate(timeout=1)
        active, peak = 0, 0
        lock = threading.Lock()
        def work(*args):
            nonlocal active, peak
            with lock:
                active += 1
                peak = max(peak, active)
            time.sleep(.02)
            with lock:
                active -= 1
            return [1]
        encoder = runtime.GuardedModel(NS(encode_query=work), gate, "embedding")
        reranker = runtime.GuardedModel(NS(score=work), gate, "reranker")
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(encoder.encode_query, "synthetic question")
            second = pool.submit(reranker.score, "synthetic question", ["synthetic document"])
            self.assertEqual(first.result(), [1])
            self.assertEqual(second.result(), [1])
        self.assertEqual(peak, 1)
        self.assertEqual(gate.snapshot()["calls"], {"embedding": 1, "reranker": 1})

    def test_synchronize_failure_is_not_counted_as_success(self):
        def fail():
            raise RuntimeError("synthetic driver failure")
        gate = runtime.Gate(synchronize=fail)
        encoder = runtime.GuardedModel(NS(encode_query=lambda q: [1]), gate, "embedding")
        with self.assertRaisesRegex(runtime.ProfileError, "GPU_INFERENCE_FAILED"):
            encoder.encode_query("synthetic")
        self.assertEqual(gate.snapshot(), {"failed": True, "calls": {"embedding": 0, "reranker": 0}})

    def test_busy_does_not_poison_gpu_but_is_bounded(self):
        gate = runtime.Gate(timeout=.01)
        with gate.enter(), ThreadPoolExecutor(max_workers=1) as pool:
            def work():
                with gate.enter():
                    self.fail("A different thread must not enter")
            with self.assertRaisesRegex(runtime.ProfileError, "GPU_BUSY"):
                pool.submit(work).result()
        self.assertFalse(gate.failed)

    def test_silent_upstream_fallback_is_rejected_after_model_failure(self):
        gate = runtime.Gate()
        def work(*args, **kwargs):
            gate.mark_failed()
            return {"items": [], "diagnostics": {"search_mode": "bm25", "ranking": []}}
        retrieval = runtime.StrictRetrieval(NS(search=work), gate)
        with self.assertRaisesRegex(runtime.ProfileError, "GPU_INFERENCE_FAILED"):
            retrieval.search("synthetic")

    def test_rule_ranking_cannot_be_gpu_execution(self):
        gate = runtime.Gate()
        source = NS(search=lambda *a, **kw: {"items": [], "diagnostics": {
            "ranking": [1], "search_mode": "bm25", "reranker": "rule_based"}})
        with self.assertRaisesRegex(runtime.ProfileError, "GPU_QUERY_FALLBACK"):
            runtime.StrictRetrieval(source, gate).list_activities("synthetic")

    def test_hybrid_labels_without_real_calls_are_rejected(self):
        source = NS(search=lambda *a, **kw: {"items": [], "diagnostics": {
            "ranking": [1], "search_mode": "hybrid_bm25_dense_rrf", "reranker": "cross_encoder"}})
        with self.assertRaisesRegex(runtime.ProfileError, "GPU_QUERY_NOT_EXECUTED"):
            runtime.StrictRetrieval(source, runtime.Gate()).search("synthetic")

    def test_legitimate_empty_search_does_not_require_inference(self):
        source = NS(search=lambda *a, **kw: {"items": [], "diagnostics": {"ranking": []}})
        self.assertEqual(runtime.StrictRetrieval(source, runtime.Gate()).search(""), source.search())

    def test_batch_one_keeps_length_and_candidates(self):
        made = []
        def model(path, device, max_length=1024, batch_size=32):
            made.append((path, device, max_length, batch_size))
            return NS()
        def retrieval(settings, **kwargs):
            self.assertEqual(settings.rerank_candidates, 20)
            self.assertEqual(set(kwargs), {"encoder", "reranker"})
            return NS(status=status)
        settings = NS(embedding_model_path="BGE-m3-ko", reranker_model_path="KURE-Reranker-nano", rerank_candidates=20)
        runtime.build_retrieval(settings, model, model, retrieval)
        self.assertEqual(made, [("BGE-m3-ko", "cuda", 1024, 1), ("KURE-Reranker-nano", "cuda", 1024, 1)])

    def test_all_dense_scopes_and_exact_counts_are_mandatory(self):
        for mutation in (lambda s: s["dense_index_scopes"].remove("recipes"),
                         lambda s: s["documents"].update(recipes=1),
                         lambda s: s["index_errors"].update(recipes="fingerprint differs")):
            row = status()
            mutation(row)
            with self.assertRaises(runtime.ProfileError):
                runtime.ready_status(row)

    def test_missing_scopes_report_only_known_names(self):
        for loaded, suffix in [(["knowledge", "activities"], "RECIPES"),
                               (["recipes"], "ACTIVITIES_KNOWLEDGE"),
                               (list(runtime.DOCUMENTS) + ["unknown"], "UNEXPECTED")]:
            row = status()
            row["dense_index_scopes"] = loaded
            with self.assertRaisesRegex(runtime.ProfileError, "^INDEX_DENSE_SCOPES_" + suffix + "$"):
                runtime.ready_status(row)


class Assets(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.models = self.root / "models"
        self.index = self.root / "index"
        self.models.mkdir()
        self.index.mkdir()
        (self.models / "model.txt").write_bytes(b"synthetic-model")
        (self.index / "vectors.txt").write_bytes(b"synthetic-vectors")
        self.manifest = [{"Path": name, "Bytes": len(raw), "Sha256": hashlib.sha256(raw).hexdigest()}
            for name, raw in [("models/model.txt", b"synthetic-model"),
                              ("backend/var/index/vectors.txt", b"synthetic-vectors")]]

    def test_valid_asset_hashes_without_writes(self):
        before = (self.index / "vectors.txt").stat().st_mtime_ns
        self.assertEqual(runtime.audit_assets(self.manifest, self.models, self.index)["files"], 2)
        self.assertEqual((self.index / "vectors.txt").stat().st_mtime_ns, before)

    def test_same_size_drift_is_rejected(self):
        (self.models / "model.txt").write_bytes(b"Synthetic-model")
        with self.assertRaisesRegex(runtime.ProfileError, "ASSET_HASH"):
            runtime.audit_assets(self.manifest, self.models, self.index)

    def test_extra_model_file_is_rejected(self):
        (self.models / "unreviewed.txt").write_text("synthetic")
        with self.assertRaisesRegex(runtime.ProfileError, "ASSET_EXTRA_MODEL_FILES"):
            runtime.audit_assets(self.manifest, self.models, self.index)

    def test_duplicate_and_traversal_are_rejected(self):
        for rows in [self.manifest * 2, [{**self.manifest[0], "Path": "models/../model.txt"}]]:
            with self.assertRaises(runtime.ProfileError):
                runtime.audit_assets(rows, self.models, self.index)

    def test_symlink_is_rejected(self):
        target = self.models / "model.txt"
        replacement = self.root / "source.txt"
        target.rename(replacement)
        target.symlink_to(replacement)
        with self.assertRaisesRegex(runtime.ProfileError, "ASSET_SYMLINK"):
            runtime.audit_assets(self.manifest, self.models, self.index)


def good_report():
    return {"hardware_trial": True, "ai_revision": runtime.AI_REVISION, "duration_seconds": 1801,
        "samples": [{"scope": list(runtime.DOCUMENTS)[i % 3], "ok": True, "llm_calls": 0,
            "mode": "hybrid_bm25_dense_rrf", "reranker": "cross_encoder", "seconds": 1.0,
            "concurrency": 1 if i < 20 else 2} for i in range(100)],
        "final_status": {"profile": "g6f.large-rag-v1", "ai_revision": runtime.AI_REVISION,
            "retrieval": status(), "runtime": {"failed": False, "calls": {"embedding": 100, "reranker": 100}},
            "hardware": {"name": "NVIDIA L4", "total_bytes": 2861 * 1024**2,
                "audited_assets": {"files": 34, "bytes": 7852052666}},
            "max_allocated_bytes": 1500 * 1024**2, "max_reserved_bytes": 1800 * 1024**2,
            "cgroup_peak_bytes": 4 * 1024**3, "disk_free_bytes": 12 * 1024**3}}


class Reports(unittest.TestCase):
    def test_valid_evidence(self):
        self.assertTrue(trial.validate_report(good_report())["passed"])

    def test_invalid_evidence_never_becomes_hardware_pass(self):
        mutations = [
            lambda r: r.update(hardware_trial=False),
            lambda r: r.update(duration_seconds=1799),
            lambda r: r.update(duration_seconds=float("nan")),
            lambda r: r["samples"].pop(),
            lambda r: r["samples"][0].update(llm_calls=1),
            lambda r: r["samples"][0].update(llm_calls=False),
            lambda r: r["samples"][0].update(mode="bm25"),
            lambda r: r["samples"][0].update(seconds=float("inf")),
            lambda r: [s.update(seconds=6) for s in r["samples"]],
            lambda r: r["final_status"]["retrieval"]["dense_index_scopes"].remove("recipes"),
            lambda r: r["final_status"]["runtime"].update(failed=True),
            lambda r: r["final_status"]["runtime"].update(calls={}),
            lambda r: r["final_status"]["runtime"]["calls"].update(reranker=0),
            lambda r: r["final_status"].update(max_reserved_bytes=2861 * 1024**2),
            lambda r: r["final_status"].update(cgroup_peak_bytes=7 * 1024**3),
            lambda r: r["final_status"].update(disk_free_bytes=9 * 1024**3),
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(case=index):
                row = good_report()
                mutation(row)
                with self.assertRaises(ValueError):
                    trial.validate_report(row)

    def test_cli_uses_only_authenticated_readonly_probe_and_preserves_failure(self):
        class Response:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def read(self, limit):
                return b'{"llm_calls":0,"diagnostics":{"search_mode":"bm25","reranker":"rule_based"}}'
        called = []
        def open_request(request, timeout):
            called.append(request.full_url)
            self.assertEqual(timeout, 45)
            self.assertTrue(request.has_header("X-coach-service-token"))
            return Response()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            secret_file = root / "synthetic-token"
            secret_file.write_text("synthetic-test-" * 4)
            secret_file.chmod(0o600)
            destination = root / "failure.json"
            opener = NS(open=open_request)
            with patch.object(trial.urllib.request, "build_opener", return_value=opener):
                result = trial.run(secret_file, destination)
            self.assertFalse(result["passed"])
            saved = json.loads(destination.read_text())
            self.assertFalse(saved["acceptance"]["passed"])
            self.assertTrue(saved["warmups"])
            self.assertNotIn(secret_file.read_text(), destination.read_text())
            self.assertEqual(saved["acceptance"]["reason"], "PROBE_WARMUP_FAILED")
            self.assertTrue(all(url in {"http://127.0.0.1:8003/gpu/search-check", "http://127.0.0.1:8003/gpu/status"} for url in called))


class HttpBoundary(unittest.TestCase):
    def test_auth_and_failed_health(self):
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
        api = FastAPI()
        @api.get("/health")
        def health():
            return {"status": "ok"}
        gate = runtime.Gate()
        cuda = NS(max_memory_allocated=lambda: 1, max_memory_reserved=lambda: 1)
        token = "synthetic-service-token-" * 3
        runtime.attach_probe(api, NS(status=status), gate, NS(cuda=cuda), {"name": "synthetic"}, token)
        with patch.object(runtime, "disk_free", return_value=12 * 1024**3):
            with TestClient(api) as client:
                self.assertEqual(client.get("/gpu/status").status_code, 401)
                self.assertEqual(client.get("/gpu/status", headers=[(b"x-coach-service-token", b"\xff")]).status_code, 401)
                self.assertEqual(client.post("/gpu/search-check", json={}).status_code, 401)
                self.assertEqual(client.get("/gpu/status", headers={"X-Coach-Service-Token": token}).status_code, 200)
                for body in ({}, {"scope": [], "query": "synthetic"}, {"scope": "recipes", "query": ""}):
                    self.assertEqual(client.post("/gpu/search-check", json=body,
                        headers={"X-Coach-Service-Token": token}).status_code, 400)
                self.assertEqual(client.get("/health").status_code, 200)
                gate.mark_failed()
                self.assertEqual(client.get("/health").status_code, 503)


class SourceManifest(unittest.TestCase):
    def test_tampering_and_extra_secret_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"synthetic-code"
            (root / "requirements.txt").write_bytes(payload)
            manifest = {"revision": verify_source.REVISION,
                        "files": {"requirements.txt": hashlib.sha256(payload).hexdigest()}}
            raw = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
            (root / "source.json").write_bytes(raw)
            with self.assertRaisesRegex(ValueError, "AI_SOURCE_MANIFEST"):
                verify_source.verify(root)  # A self-authored manifest is not trusted.
            with patch.object(verify_source, "MANIFEST_SHA256", hashlib.sha256(raw).hexdigest()):
                self.assertEqual(verify_source.verify(root), 1)
                (root / ".env").write_text("synthetic")
                with self.assertRaisesRegex(ValueError, "AI_SOURCE_FILES"):
                    verify_source.verify(root)
                (root / ".env").unlink()
                (root / "requirements.txt").write_bytes(b"Synthetic-code")
                with self.assertRaisesRegex(ValueError, "AI_SOURCE_HASH"):
                    verify_source.verify(root)


class ComposeSafety(unittest.TestCase):
    def test_stt_overlay_only_adds_fixed_secret_and_boolean(self):
        candidate = json.loads(Path(__file__).with_name("test-compose.json").read_text())
        candidate["services"]["coach"]["environment"]["COACH_STT_ENABLED"] = "false"
        candidate["services"]["coach"]["secrets"].append(
            {"source": "google_credentials", "target": "/run/secrets/google_credentials"})
        candidate["secrets"]["google_credentials"] = {"file": "/etc/hobbytree/ai-secrets/google_credentials"}
        self.assertTrue(verify_compose.validate(candidate))
        candidate["services"]["coach"]["environment"]["COACH_STT_ENABLED"] = "true"
        self.assertTrue(verify_compose.validate(candidate))
        for mutation in (
            lambda c: c["secrets"]["google_credentials"].update(file="/tmp/arbitrary"),
            lambda c: c["secrets"]["google_credentials"].update(environment="PLAINTEXT"),
            lambda c: c["services"]["coach"]["environment"].update(COACH_STT_ENABLED="TRUE"),
            lambda c: c["services"]["coach"]["environment"].update(GOOGLE_APPLICATION_CREDENTIALS="/tmp/other"),
            lambda c: c["services"]["coach"]["secrets"][-1].update(target="/run/secrets/other"),
        ):
            changed = copy.deepcopy(candidate)
            mutation(changed)
            with self.assertRaises(AssertionError):
                verify_compose.validate(changed)

    def test_private_small_offline_profile_and_unsafe_mutations(self):
        baseline = json.loads(Path(__file__).with_name("test-compose.json").read_text())
        self.assertTrue(verify_compose.validate(baseline))
        old_renderer = copy.deepcopy(baseline)
        for volume in old_renderer["services"]["coach"]["volumes"]:
            volume["bind"].pop("create_host_path")
        self.assertTrue(verify_compose.validate(old_renderer))
        def coach(config):
            return config["services"]["coach"]
        mutations = [
            lambda c: coach(c)["ports"][0].update(host_ip="0.0.0.0"),
            lambda c: coach(c)["ports"][0].update(host_ip="8.8.8.8"),
            lambda c: coach(c)["ports"].append(dict(coach(c)["ports"][0])),
            lambda c: coach(c).update(privileged=True),
            lambda c: coach(c).update(entrypoint=["python", "-m", "uvicorn", "app.main:create_app"]),
            lambda c: coach(c).update(cap_add=["SYS_ADMIN"]),
            lambda c: coach(c).update(image="example.invalid/coach:latest"),
            lambda c: coach(c).update(cpus=4),
            lambda c: coach(c).update(memswap_limit=8 * 1024**3),
            lambda c: coach(c)["environment"].update(COACH_DEVICE="cpu"),
            lambda c: coach(c)["environment"].update(COACH_LLM_ENABLED="true"),
            lambda c: coach(c)["environment"].update(ANTHROPIC_API_KEY="synthetic"),
            lambda c: coach(c)["deploy"]["resources"]["reservations"].update(devices=[]),
            lambda c: coach(c)["volumes"][1].update(read_only=False),
            lambda c: coach(c)["volumes"][0]["bind"].update(create_host_path=True),
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(case=index):
                candidate = copy.deepcopy(baseline)
                mutation(candidate)
                with self.assertRaises(AssertionError):
                    verify_compose.validate(candidate)


if __name__ == "__main__":
    unittest.main()
