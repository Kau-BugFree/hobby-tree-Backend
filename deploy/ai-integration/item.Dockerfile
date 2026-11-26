FROM python:3.11-slim-bookworm@sha256:ef56ae98435cc40d8a27020b0b32a9941515acd5840f0fba34858bb34dd362d1 AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY --from=items . /app/
RUN python -m pip install --no-cache-dir setuptools==80.9.0 \
    && python -m pip install --no-cache-dir --no-build-isolation . psycopg[binary]==3.2.13
COPY deploy/ai-integration/entrypoint.py deploy/ai-integration/import_export.py /integration/
LABEL org.opencontainers.image.source="https://github.com/Kau-BugFree/hobby-tree-AI" \
      org.opencontainers.image.revision="0920a3ada3b50700af61bf792c859c3606a4a1be"
USER 10001:10001
ENTRYPOINT ["python", "/integration/entrypoint.py"]
CMD ["api"]

FROM runtime AS tests
USER 0:0
RUN python -m pip install --no-cache-dir pytest==9.2.0
USER 10001:10001

FROM runtime AS production
