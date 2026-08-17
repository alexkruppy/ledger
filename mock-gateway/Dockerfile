FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY target/mock-gateway-1.0.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
