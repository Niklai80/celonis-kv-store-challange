# --- build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build

# Maven wrapper isn't checked in; use the JDK image's own toolchain via a pinned Maven install
# instead of requiring internet access to Maven Central for the wrapper jar on every build.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/* \
    && mvn -q -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests \
    && cp target/kvstore-*.jar target/app.jar

# --- runtime stage -------------------------------------------------------------
# JRE only, not the JDK - this container never compiles anything.
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system kvstore && useradd --system --gid kvstore --no-create-home kvstore
COPY --from=build /build/target/app.jar /app/app.jar
RUN chown -R kvstore:kvstore /app
USER kvstore

EXPOSE 8080

# MaxRAMPercentage, not a fixed -Xmx: the container's memory limit (set by Kubernetes resources.limits
# or `docker run -m`) is what should determine the heap, not a number baked into the image - a
# fixed -Xmx either wastes memory on a generously-limited pod or OOM-kills a tightly-limited one.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
