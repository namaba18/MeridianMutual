# Runtime image for payment-intake. The jar is built and tested by the pipeline before this step
# (./mvnw verify), so the image only packages an artifact that already passed every test.
# In a real repository the base image is pinned by digest; Dependabot keeps it current.
FROM eclipse-temurin:24-jre-alpine

# Never run as root.
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/payment-intake-*.jar /app/app.jar
USER app

EXPOSE 8080
# Use the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
