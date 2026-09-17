# -----------------------------
# Stage 1: Build JAR
# -----------------------------
FROM gradle:9.3-jdk25 AS builder
WORKDIR /app

ENV GRADLE_USER_HOME=/home/gradle/.gradle \
    GRADLE_OPTS="-Dorg.gradle.internal.http.retry=8 -Dorg.gradle.internal.repository.max.retries=10"

# Cache dependencies
COPY gradlew .
COPY gradle gradle
COPY gradle.properties settings.gradle build.gradle ./
COPY src/main/proto src/main/proto
RUN ./gradlew dependencies --no-daemon

# Copy source and build. Retry transient Maven Central 429s.
COPY . .
RUN set -eu; \
    n=1; \
    until ./gradlew bootJar --no-daemon; do \
      n=$((n + 1)); \
      if [ "$n" -gt 5 ]; then exit 1; fi; \
      echo "Gradle failed (likely Maven Central 429). Retry $n/5 in 45s..."; \
      sleep 45; \
    done

# -----------------------------
# Stage 2: Runtime image
# -----------------------------
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
