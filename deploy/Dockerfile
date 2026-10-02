FROM node:24.19.0-bookworm-slim AS node
FROM eclipse-temurin:25-jre-noble
ARG COMPONENT
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
WORKDIR /app
COPY components/${COMPONENT}/build/libs/app.jar /app/app.jar
COPY components/script-runner/sandbox.mjs /app/components/script-runner/sandbox.mjs
USER 65532:65532
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
