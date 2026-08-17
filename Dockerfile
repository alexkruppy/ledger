FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY target/ledger-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+TieredCompilation", "-jar", "app.jar"]
