# Build + runtime image for the Streamable HTTP MCP transport.
#
# Two-stage build: Maven container compiles the project and produces the
# Quarkus fast-jar layout under server-http/target/quarkus-app; the runtime
# stage copies that into a JRE-only image.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Copy everything in. For incremental rebuild speed, this Dockerfile prioritises
# correctness over layer caching; if build times become an issue, split the
# POM-only copy + dependency resolution into a separate cached layer.
COPY pom.xml ./
COPY core/ ./core/
COPY server-stdio/pom.xml ./server-stdio/
COPY server-http/ ./server-http/

# Build only what we need; -am pulls core in as a dependency.
RUN mvn -B -pl server-http -am package -DskipTests

# --------------------------------------------------------------------------

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Quarkus fast-jar layout — copy the WHOLE quarkus-app directory; the runner
# expects lib/, app/, and quarkus/ siblings of quarkus-run.jar.
COPY --from=build /src/server-http/target/quarkus-app /app

EXPOSE 8080

# Defaults; override via compose / docker run -e.
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=8080 \
    INGEST_DEFAULT_VISUAL_INDEX=true

HEALTHCHECK --interval=20s --timeout=4s --start-period=30s --retries=3 \
    CMD wget --spider -q http://localhost:8080/q/health || exit 1

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
