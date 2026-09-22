# Builds any of the three services: docker build --build-arg MODULE=cake-service .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /workspace
COPY pom.xml .
COPY party-service/pom.xml party-service/
COPY cake-service/pom.xml cake-service/
COPY invitation-service/pom.xml invitation-service/
RUN mvn -q -B -pl ${MODULE} -am dependency:go-offline
COPY . .
RUN mvn -q -B -pl ${MODULE} -am -DskipTests package
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
