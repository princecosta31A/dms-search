# Use Java 21 JDK base image

FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app

COPY target/dms-search-1.0.0-SNAPSHOT.jar app.jar


EXPOSE 7008

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
