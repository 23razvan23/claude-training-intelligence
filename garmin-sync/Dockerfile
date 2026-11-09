FROM python:3.12-slim
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY garmin_sync garmin_sync
USER app
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health')"
CMD ["python", "-m", "garmin_sync.server"]
