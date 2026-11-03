# Importador geográfico do Dono da Vez.
FROM python:3.12.12-slim-bookworm

# osmium-tool: recorte do extrato pelo polígono da região sem carregar o arquivo inteiro.
RUN apt-get update \
 && apt-get install -y --no-install-recommends osmium-tool ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 importer
WORKDIR /app

COPY pyproject.toml ./
COPY dv_importer ./dv_importer
RUN pip install --no-cache-dir ".[dev]"

COPY fixtures ./fixtures
COPY regions ./regions
COPY tests ./tests

USER importer
ENV PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "-m", "dv_importer"]
CMD ["--help"]
