FROM python:3.12-slim
RUN useradd --system --uid 10001 app
WORKDIR /app
# Pinned versions (requirements.txt): the Garmin library uses unofficial endpoints, so it is
# updated deliberately, never by a rebuild (ADR 0011 D11).
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY garmin_sync garmin_sync
USER app
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health')"
CMD ["python", "-m", "garmin_sync.server"]
