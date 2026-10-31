FROM maven:3.9.15-eclipse-temurin-21 AS build
ARG MAVEN_MIRROR_URL=https://repo1.maven.org/maven2
WORKDIR /build
COPY pom.xml .
COPY maven-settings.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -s maven-settings.xml -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /data/files /data/tmp && chown -R app:app /data
WORKDIR /app
COPY --from=build --chown=app:app /build/target/app.jar ./app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
