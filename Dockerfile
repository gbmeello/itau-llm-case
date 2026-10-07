# Build multi-stage: compila com o Maven Wrapper e roda só com o JRE, como usuário não-root.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 agent
USER agent
WORKDIR /app
COPY --from=build /src/target/purchase-approval-agent-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
