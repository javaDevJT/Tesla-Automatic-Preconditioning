FROM openjdk:24-jdk-slim

WORKDIR /app

COPY target/Tesla-Automatic-Preconditioning-0.0.1-SNAPSHOT.jar /app/app.jar
COPY src/main/resources /resources

CMD ["java", "-jar", "your-app.jar"]