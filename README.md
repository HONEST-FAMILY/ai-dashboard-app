# AI 대시보드 안드로이드 앱

`https://dashboard.honest-family.com` 을 감싼 안드로이드 앱과 홈 화면 위젯입니다.

## 구성
- **앱 본체** — 로그인·모든 화면을 그대로 쓰는 WebView 앱. 파일 업로드/다운로드 지원.
- **스케줄 위젯** — 앞으로 30일 일정을 홈 화면에서 봅니다. 크기 조절 가능. 탭하면 스케줄 페이지가 열립니다.
- **즐겨찾기 위젯** — 대시보드에서 별표한 메뉴만 모아서 보여주고, 누르면 그 페이지가 바로 열립니다.

두 위젯 모두 로그인 토큰(쿠키)을 앱과 공유합니다. 위젯이 비어 있으면 앱을 한 번 열어 로그인한 뒤 위젯의 새로고침(↻)을 누르세요.

## 다운로드
최신 APK는 [Releases](../../releases/latest) 의 `ai-dashboard.apk` 입니다. 푸시할 때마다 GitHub Actions가 자동으로 다시 빌드해 올립니다.

## 로컬 빌드
Android Studio(또는 JDK 17 + Android SDK 34)로 이 폴더를 열고 `assembleDebug` 를 실행합니다.

## 뽀뽀뜨 직원용 앱

`popote` 모듈은 `https://popote.honest-family.com`을 여는 별도 앱이다. 앱 이름은 뽀뽀뜨 대시보드이며 기존 직원 로그인과 첨부·다운로드를 사용한다. 어니스트 앱의 위젯·워치·알림 API와 토큰 저장 기능은 포함하지 않는다.

`gradle :popote:assembleRelease :popote:lintRelease`로 검증한다. main 푸시 시 뽀뽀뜨 전용 자동 빌드가 기존 서명 설정을 사용하고 `popote-latest` 릴리스에 `popote-dashboard.apk`를 공개한다. 기존 어니스트 앱과 설치 식별자가 달라 함께 설치할 수 있다. 설치 안내는 `https://popote.honest-family.com/install/mobile`이다.

되돌릴 때는 뽀뽀뜨 모듈과 자동 빌드 추가 변경만 `git revert`하여 main에 푸시한다. 기존 어니스트 앱과 최신 릴리스는 유지한다. 운영 계정과 업무 DB는 변경하지 않는다.
