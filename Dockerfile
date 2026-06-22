# ── 빌드 스테이지: JDK21 + gradle wrapper(9.5.1)로 부트 jar 생성 ──
# (이미지의 gradle 대신 wrapper 를 써서 Boot 4.1 이 요구하는 gradle 버전 보장)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon clean bootJar -x test

# ── 런타임 스테이지: JRE21 ──
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
# cloudtype 은 PORT 환경변수를 주입(없으면 8888). 시크릿 키·비밀번호는 환경변수로만.
ENV PORT=8888
EXPOSE 8888
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
