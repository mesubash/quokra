# Quokra: the server with the web UI (chat panel, console, sdk.js) built in. One image, one process.
#
#   docker build -t quokra .
#   docker run -p 8080:8080 -e SPRING_DATASOURCE_URL=... quokra
#
# See docs/DEPLOY.md for the settings.

# 1. The web UI. Vite writes it into the server's static resources.
FROM node:22-alpine AS web
RUN corepack enable && corepack prepare pnpm@10.12.4 --activate
WORKDIR /app/web
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

# 2. The server, with the UI inside the jar. Tests run in development, not here.
FROM eclipse-temurin:21-jdk-alpine AS server
WORKDIR /app/server
COPY server/.mvn .mvn
COPY server/mvnw server/pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY server/src src
COPY --from=web /app/server/src/main/resources/static src/main/resources/static
RUN ./mvnw -q -B -DskipTests package && cp target/*.jar /app/quokra.jar

# 3. What runs: a JRE and the jar, as a non-root user.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S quokra && adduser -S quokra -G quokra \
  && mkdir -p /data/attachments && chown -R quokra:quokra /data
WORKDIR /app
COPY --from=server /app/quokra.jar quokra.jar
USER quokra
EXPOSE 8080
# Logs as JSON lines (Elastic Common Schema), one per event; set to empty for plain text.
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
# Images sent in chats, when stored on disk. Mount a volume here to keep them.
ENV QUOKRA_ATTACHMENTS_DIR=/data/attachments
VOLUME /data
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
# Memory and other JVM settings; override for small hosts (e.g. 512 MB: see docs/guides/09-free-demo.md).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/quokra.jar"]
