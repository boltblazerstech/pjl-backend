# Stage 1: Build the application
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the pom.xml and download dependencies to utilize Docker layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy the actual source code and compile the jar
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Create the slim runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the compiled fat jar from the build stage
COPY --from=build /app/target/pjl-backend-0.0.1-SNAPSHOT.jar app.jar

# Render injects a dynamic PORT environment variable at runtime.
# We set a default of 8080 for local testing.
ENV PORT=8080
EXPOSE $PORT

# Use sh -c to ensure the PORT environment variable is evaluated at container startup
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT}"]
