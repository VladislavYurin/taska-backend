FROM maven:3.9.14-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY common-lib ./common-lib
COPY grpc-common-lib ./grpc-common-lib
COPY kafka-lib ./kafka-lib
COPY api-gateway ./api-gateway
COPY auth-service ./auth-service
COPY project-service ./project-service
COPY workflow-service ./workflow-service
COPY issue-service ./issue-service
COPY notification-service ./notification-service

RUN mvn -q -DskipTests -pl api-gateway -am clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/api-gateway/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]