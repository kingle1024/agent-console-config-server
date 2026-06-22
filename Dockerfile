# ── 빌드 스테이지: Gradle + JDK21 로 부트 jar 생성 ──
FROM gradle:8.14-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon clean bootJar

# ── 런타임 스테이지: JRE21 ──
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
# cloudtype 은 PORT 환경변수를 주입(없으면 8888). 시크릿 키·비밀번호는 환경변수로만.
ENV PORT=8888
EXPOSE 8888
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
