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
# 컨테이너(cloudtype)는 8080 으로 listen. 로컬은 PORT 미설정 시 application.yml 의 8888 사용.
# (cloudtype 이 PORT 를 주입하면 그 값이 우선됨)
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
