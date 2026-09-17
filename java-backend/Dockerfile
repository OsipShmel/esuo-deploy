FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY java-backend/ java-backend/
COPY infrastructure/config/ infrastructure/config/
ARG MODULE=node-app
RUN mvn -f java-backend/pom.xml -pl ${MODULE} -am -DskipTests package
RUN cp java-backend/${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
