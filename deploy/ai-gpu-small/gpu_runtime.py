"""Pinned RAG-only GPU profile. No model substitution or silent CPU fallback."""
from contextlib import contextmanager
import hashlib
import hmac
import json
from pathlib import Path
import threading

AI_REVISION = "9364bface2638fb62c54edef6782ea58d5d73df9"
DOCUMENTS = {"activities": 162, "knowledge": 486, "recipes": 544888}


class ProfileError(RuntimeError):
    """A fixed diagnostic code; never include upstream exception bodies."""


def require(value, code):
    if not value:
        raise ProfileError(code)


def audit_assets(manifest, models, index):
    """Read and hash pinned assets; never modify or rebuild an index."""
    expected_models = set()
    seen = set()
    total = 0
    for row in manifest:
        relative = row["Path"]
        require(relative not in seen, "ASSET_DUPLICATE")
        seen.add(relative)
        if relative.startswith("models/"):
            root, suffix = Path(models), relative[len("models/"):]
            expected_models.add(suffix)
        else:
            require(relative.startswith("backend/var/index/"), "ASSET_PATH")
            root, suffix = Path(index), relative[len("backend/var/index/"):]
        require(suffix and all(p not in ("", ".", "..") for p in suffix.split("/")), "ASSET_PATH")
        require("\\" not in suffix and not Path(suffix).is_absolute(), "ASSET_PATH")
        path = root / suffix
        require(not root.is_symlink(), "ASSET_SYMLINK")
        for part in Path(suffix).parts:
            root = root / part
            require(not root.is_symlink(), "ASSET_SYMLINK")
        require(path.is_file() and path.stat().st_size == row["Bytes"], "ASSET_SIZE")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        require(digest.hexdigest() == row["Sha256"], "ASSET_HASH")
        total += row["Bytes"]
    actual_models = {p.relative_to(models).as_posix() for p in Path(models).rglob("*") if p.is_file()}
    require(actual_models == expected_models, "ASSET_EXTRA_MODEL_FILES")
    return {"files": len(seen), "bytes": total}


def cuda_info(torch):
    """Prove actual CUDA availability and a real tensor operation before startup."""
    require(torch.cuda.is_available(), "GPU_UNAVAILABLE")
    require(torch.cuda.device_count() == 1, "GPU_COUNT")
    props = torch.cuda.get_device_properties(0)
    require("L4" in props.name and 2500 * 1024**2 <= props.total_memory <= 3072 * 1024**2, "GPU_PROFILE")
    value = torch.ones(1, device="cuda", dtype=torch.float16)
    require(value.device.type == "cuda" and float((value + value).item()) == 2.0, "GPU_OPERATION")
    torch.cuda.synchronize()
    return {"name": props.name, "total_bytes": props.total_memory}


def model_cuda(model, reranker=False):
    """Check real parameter placement, not only a requested device string."""
    module = model.model if reranker else model
    parameters = list(module.parameters())
    require(parameters and all(p.device.type == "cuda" for p in parameters), "MODEL_NOT_CUDA")
    require(all(str(p.dtype) == "torch.float16" for p in parameters if p.is_floating_point()), "MODEL_NOT_FP16")


class Gate:
    """One reentrant GPU/search gate shared by both models and all requests."""

    def __init__(self, timeout=10, synchronize=lambda: None):
        self.lock = threading.RLock()
        self.state_lock = threading.Lock()
        self.timeout = timeout
        self.synchronize = synchronize
        self.failed = False
        self.calls = {"embedding": 0, "reranker": 0}

    @contextmanager
    def enter(self):
        require(self.lock.acquire(timeout=self.timeout), "GPU_BUSY")
        try:
            require(not self.failed, "GPU_INFERENCE_FAILED")
            yield
        finally:
            self.lock.release()

    def mark_failed(self):
        with self.state_lock:
            self.failed = True

    def success(self, kind):
        with self.state_lock:
            self.calls[kind] += 1

    def snapshot(self):
        with self.state_lock:
            return {"failed": self.failed, "calls": dict(self.calls)}


class GuardedModel:
    """Preserve upstream fingerprint attributes while serializing model work."""

    def __init__(self, source, gate, kind):
        self.source, self.gate, self.kind = source, gate, kind

    def __getattr__(self, name):
        return getattr(self.source, name)

    def load(self):
        with self.gate.enter():
            try:
                result = self.source.load()
                self.gate.synchronize()
                model_cuda(result, self.kind == "reranker")
                return result
            except Exception:
                self.gate.mark_failed()
                raise ProfileError("GPU_MODEL_LOAD_FAILED") from None

    def invoke(self, method, *args):
        with self.gate.enter():
            try:
                result = getattr(self.source, method)(*args)
                self.gate.synchronize()
                self.gate.success(self.kind)
                return result
            except Exception:
                self.gate.mark_failed()
                raise ProfileError("GPU_INFERENCE_FAILED") from None

    def encode_query(self, text):
        return self.invoke("encode_query", text)

    def encode_documents(self, texts):
        return self.invoke("encode_documents", texts)

    def score(self, query, documents):
        return self.invoke("score", query, documents)


def ready_status(status):
    require(status["documents"] == DOCUMENTS, "INDEX_COUNTS")
    scopes = set(status["dense_index_scopes"])
    if scopes != set(DOCUMENTS):
        missing = sorted(set(DOCUMENTS) - scopes)
        raise ProfileError("INDEX_DENSE_SCOPES_" + ("_".join(missing).upper() or "UNEXPECTED"))
    require(status["embedding_enabled"] and status["reranker_enabled"], "MODELS_DISABLED")
    require(status["embedding_model"] == "BGE-m3-ko" and status["reranker_model"] == "KURE-Reranker-nano", "MODEL_NAMES")
    require(not status["embedding_error"] and not status["reranker_error"] and not status["index_errors"], "INDEX_OR_MODEL_FAILURE")


class StrictRetrieval:
    """Reject silent per-query BM25/rule fallback in this GPU trial profile."""

    def __init__(self, source, gate):
        self.source, self.gate = source, gate

    def __getattr__(self, name):
        return getattr(self.source, name)

    def search(self, *args, **kwargs):
        with self.gate.enter():
            before = self.gate.snapshot()["calls"]
            result = self.source.search(*args, **kwargs)
            after = self.gate.snapshot()
            require(not after["failed"], "GPU_INFERENCE_FAILED")
            diagnostics = result["diagnostics"]
            # Legitimate empty/browse requests do not promise model execution.
            if diagnostics.get("ranking"):
                require(diagnostics.get("search_mode") == "hybrid_bm25_dense_rrf"
                        and diagnostics.get("reranker") == "cross_encoder"
                        and not diagnostics.get("embedding_error") and not diagnostics.get("reranker_error"), "GPU_QUERY_FALLBACK")
                require(all(after["calls"][key] > before[key] for key in before), "GPU_QUERY_NOT_EXECUTED")
            return result

    def list_activities(self, query="", hobby_key="cooking", limit=20):
        if query.strip():
            return self.search(query, hobby_key=hobby_key, limit=limit)["items"]
        return self.source.list_activities(query, hobby_key, limit)


def build_retrieval(settings, encoder_type, reranker_type, retrieval_type, synchronize=lambda: None):
    """Inject existing upstream constructors; preserve dtype/max_length/candidates."""
    gate = Gate(synchronize=synchronize)
    encoder = GuardedModel(encoder_type(settings.embedding_model_path, "cuda", batch_size=1), gate, "embedding")
    reranker = GuardedModel(reranker_type(settings.reranker_model_path, "cuda", batch_size=1), gate, "reranker")
    source = retrieval_type(settings, encoder=encoder, reranker=reranker)
    ready_status(source.status())
    require(not gate.snapshot()["failed"], "GPU_MODEL_LOAD_FAILED")
    return StrictRetrieval(source, gate), gate


def attach_probe(api, retrieval, gate, torch, hardware, service_token):
    """Private authenticated diagnostics use only retrieval, never an LLM."""
    from fastapi import Request
    from fastapi.responses import JSONResponse

    @api.exception_handler(ProfileError)
    async def profile_error(_request, error):
        return JSONResponse(status_code=503, content={"detail": str(error)})

    @api.middleware("http")
    async def failed_health(request, call_next):
        if request.url.path == "/health" and gate.snapshot()["failed"]:
            return JSONResponse(status_code=503, content={"status": "error", "detail": "GPU_INFERENCE_FAILED"})
        return await call_next(request)

    def authorized(request):
        supplied = request.headers.get("X-Coach-Service-Token", "")
        return bool(service_token) and hmac.compare_digest(supplied.encode("utf8"), service_token.encode("utf8"))

    @api.get("/gpu/status")
    def gpu_status(request: Request):
        if not authorized(request):
            return JSONResponse(status_code=401, content={"detail": "GPU_PROBE_AUTH"})
        state = gate.snapshot()
        return {"profile": "g6f.large-rag-v1", "ai_revision": AI_REVISION, "hardware": hardware,
                "runtime": state, "retrieval": retrieval.status(),
                "max_allocated_bytes": torch.cuda.max_memory_allocated(),
                "max_reserved_bytes": torch.cuda.max_memory_reserved(),
                "cgroup_peak_bytes": cgroup_peak(),
                "disk_free_bytes": disk_free(settings_path="/state")}

    @api.post("/gpu/search-check")
    async def search_check(request: Request):
        if not authorized(request):
            return JSONResponse(status_code=401, content={"detail": "GPU_PROBE_AUTH"})
        try:
            body = await request.json()
        except ValueError:
            return JSONResponse(status_code=400, content={"detail": "GPU_PROBE_INPUT"})
        if (not isinstance(body, dict) or set(body) != {"scope", "query"}
                or not isinstance(body["scope"], str) or body["scope"] not in DOCUMENTS
                or not isinstance(body["query"], str) or not 0 < len(body["query"]) <= 2000):
            return JSONResponse(status_code=400, content={"detail": "GPU_PROBE_INPUT"})
        import asyncio
        # The gate stays held by the worker until its actual work ends, including
        # when an HTTP caller disconnects or its wait times out.
        result = await asyncio.to_thread(retrieval.search, body["query"], scope=body["scope"])
        return {"llm_calls": 0, "diagnostics": result["diagnostics"],
                "activity_ids": [item["activity_id"] for item in result["items"]]}
    return api


def cgroup_peak():
    path = Path("/sys/fs/cgroup/memory.peak")
    return int(path.read_text()) if path.exists() else None


def disk_free(settings_path):
    import shutil
    return shutil.disk_usage(settings_path).free


def create_app():
    """Production factory has no fake-hardware or skip-integrity switch."""
    import torch
    from app.config import Settings
    from app.encoder import SentenceEncoder
    from app.reranker import CrossEncoderReranker
    from app.retrieval import Retrieval
    from app.main import create_app as upstream_app

    settings = Settings.from_env()
    require(settings.device == "cuda" and settings.embedding_enabled and settings.reranker_enabled, "GPU_SETTINGS")
    hardware = cuda_info(torch)
    manifest = json.loads(Path("/service/assets-manifest.json").read_text())
    audited = audit_assets(manifest, Path("/models"), settings.index_dir)
    require(audited == {"files": 34, "bytes": 7852052666}, "ASSET_MANIFEST")
    hardware["audited_assets"] = audited
    retrieval, gate = build_retrieval(settings, SentenceEncoder, CrossEncoderReranker, Retrieval,
                                    synchronize=torch.cuda.synchronize)
    api = upstream_app(settings=settings, retrieval=retrieval)
    return attach_probe(api, retrieval, gate, torch, hardware, settings.backend_token)
