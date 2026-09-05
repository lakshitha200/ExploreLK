# ═══════════════════════════════════════════════════════════════════════════
# Auth Service
#
# Multi-stage: the build stage carries a JDK, Maven and the whole dependency
# cache — around 700 MB that has no business being in a running container. Only
# the exploded application is copied into the runtime stage.
# ═══════════════════════════════════════════════════════════════════════════

# ── Build ──────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Dependencies first, in their own layer. pom.xml changes rarely and source
# changes constantly, so this cache survives almost every rebuild — without the
# split, every one-line code change re-downloads the internet.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Unpack the fat jar. Spring Boot's layered format separates dependencies (which
# almost never change) from application classes (which change every build), so
# the runtime image below re-layers only what actually moved.
#
# Boot 4's `tools` jarmode does NOT produce the old `layertools` layout. There is
# no exploded BOOT-INF and no loader layer to launch through: `dependencies/`
# holds `lib/*.jar` and `application/` holds an ordinary executable jar whose
# manifest Class-Path points at `lib/`. Copying them into the same directory and
# running `java -jar` is the whole mechanism — an ENTRYPOINT of
# `org.springframework.boot.loader.launch.JarLauncher` compiles a perfectly good
# image that dies at startup with ClassNotFoundException.
#
# Two housekeeping steps follow the extraction, and they cannot be commented
# inline — a `#` inside a line-continued RUN is a parse error, not a comment:
#   * the application jar is renamed to a stable `app.jar`, so the ENTRYPOINT
#     does not carry a version number that needs editing at every release;
#   * `snapshot-dependencies/` is created because this project has none, which
#     makes the directory absent rather than empty, and COPY fails on a source
#     that does not exist.
RUN java -Djarmode=tools -jar target/auth-service-*.jar extract --layers --destination extracted \
    && mv extracted/application/*.jar extracted/application/app.jar \
    && mkdir -p extracted/snapshot-dependencies/lib


# ── Runtime ────────────────────────────────────────────────────────────────
# JRE, not JDK: no compiler, no javadoc tooling, nothing a running service needs.
FROM eclipse-temurin:17-jre AS runtime

# curl for the healthcheck below. The image has no shell utilities otherwise.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never root. A container escape from a root process is a host compromise; from
# an unprivileged one it is a much smaller problem.
RUN groupadd --system explorelk && useradd --system --gid explorelk explorelk

WORKDIR /app

# Least volatile first. Third-party jars change when the pom does — rarely — so
# this layer is reused across almost every rebuild; the application jar below is
# a few hundred KB and changes every time.
COPY --from=build --chown=explorelk:explorelk /build/extracted/dependencies/ ./
COPY --from=build --chown=explorelk:explorelk /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=explorelk:explorelk /build/extracted/application/ ./

USER explorelk

# NOTE: the RSA keypair is NOT in this image. `keys/` is git-ignored and
# .dockerignore keeps it out of the build context; the container reads
# JWT_PRIVATE_KEY / JWT_PUBLIC_KEY, which compose supplies from a mounted volume
# in development and a secret store in production. A signing key baked into an
# image is a signing key in every registry that image is ever pushed to.
EXPOSE 8081

# Readiness, not liveness: this is the probe that answers "can it serve traffic".
# The group is readinessState + db. Redis is excluded on purpose — the rate
# limiter fails open and the lockout lives in Postgres, so a restarting cache
# must not take the whole platform's login out of rotation.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8081/actuator/health/readiness || exit 1

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes its heap from the
# container's cgroup limit, so changing the compose memory limit is enough and
# there is no second number to forget.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Resolves lib/ relative to the jar, which is why WORKDIR and the COPYs above
# put them side by side.
ENTRYPOINT ["java", "-jar", "app.jar"]
