# stage 1: build with Maven (uses Maven image so no mvnw needed)
FROM maven:3.9.3-eclipse-temurin-17 AS build

WORKDIR /workspace
# copy only pom + sources to leverage Docker cache better
COPY pom.xml .
# download dependencies
RUN mvn -B dependency:go-offline

# copy sources and build
COPY src ./src
RUN mvn -B -DskipTests package

# stage 2: runtime image
FROM eclipse-temurin:17-jdk

WORKDIR /app
# Copy the built jar (assumes artifact in target/*.jar)
COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
