# Use Java 21 JDK base image

FROM eclipse-temurin:21-jdk-alpine

# Pull in the current Alpine security fixes before anything else.
# Without this the image ships whatever package versions the base image was
# built with, and Trivy reports every OS CVE published since - libexpat
# 2.9.0-r0 (2 High, 1 Medium, 1 Unknown) being the one that prompted this.
# Same line as document-service, deliberately.
RUN apk upgrade --no-cache

WORKDIR /app

COPY target/dms-search-1.0.0-SNAPSHOT.jar app.jar


EXPOSE 7008

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
