FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app && mkdir -p /var/lib/training-imports && chown app /var/lib/training-imports
USER app
COPY --from=build /app.jar /app.jar
ENV IMPORT_WORK_DIR=/var/lib/training-imports
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q UP <&3'
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
