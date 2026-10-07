# --- build stage -------------------------------------------------------------
FROM gradle:8.14-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle ./
# 의존성 레이어 캐시
RUN gradle dependencies --no-daemon -q > /dev/null 2>&1 || true
COPY src ./src
RUN gradle bootJar -x test --no-daemon

# --- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home ledgerguard
COPY --from=build /app/build/libs/*.jar app.jar
USER ledgerguard
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
