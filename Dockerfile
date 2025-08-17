FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

COPY target/*.jar /app/app.jar
COPY src/main/resources/ /resources/

CMD ["java", "-jar", "app.jar"]