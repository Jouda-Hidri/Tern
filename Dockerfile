# Build the jar inside the image so a clean checkout is all CI (or a new machine) needs.
# Dependencies resolve in their own layer, so source-only changes do not re-download them.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:11-jre
# curl is only here so the container can report its own health to docker-compose and k8s.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
VOLUME /tmp
EXPOSE 8080 9090
COPY --from=build /build/target/artic-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar"]
