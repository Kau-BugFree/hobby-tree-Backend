"""Run loopback-only, no-LLM searches on an approved disposable GPU candidate."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
from pathlib import Path
import stat
import re
import time
import urllib.request

from gpu_runtime import AI_REVISION, DOCUMENTS, ready_status

QUESTIONS = [
    ("activities", "간장 드레싱 계량"), ("activities", "달걀 요리"),
    ("activities", "채소 씻기"), ("activities", "버섯 손질"),
    ("activities", "미역 불리기"), ("activities", "쌀 씻기"),
    ("activities", "주먹밥 만들기"), ("activities", "소금 계량"),
    ("activities", "두부 물기 빼기"), ("activities", "김 고명"),
    ("activities", "칼질 과일"), ("activities", "손 씻기"),
    ("knowledge", "계량스푼 큰술 작은술"), ("knowledge", "칼질 안전 주의"),
    ("knowledge", "전자레인지 달걀 주의"),
    ("recipes", "rice egg recipe"), ("recipes", "chicken soup"),
    ("recipes", "tofu salad"), ("recipes", "soy sauce dressing"),
    ("activities", "간장 드레싱 계량 " * 180),
]


def finite(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def percentile95(values):
    return sorted(values)[math.ceil(len(values) * .95) - 1]


def validate_report(report):
    """Accept only actual GPU execution evidence and the complete trial window."""
    if report.get("hardware_trial") is not True or report.get("ai_revision") != AI_REVISION:
        raise ValueError("TRIAL_IDENTITY")
    if not finite(report.get("duration_seconds")) or report["duration_seconds"] < 1800:
        raise ValueError("TRIAL_DURATION")
    samples = report["samples"]
    if len(samples) < 100 or {s["scope"] for s in samples} != set(DOCUMENTS):
        raise ValueError("TRIAL_SAMPLES")
    for sample in samples:
        if (sample["ok"] is not True or type(sample["llm_calls"]) is not int or sample["llm_calls"] != 0 or sample["mode"] != "hybrid_bm25_dense_rrf"
                or sample["reranker"] != "cross_encoder" or not finite(sample["seconds"])
                or sample["concurrency"] not in (1, 2)):
            raise ValueError("TRIAL_QUERY")
    single = [s["seconds"] for s in samples if s["concurrency"] == 1]
    parallel = [s["seconds"] for s in samples if s["concurrency"] == 2]
    if len(single) < 20 or not parallel or percentile95(single) > 5 or max(parallel) > 45:
        raise ValueError("TRIAL_LATENCY")
    status = report["final_status"]
    if status["profile"] != "g6f.large-rag-v1" or status["ai_revision"] != AI_REVISION:
        raise ValueError("TRIAL_PROFILE")
    retrieval = status["retrieval"]
    try:
        ready_status(retrieval)
    except Exception:
        raise ValueError("TRIAL_INDEX")
    state = status["runtime"]
    if (state["failed"] is not False or set(state["calls"]) != {"embedding", "reranker"}
            or any(type(v) is not int or v < len(samples) for v in state["calls"].values())):
        raise ValueError("TRIAL_EXECUTION")
    hardware = status["hardware"]
    if ("L4" not in hardware["name"] or not 2500 * 1024**2 <= hardware["total_bytes"] <= 3072 * 1024**2
            or hardware["audited_assets"] != {"files": 34, "bytes": 7852052666}):
        raise ValueError("TRIAL_HARDWARE")
    if (not finite(status["max_reserved_bytes"]) or not finite(status["max_allocated_bytes"])
            or status["max_allocated_bytes"] <= 0 or status["max_reserved_bytes"] <= 0
            or max(status["max_reserved_bytes"], status["max_allocated_bytes"]) > hardware["total_bytes"] * .85
            or type(status["cgroup_peak_bytes"]) is not int or not 0 < status["cgroup_peak_bytes"] <= 6 * 1024**3
            or type(status["disk_free_bytes"]) is not int or status["disk_free_bytes"] < 10 * 1024**3):
        raise ValueError("TRIAL_RESOURCES")
    return {"passed": True, "single_search_p95_seconds": percentile95(single),
            "samples": len(samples), "paid_calls": 0}


def run(token_file, destination):
    """Only the future approved host's loopback GPU probe is contacted."""
    info = token_file.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_size > 4096:
        raise ValueError("PROBE_TOKEN_PERMISSIONS")
    secret = token_file.read_text(encoding="utf8").strip()
    if len(secret) < 32 or destination.exists():
        raise ValueError("PROBE_INPUT")
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    def request(path, body=None):
        req = urllib.request.Request("http://127.0.0.1:8003" + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"X-Coach-Service-Token": secret, "Content-Type": "application/json"})
        with opener.open(req, timeout=45) as response:
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError("PROBE_RESPONSE_SIZE")
            return json.loads(raw)
    def sample(index, concurrency):
        scope, query = QUESTIONS[index % len(QUESTIONS)]
        started = time.monotonic()
        try:
            row = request("/gpu/search-check", {"scope": scope, "query": query})
            diagnostics = row["diagnostics"]
            return {"scope": scope, "query_sha256": hashlib.sha256(query.encode()).hexdigest(),
                    "seconds": time.monotonic() - started, "ok": True, "llm_calls": row["llm_calls"],
                    "mode": diagnostics["search_mode"], "reranker": diagnostics["reranker"], "concurrency": concurrency}
        except Exception as error:
            return {"scope": scope, "ok": False, "seconds": time.monotonic() - started,
                    "llm_calls": 0, "mode": None, "reranker": None, "concurrency": concurrency,
                    "error_type": type(error).__name__, "http_status": getattr(error, "code", None)}
    started = time.monotonic()
    report = {"hardware_trial": True, "ai_revision": AI_REVISION, "samples": []}
    try:
        report["warmups"] = [sample(index, 1) for index in (0, 12, 15)]
        if any(not s["ok"] or s["llm_calls"] != 0 or s["mode"] != "hybrid_bm25_dense_rrf"
               or s["reranker"] != "cross_encoder" for s in report["warmups"]):
            raise ValueError("PROBE_WARMUP_FAILED")
        started = time.monotonic()
        # Warmup is separate; measure a complete window on all three scopes.
        with ThreadPoolExecutor(max_workers=2) as pool:
            while len(report["samples"]) < 100 or time.monotonic() - started < 1800:
                index = len(report["samples"])
                concurrency = 1 if index < 20 else 2
                results = list(pool.map(lambda i: sample(i, concurrency), range(index, index + concurrency)))
                report["samples"].extend(results)
                if any(not row["ok"] for row in results):
                    raise ValueError("PROBE_REQUEST_FAILED")
                # Bound traffic while observing a full thirty-minute window.
                time.sleep(10)
        report["final_status"] = request("/gpu/status")
        report["duration_seconds"] = time.monotonic() - started
        report["acceptance"] = validate_report(report)
    except Exception as error:
        report["duration_seconds"] = time.monotonic() - started
        reason = str(error) if isinstance(error, ValueError) else "GPU_TRIAL_FAILED"
        if re.fullmatch(r"(?:TRIAL|PROBE)_[A-Z_]+", reason) is None:
            reason = "GPU_TRIAL_FAILED"
        report["acceptance"] = {"passed": False, "reason": reason, "error_type": type(error).__name__}
        if "final_status" not in report:
            try:
                report["final_status"] = request("/gpu/status")
            except Exception as status_error:
                report["status_error_type"] = type(status_error).__name__
    # File contains only synthetic query hashes, diagnostic counters and timings.
    with destination.open("x", encoding="utf8") as stream:
        json.dump(report, stream, indent=2)
    return report["acceptance"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("token_file", type=Path)
    parser.add_argument("report_file", type=Path)
    args = parser.parse_args()
    result = run(args.token_file, args.report_file)
    print(json.dumps(result))
    raise SystemExit(0 if result["passed"] else 1)
