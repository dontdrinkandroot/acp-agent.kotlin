# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /src

COPY gradlew gradlew.bat ./
COPY gradle/wrapper/ ./gradle/wrapper/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src/ ./src/

# Cache mounts persist the Gradle caches (wrapper dist, dependencies, Kotlin
# compiler output) in the BuildKit store on the host, so dependency downloads
# and compilation are incremental across rebuilds (CI and local builds alike).
# --no-daemon keeps the throwaway builder from leaving a lingering daemon.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --quiet installDist

# Runtime base: the generic toolchain image from dontdrinkandroot/dev.docker
# (user `dev`, home /home/dev, XDG env, /workspace, git defaults). It carries
# OpenJDK 25 (the generated launcher resolves `java`) and the full toolchain the
# agent's `bash` tool needs to build/test real projects. User, env and workdir
# are inherited from it — this stage only adds the agent and its git identity.
FROM ghcr.io/dontdrinkandroot/dev:latest

USER root

RUN printf '%s\n' '[user]' '	name = acp-agent' '	email = acp-agent@acp-agent.docker' >> /etc/gitconfig

COPY --from=builder /src/build/install/acp-agent.kotlin /opt/acp-agent

USER dev

ENTRYPOINT ["/opt/acp-agent/bin/acp-agent.kotlin"]
CMD []