FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/samaadresse-api-0.1.0-SNAPSHOT.jar app.jar
# Dossier des photos de façade, accessible en écriture à l'utilisateur non privilégié.
RUN mkdir -p /data/photos && chown -R 10001:10001 /data
ENV APP_PHOTOS_DIRECTORY=/data/photos
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
