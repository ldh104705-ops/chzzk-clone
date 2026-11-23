# syntax=docker/dockerfile:1
# ---- build stage: compile and package the Spring Boot jar with JDK 25 ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Dependencies first (cached until the build files change), then the sources.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
 && cp "build/libs/$(ls build/libs | grep -v -- '-plain' | head -n 1)" /workspace/app.jar

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app/uploads && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER app

# Size the heap from the container's memory limit instead of a fixed -Xmx.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# PORT is read by application.properties (server.port=${PORT:8080}), so Railway's injected PORT just works.
ENV PORT=8080
EXPOSE 8080
# exec so the JVM is PID 1 and receives SIGTERM (graceful shutdown on scale-in / rolling update).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
