# syntax=docker/dockerfile:1
# ---------- Etapa de build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 1) Wrapper + archivos de build primero: la descarga de dependencias queda en cache
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
 && ./gradlew --no-daemon dependencies > /dev/null

# 2) Codigo fuente (cambia seguido) despues
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
 && cp build/libs/*-SNAPSHOT.jar app.jar

# ---------- Etapa final ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
