# Пульт демо-сценариев: всё, что нужно scripts/demo/run.sh (bash, curl, jq, python3, shasum, git, docker CLI, Java 21+
# для генератора истории в сценарии analytics). Репозиторий и сокет Docker монтируются в compose.
FROM docker:27-cli AS docker-cli

FROM eclipse-temurin:25-jdk
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash curl jq python3 perl git tzdata ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && git config --system --add safe.directory /repo
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
WORKDIR /app
COPY runner.py .
ENV PYTHONUNBUFFERED=1 REPO=/repo PORT=8099
EXPOSE 8099
CMD ["python3", "-u", "runner.py"]
