# ── 빌드 스테이지: JDK21 + gradle wrapper(9.5.1)로 부트 jar 생성 ──
# (이미지의 gradle 대신 wrapper 를 써서 Boot 4.1 이 요구하는 gradle 버전 보장)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon clean bootJar -x test

# ── 런타임 스테이지: JRE21 ──
FROM eclipse-temurin:21-jre
WORKDIR /app
# 방화벽 고객사동의서 OCR(ConsentOcrService) — tesseract CLI + 한국어 모델(apt tesseract-ocr-kor = 공식 tessdata 4.1.0).
# ★모델을 ADD <URL> 로 받지 말 것★ — 0600 root 파일이 되어 non-root 로 도는 cloudtype 에서 "Error opening data file" 로 실패했다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-kor \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
# 컨테이너(cloudtype)는 8080 으로 listen. 로컬은 PORT 미설정 시 application.yml 의 8888 사용.
# (cloudtype 이 PORT 를 주입하면 그 값이 우선됨)
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
