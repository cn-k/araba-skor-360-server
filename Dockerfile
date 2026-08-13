FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/araba-skor-360-server ./

# Railway injects PORT at runtime; AppConfig falls back to 7070 if unset (e.g. local docker run).
CMD ["./bin/araba-skor-360-server"]
