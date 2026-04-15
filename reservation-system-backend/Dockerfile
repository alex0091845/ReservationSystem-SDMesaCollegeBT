# Maven dependency layer
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy src
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# User for security (runs as root by default)
RUN useradd -ms /bin/bash javauser
USER javauser

COPY --from=build /build/target/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]