# 两阶段：带 Maven 的镜像编译出可执行 fat jar，运行镜像只留 JRE。
# 单独 build 这一个镜像没意义（应用要连 PostgreSQL 和 MySQL），请用 docker compose up --build。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 先只拷 pom 拉依赖，改代码时这一层不用重来
COPY pom.xml .
# go-offline 拉不全也不该让整个镜像失败：它只影响缓存收益，真正的编译在下一步
RUN mvn -B -q dependency:go-offline || true
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
# target 下只有一个可执行 jar（另一个是 .original，不被 *.jar 匹配）
COPY --from=build /src/target/*.jar app.jar
# 仓库自带的示例指南；compose 会用宿主目录只读覆盖它，方便往里丢自己的 PDF
COPY data/pdfs data/pdfs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
