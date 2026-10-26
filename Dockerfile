# 两阶段：带 Maven 的镜像编译出可执行 fat jar，运行镜像只留 JRE。
# 单独 build 这一个镜像没意义（应用要连 PostgreSQL 和 MySQL），请用 docker compose up --build。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src src
COPY .mvn .mvn
COPY mvnw .
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseZGC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs/heapdump.hprof \
  -XX:+ExitOnOutOfMemoryError"

RUN mkdir -p /app/logs \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/target/*.jar app.jar
COPY data/pdfs data/pdfs

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=15s \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]