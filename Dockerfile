# Stage 1: Build Spring Boot application
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd
COPY src src

RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the built JAR from builder
COPY --from=builder /app/target/*.jar app.jar

# Create data directory for uploads and logo
RUN mkdir -p /app/data/uploads

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
