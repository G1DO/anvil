# Multistage minimal unprivileged image (Starter Kit service standard).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:25-jre
RUN useradd -m -u 10001 anvil && mkdir -p /app && chown anvil:anvil /app
USER anvil
WORKDIR /app
COPY --from=build /app/target/anvil-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
