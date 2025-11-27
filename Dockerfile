# Use official JDK
FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN ./mvnw -B dependency:resolve

COPY src src

RUN ./mvnw -B -DskipTests package

EXPOSE 9090

CMD ["sh", "-c", "java -jar target/*.jar"]
