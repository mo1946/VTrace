FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 vtrace && useradd --uid 10001 --gid vtrace --create-home vtrace
COPY --from=build /app/target/business-backend-0.1.0-SNAPSHOT.jar app.jar
USER vtrace
ENV VTRACE_BIND_ADDRESS=0.0.0.0
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
