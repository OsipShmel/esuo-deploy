# Frontend (frontend-concept.md, F8): one multi-page build, served by both node-app and center-app from static/.
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/*.html frontend/*.ts frontend/*.js frontend/tsconfig.json ./
COPY frontend/src ./src
# Typecheck is a dev/CI step (npm run build); the image only needs the bundle.
RUN npx vite build

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY java-backend/ java-backend/
COPY infrastructure/config/ infrastructure/config/
ARG MODULE=node-app
COPY --from=web /web/dist/ java-backend/${MODULE}/src/main/resources/static/
RUN mvn -f java-backend/pom.xml -pl ${MODULE} -am -DskipTests package
RUN cp java-backend/${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app && mkdir -p /app/logs /app/dumps && chown app:app /app/logs /app/dumps
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
