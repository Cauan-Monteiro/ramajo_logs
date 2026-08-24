# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# O pom.xml sozinho primeiro: enquanto as dependencias nao mudarem, o Docker
# reaproveita esta camada e o build nao baixa a internet de novo a cada
# alteracao de codigo.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Nao rodar como root: se a app for comprometida, o atacante nao e root no
# container.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=build /build/target/system-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

# MaxRAMPercentage faz a JVM respeitar o limite de memoria do container em vez
# de enxergar a RAM do host inteiro.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
