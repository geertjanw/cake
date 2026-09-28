# Builds any of the three services: docker build --build-arg MODULE=cake-service .

# The Java constants for our own semantic conventions are generated from semconv/registry.
# That happens in its own stage, on the Weaver image itself: the Maven build normally shells
# out to `docker run otel/weaver`, and there is no Docker inside a Docker build. The output
# is handed to the Maven stage, which then builds with -Dweaver.skip=true.
FROM otel/weaver:v0.26.1 AS semconv
# The image ships the binary at /weaver/weaver and runs as an unprivileged user whose home
# is not where we copy the registry, hence the explicit path and the root switch.
USER root
WORKDIR /w
COPY semconv semconv
RUN /weaver/weaver registry check --registry semconv/registry --quiet \
 && /weaver/weaver registry generate \
      --registry semconv/registry \
      --templates semconv/templates \
      --quiet \
      java /out

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /workspace
COPY pom.xml .
COPY cake-semconv/pom.xml cake-semconv/
COPY party-service/pom.xml party-service/
COPY cake-service/pom.xml cake-service/
COPY invitation-service/pom.xml invitation-service/
RUN mvn -q -B -pl ${MODULE} -am -Dweaver.skip=true dependency:go-offline
COPY . .
COPY --from=semconv /out cake-semconv/target/generated-sources/weaver/com/cakeandcandles/semconv/
RUN mvn -q -B -pl ${MODULE} -am -Dweaver.skip=true -DskipTests package
# The OpenTelemetry Java agent gives us HTTP, JDBC, scheduling and log
# instrumentation with zero code changes.
RUN curl -sSL -o /workspace/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /workspace/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /workspace/${MODULE}/target/${MODULE}-*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
