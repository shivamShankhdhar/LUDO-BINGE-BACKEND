# syntax=docker/dockerfile:1

# Stage 1: Build the Spring Boot application
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy Maven wrapper files and pom.xml first for dependency caching
COPY mvnw pom.xml ./
COPY .mvn .mvn

RUN chmod +x mvnw
# Pre-fetch dependencies if possible
RUN ./mvnw dependency:go-offline -B || true

# Copy source code and build the production jar
COPY src src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Production runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Run as non-root user
RUN groupadd -r spring && useradd -r -g spring spring

COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

COPY --from=build /workspace/target/LudoBingeServer-*.jar app.jar
RUN chown -R spring:spring /app

USER spring:spring

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
