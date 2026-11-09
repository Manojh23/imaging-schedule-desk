FROM node:22-bookworm-slim AS web
WORKDIR /src/web
COPY web/package*.json ./
RUN npm ci
COPY web/ .
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src
COPY pom.xml ./
COPY src ./src
COPY --from=web /src/web/dist ./src/main/resources/static
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=api /src/target/imaging-schedule-desk-0.1.0.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70.0","-jar","/app/app.jar"]
