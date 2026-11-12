FROM python:3.12-slim@sha256:4840d514c583a4d3d8cad3940e68dee6f0f6f3635c12fcd0a3d74c21a5b93a54 AS basic
LABEL org.opencontainers.image.source="https://github.com/Kau-BugFree/hobby-tree-AI" \
      org.opencontainers.image.revision="9364bface2638fb62c54edef6782ea58d5d73df9"
WORKDIR /service
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
    COACH_DEVICE=cpu COACH_EMBEDDING_ENABLED=false COACH_RERANKER_ENABLED=false \
    COACH_DATABASE_PATH=/state/coach.sqlite COACH_INDEX_DIR=/state/index \
    COACH_EMBEDDING_MODEL_PATH=/models/embedding/BGE-m3-ko \
    COACH_RERANKER_MODEL_PATH=/models/reranker/KURE-Reranker-nano \
    OMP_NUM_THREADS=2 MKL_NUM_THREADS=2 HF_HOME=/tmp/huggingface
# Build context is the pinned AI src/activity-coach/backend directory.
# Only reviewed source and catalog files enter the image; no .env, models or state.
COPY requirements.txt /tmp/upstream-requirements.txt
RUN sed '/^torch==/d; /^sentence-transformers==/d; /^transformers==/d' /tmp/upstream-requirements.txt > /tmp/basic-requirements.txt \
    && pip install --no-cache-dir -r /tmp/basic-requirements.txt \
    && groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home app \
    && mkdir /state && chown 10001:10001 /state
COPY app ./app
COPY data/*.json data/*.jsonl ./data/
USER 10001:10001
EXPOSE 8003
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8003/health', timeout=8).read()"]
CMD ["python", "-m", "uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8003", "--workers", "1", "--no-access-log"]

FROM basic AS cpu
USER root
# Only the CUDA wheel changes. CPU recipe dense search is deliberately unavailable.
RUN pip install --no-cache-dir torch==2.11.0+cpu --index-url https://download.pytorch.org/whl/cpu \
    && sed 's/torch==2.11.0+cu128/torch==2.11.0+cpu/' /tmp/upstream-requirements.txt > /tmp/cpu-requirements.txt \
    && pip install --no-cache-dir -r /tmp/cpu-requirements.txt \
    && pip check
ENV COACH_EMBEDDING_ENABLED=true COACH_RERANKER_ENABLED=true
USER 10001:10001
