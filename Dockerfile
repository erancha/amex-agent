# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
# Dependencies land in the BuildKit cache mount, which outlives builds but is not an image layer,
# so source edits do not re-download them.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin agent
WORKDIR /app
COPY --from=build /app/target/amex-agent.jar /app/amex-agent.jar
# The statements folder is a bind mount supplied at run time (APP_STATEMENTS_DIR); statements
# are never baked into the image.
USER agent
EXPOSE 8020
ENTRYPOINT ["java", "-jar", "/app/amex-agent.jar"]
