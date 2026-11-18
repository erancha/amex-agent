# syntax=docker/dockerfile:1
# The jar is the same on every processor, so Maven runs on the building machine's own platform;
# only the runtime stage is built for the target, linux/arm64 for the EC2 instance.
FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-21 AS build
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
# Statements are never baked into the image. On this machine the statements folder is mounted
# here; on the EC2 instance a named volume is, which Docker creates with this folder's owner, so
# the agent can write the uploads into it.
RUN install -d -o agent /statements
USER agent
EXPOSE 8020
ENTRYPOINT ["java", "-jar", "/app/amex-agent.jar"]
