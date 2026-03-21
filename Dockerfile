FROM openjdk:17-jdk-slim

WORKDIR /app

COPY target/reportAi-0.0.1-SNAPSHOT.jar reportai.jar

EXPOSE 8080

ENV SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/vector_db
ENV SPRING_DATASOURCE_USERNAME=postgres
ENV SPRING_DATASOURCE_PASSWORD=root
ENV SPRING_AI_ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
ENV SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
ENV APP_STORAGE_ROOT=/app/data/uploads
ENV APP_STORAGE_TEMP_ROOT=/app/data/temp
ENV APP_STORAGE_TEMPLATES_ROOT=/app/data/templates
ENV APP_REPORTS_ROOT=/app/data/reports

RUN mkdir -p /app/data/uploads /app/data/temp /app/data/templates /app/data/reports

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "reportai.jar"]
