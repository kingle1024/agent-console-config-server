# ── 빌드 스테이지: JDK21 + gradle wrapper(9.5.1)로 부트 jar 생성 ──
# (이미지의 gradle 대신 wrapper 를 써서 Boot 4.1 이 요구하는 gradle 버전 보장)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon clean bootJar -x test

# ── 런타임 스테이지: JRE21 ──
FROM eclipse-temurin:21-jre
WORKDIR /app
# 방화벽 고객사동의서 OCR(ConsentOcrService) — tesseract CLI + 한국어 모델.
# 모델은 공식 tessdata 4.1.0 kor(best_int LSTM — 앱 v0.2.180 에서 실동의서 7건 판독한 것과 같은 모델).
#  - apt tesseract-ocr-kor(경량 fast 모델)는 굵은 라벨 "접근 기간"을 깨뜨려 NOT_FOUND 가 났다.
#  - ADD <URL> 은 0600 root 파일이라 non-root 실행(cloudtype)에서 "Error opening data file" → 반드시 chmod 644.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr \
 && rm -rf /var/lib/apt/lists/*
ADD https://github.com/tesseract-ocr/tessdata/raw/4.1.0/kor.traineddata /app/tessdata/kor.traineddata
RUN chmod 755 /app/tessdata && chmod 644 /app/tessdata/kor.traineddata
ENV OCR_TESSDATA_DIR=/app/tessdata
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
# 컨테이너(cloudtype)는 8080 으로 listen. 로컬은 PORT 미설정 시 application.yml 의 8888 사용.
# (cloudtype 이 PORT 를 주입하면 그 값이 우선됨)
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
