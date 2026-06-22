# Agent Console Config Server

[Agent Console](https://github.com/kingle1024/agent-console-electron) 데스크톱 앱이 쓸 **DB 접속정보·인증값을 보관/제공**하는 Spring Cloud Config Server.
앱(exe)에는 비밀을 넣지 않고, 실행 시 **비밀번호를 입력**하면 이 서버에서 받아온다.

## 보안 모델

- **접근 차단**: HTTP Basic 인증. 사용자가 입력한 비밀번호(`CONFIG_PASSWORD`)가 맞아야 설정을 받는다(틀리면 401).
- **저장 시 암호화**: 모든 비밀값은 `{cipher}...` 로 암호화되어 저장된다. 복호화 키 `ENCRYPT_KEY` 는 **환경변수로만** 주입(repo·이미지엔 암호문만).
- **전송 암호화**: cloudtype 의 HTTPS.
- 즉 repo 가 새도 암호문뿐이고, 키는 cloudtype 환경변수에만 있다.

## 환경변수 (운영=cloudtype 에서 반드시 지정)

| 변수 | 용도 |
|---|---|
| `ENCRYPT_KEY` | `{cipher}` 복호화 대칭키 (강한 랜덤 문자열) |
| `CONFIG_USER` | Basic 인증 사용자 (기본 `agent`) |
| `CONFIG_PASSWORD` | Basic 인증 비밀번호 — 앱에서 입력하는 그 값 |
| `PORT` | 리슨 포트 (cloudtype 자동 주입) |

## 제공 엔드포인트

- `GET /agent-console/default` — Basic 인증 필요. `config/agent-console.yml` 의 값을 **복호화해서** 반환.
- `GET /actuator/health` — 공개(헬스체크).
- `POST /encrypt`, `POST /decrypt` — Basic 인증 필요. 암호문 생성/검증용.

## 시크릿 값 넣기/갱신

1. 서버를 띄운다(로컬 또는 운영). `ENCRYPT_KEY`, `CONFIG_PASSWORD` 가 설정돼 있어야 함.
2. 평문을 암호화:
   ```
   curl -u $CONFIG_USER:$CONFIG_PASSWORD --data-urlencode '평문값' http://localhost:8888/encrypt
   ```
3. 출력 문자열 앞에 `{cipher}` 를 붙여 `src/main/resources/config/agent-console.yml` 에 넣는다. 예:
   ```yaml
   db:
     nsm:
       password: '{cipher}AQA...'
   ```
4. 재빌드/재배포.

## 로컬 실행

```
JAVA_HOME=<JDK21> ./gradlew bootRun
# 또는
docker build -t agent-console-config-server .
docker run -p 8080:8080 -e ENCRYPT_KEY=... -e CONFIG_USER=agent -e CONFIG_PASSWORD=... agent-console-config-server
```
> 컨테이너는 8080(`ENV PORT=8080`), 로컬 `gradlew bootRun` 은 8888(application.yml 기본). cloudtype 의 서비스 포트도 **8080** 으로 맞출 것.

## cloudtype 배포

1. 이 repo 를 cloudtype 에 연결(Dockerfile 자동 인식).
2. 환경변수 `ENCRYPT_KEY` / `CONFIG_USER` / `CONFIG_PASSWORD` 지정.
3. 배포 후 `https://<주소>/agent-console/default` 가 Basic 인증으로 열리는지 확인.

## 앱(클라이언트) 연동

Agent Console 설정에 Config Server URL + 비밀번호를 입력 → `GET {url}/agent-console/default` (Basic) 호출 →
응답의 propertySources 에서 `db.*`, `nsmAuth.*`, `tokens.*` 를 읽어 로컬(userData)에 암호화 저장 → 이후 그 값으로 DB 접속.
