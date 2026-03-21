#!/bin/bash

# ReportAI - Build and Deploy Script
# Usage: ./build_and_deploy.sh [build|run|test|docker]

set -e

PROJECT_NAME="reportAi"
VERSION="1.2.0"
JAR_NAME="reportAi-0.0.1-SNAPSHOT.jar"
PORT="8080"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

print_header() {
    echo -e "${YELLOW}======================================${NC}"
    echo -e "${YELLOW}  $1${NC}"
    echo -e "${YELLOW}======================================${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

# Function: Clean
clean() {
    print_header "Cleaning Project"
    mvn clean -q
    print_success "Project cleaned"
}

# Function: Build
build() {
    print_header "Building Project"
    
    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "\K[0-9]+' | head -1)
    if [ "$JAVA_VERSION" -lt 21 ]; then
        print_error "Java 21+ required (current: $JAVA_VERSION)"
        exit 1
    fi
    print_success "Java $JAVA_VERSION detected"
    
    # Build
    mvn clean package -DskipTests -q
    
    if [ -f "target/$JAR_NAME" ]; then
        JAR_SIZE=$(du -h "target/$JAR_NAME" | cut -f1)
        print_success "Build successful - JAR size: $JAR_SIZE"
    else
        print_error "Build failed - JAR not found"
        exit 1
    fi
}

# Function: Run
run() {
    print_header "Starting ReportAI"
    
    if [ ! -f "target/$JAR_NAME" ]; then
        print_error "JAR not found - run './build_and_deploy.sh build' first"
        exit 1
    fi
    
    # Create required directories
    mkdir -p data/uploads data/temp data/templates data/reports
    print_success "Directories created"
    
    # Check environment
    if [ -z "$ANTHROPIC_API_KEY" ]; then
        print_error "ANTHROPIC_API_KEY not set"
        echo "Set with: export ANTHROPIC_API_KEY='your-key'"
        exit 1
    fi
    print_success "Environment configured"
    
    # Start application
    echo ""
    echo "Starting application on port $PORT..."
    echo "Logs available in: application.log"
    echo ""
    
    java -jar target/$JAR_NAME \
        --server.port=$PORT \
        --spring.profiles.active=production \
        2>&1 | tee application.log
}

# Function: Test
test_app() {
    print_header "Testing API"
    
    # Wait for application
    echo "Waiting for application to start..."
    sleep 5
    
    # Test health
    RESPONSE=$(curl -s -X POST http://localhost:$PORT/api/reports/generate \
        -H "Content-Type: application/json" \
        -d '{"prompt":"test","format":"JSON"}' 2>/dev/null || echo "ERROR")
    
    if echo "$RESPONSE" | grep -q "status"; then
        print_success "API is responding"
        echo "Response: $RESPONSE" | head -c 200
    else
        print_error "API not responding"
        exit 1
    fi
}

# Function: Docker Build
docker_build() {
    print_header "Building Docker Image"
    
    if [ ! -f "target/$JAR_NAME" ]; then
        print_error "JAR not found - run './build_and_deploy.sh build' first"
        exit 1
    fi
    
    # Create Dockerfile if doesn't exist
    if [ ! -f "Dockerfile" ]; then
        cat > Dockerfile << 'EOF'
FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

COPY target/reportAi-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=production
ENV ANTHROPIC_API_KEY=
ENV SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/reportai

ENTRYPOINT ["java", "-jar", "app.jar"]
EOF
        print_success "Dockerfile created"
    fi
    
    # Build image
    docker build -t $PROJECT_NAME:$VERSION .
    print_success "Docker image built: $PROJECT_NAME:$VERSION"
    
    # Show usage
    echo ""
    echo "To run Docker container:"
    echo "docker run -p 8080:8080 \\"
    echo "  -e ANTHROPIC_API_KEY='your-key' \\"
    echo "  -e SPRING_DATASOURCE_URL='jdbc:postgresql://host:5432/reportai' \\"
    echo "  -e SPRING_DATASOURCE_USERNAME='postgres' \\"
    echo "  -e SPRING_DATASOURCE_PASSWORD='password' \\"
    echo "  $PROJECT_NAME:$VERSION"
}

# Function: Docker Compose
docker_compose() {
    print_header "Creating Docker Compose Setup"
    
    if [ ! -f "docker-compose.yml" ]; then
        cat > docker-compose.yml << 'EOF'
version: '3.8'

services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: reportai
      POSTGRES_PASSWORD: root
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  pgvector:
    image: pgvector/pgvector:pg15
    depends_on:
      postgres:
        condition: service_healthy
    command: psql -h postgres -U postgres -d reportai -c "CREATE EXTENSION IF NOT EXISTS vector"

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:11434/api/tags"]
      interval: 10s
      timeout: 5s
      retries: 5

  reportai:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
      ollama:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/reportai
      SPRING_DATASOURCE_USERNAME: postgres
      SPRING_DATASOURCE_PASSWORD: root
      SPRING_AI_OLLAMA_BASE_URL: http://ollama:11434
    volumes:
      - ./data/uploads:/app/data/uploads
      - ./data/reports:/app/data/reports
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/health"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  postgres_data:
  ollama_data:
EOF
        print_success "docker-compose.yml created"
    fi
    
    echo ""
    echo "To start all services:"
    echo "docker-compose up -d"
    echo ""
    echo "To stop all services:"
    echo "docker-compose down"
    echo ""
    echo "To view logs:"
    echo "docker-compose logs -f reportai"
}

# Function: Deploy
deploy() {
    print_header "Deployment Guide"
    
    echo ""
    echo "🚀 Production Deployment Steps:"
    echo ""
    echo "1. BUILD ARTIFACTS"
    echo "   ./build_and_deploy.sh build"
    echo ""
    echo "2. DOCKER (Recommended)"
    echo "   ./build_and_deploy.sh docker-compose"
    echo "   docker-compose up -d"
    echo ""
    echo "3. VERIFY"
    echo "   curl http://localhost:8080/api/reports/generate"
    echo ""
    echo "4. MONITORING"
    echo "   docker-compose logs -f reportai"
    echo ""
    echo "5. SCALE UP (in docker-compose.yml)"
    echo "   Update replicas: 3"
    echo ""
}

# Main
case "${1:-help}" in
    clean)
        clean
        ;;
    build)
        clean
        build
        ;;
    run)
        build
        run
        ;;
    test)
        test_app
        ;;
    docker)
        build
        docker_build
        ;;
    docker-compose)
        build
        docker_build
        docker_compose
        ;;
    deploy)
        deploy
        ;;
    *)
        echo -e "${YELLOW}ReportAI Build & Deploy Script${NC}"
        echo ""
        echo "Usage: $0 {command}"
        echo ""
        echo "Commands:"
        echo "  clean              Clean build artifacts"
        echo "  build              Build JAR application"
        echo "  run                Build and run application locally"
        echo "  test               Test API endpoints"
        echo "  docker             Build Docker image"
        echo "  docker-compose     Setup Docker Compose environment"
        echo "  deploy             Show deployment guide"
        echo ""
        echo "Examples:"
        echo "  $0 build"
        echo "  $0 docker-compose && docker-compose up -d"
        echo ""
        ;;
esac
