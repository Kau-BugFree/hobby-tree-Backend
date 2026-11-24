# syntax=docker/dockerfile:1
FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b
LABEL org.opencontainers.image.source="https://github.com/Kau-BugFree/hobby-tree-AI" \
      org.opencontainers.image.revision="9364bface2638fb62c54edef6782ea58d5d73df9"
WORKDIR /service
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 HF_HOME=/tmp/huggingface \
    COACH_DEVICE=cuda COACH_EMBEDDING_ENABLED=true COACH_RERANKER_ENABLED=true \
    COACH_DATABASE_PATH=/state/coach.sqlite COACH_INDEX_DIR=/state/index \
    COACH_EMBEDDING_MODEL_PATH=/models/embedding/BGE-m3-ko \
    COACH_RERANKER_MODEL_PATH=/models/reranker/KURE-Reranker-nano \
    OMP_NUM_THREADS=1 MKL_NUM_THREADS=1 COACH_LLM_ENABLED=false
# Named context ai is a fresh canonical export from prepare_source.py, never a working tree.
COPY --from=ai . ./
COPY deploy/ai-gpu-small/verify_source.py /verify_source.py
RUN python /verify_source.py /service \
    && pip install --no-cache-dir -r /service/requirements.txt --extra-index-url https://download.pytorch.org/whl/cu128 \
    && pip check \
    && groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home app
# Patch the OS library without changing the pinned AI Python/model fingerprint.
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade \
        zlib1g=1:1.3.dfsg+really1.3.1-1+deb13u1 \
        libc6=2.41-12+deb13u5 libc-bin=2.41-12+deb13u5 \
        openssl=3.5.9-1~deb13u1 libssl3t64=3.5.9-1~deb13u1 openssl-provider-legacy=3.5.9-1~deb13u1 \
    && rm -rf /var/lib/apt/lists/* \
    && find /usr /bin /sbin -xdev -type f -perm /6000 -exec chmod a-s {} + \
    && rm -f /usr/bin/infocmp
COPY deploy/ai-server/coach-entrypoint.py ./approved_launcher.py
COPY --from=stt . /service/stt_vendor/
COPY deploy/ai-gpu-small/prepare_stt_source.py /verify_stt_source.py
RUN python /verify_stt_source.py /service/stt_vendor \
    && pip install --no-cache-dir google-auth==2.63.1 && pip check
COPY deploy/ai-gpu-small/stt_config.py /service/stt_vendor/config.py
COPY deploy/ai-gpu-small/stt_runtime.py ./
COPY deploy/ai-server/assets-manifest.json ./assets-manifest.json
COPY deploy/ai-gpu-small/gpu_runtime.py deploy/ai-gpu-small/gpu_launcher.py deploy/ai-gpu-small/check_no_gpu.py ./
COPY deploy/ai-gpu-small/check_image_security.py ./
RUN --mount=type=bind,source=deploy/ai-gpu-small/prepare_trial_index.py,target=/tmp/prepare_trial_index.py \
    python /tmp/prepare_trial_index.py
USER 10001:10001
EXPOSE 8003
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8003/health',timeout=8).read()"]
ENTRYPOINT ["python", "/service/gpu_launcher.py"]
