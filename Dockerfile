FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -q -pl careerpilot-server -am package -DskipTests

FROM eclipse-temurin:17-jre
COPY --from=build /src/careerpilot-server/target/careerpilot-server-*.jar /app/server.jar
ENV SERVER_ADDRESS=0.0.0.0 HOME=/data
VOLUME /data
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
