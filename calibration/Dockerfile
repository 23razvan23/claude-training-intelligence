FROM python:3.12-slim
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY pyproject.toml ./
COPY calibration calibration
RUN pip install --no-cache-dir . && mkdir -p /tmp/mpl && chown app /tmp/mpl
ENV MPLCONFIGDIR=/tmp/mpl
USER app
ENTRYPOINT ["python", "-m", "calibration"]
