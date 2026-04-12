FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . ./placeholder
WORKDIR /app/placeholder
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/placeholder/target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]