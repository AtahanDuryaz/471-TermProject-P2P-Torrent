# Stage 1: Build stage with Maven
FROM maven:3-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy pom.xml first for dependency caching
COPY pom.xml .

# Download dependencies (cached layer if pom.xml doesn't change)
RUN mvn dependency:go-offline

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Stage 2: Runtime stage (minimal JRE image)
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy JAR and dependencies from builder stage
COPY --from=builder /build/target/p2p-video-stream-1.0-SNAPSHOT.jar /app/app.jar
COPY --from=builder /build/target/lib /app/lib

# Create directories for videos and buffer
RUN mkdir -p /videos /buffer

# Environment variables with defaults
ENV PEER_ID=""
ENV BROADCAST_ADDRESS="172.20.255.255"
ENV FILE_SERVER_PORT="50001"
ENV VIDEO_DIR="/videos"
ENV BUFFER_DIR="/buffer"

# Expose ports
# UDP for discovery
EXPOSE 50000/udp
# TCP for file transfer
EXPOSE 50001/tcp

# Health check (optional - checks if discovery port is listening)
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD netstat -an | grep 50000 || exit 1

# Run in headless mode
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--headless"]
