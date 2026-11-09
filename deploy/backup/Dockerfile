FROM timescale/timescaledb:2.17.2-pg16
RUN apk add --no-cache openssl
COPY backup.sh /usr/local/bin/backup.sh
ENTRYPOINT ["/bin/sh", "/usr/local/bin/backup.sh"]
