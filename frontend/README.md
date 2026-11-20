# 결제 API 테스트 화면

React·Vite로 백엔드 결제 흐름을 브라우저에서 확인하는 개발용 화면입니다.

## 실행

1. Docker와 MySQL을 사용할 수 있는 환경에서 Spring Boot 백엔드를 `localhost:8080`으로 실행합니다.
2. 백엔드 실행 환경에 API 개별 연동 테스트 키인 `TOSS_CLIENT_KEY`(`test_ck`)와 짝이 맞는 `TOSS_SECRET_KEY`(`test_sk`)를 설정합니다.
3. 이 폴더에서 아래 명령을 실행합니다.

```powershell
npm ci
npm run dev
```

Vite가 표시한 주소로 접속합니다. `/apis` 요청은 `vite.config.js`가 백엔드로 프록시하므로 별도의 브라우저 CORS 설정 없이 로그인 쿠키가 전달됩니다.

## 화면 흐름

1. `/login`에서 회원가입 또는 로그인합니다. 로그인 후 `/checkout`으로 이동합니다.
2. 주문번호로 `ready`를 호출합니다. 서버가 반환한 금액을 SDK에 사용합니다.
3. 결제창이 성공 URL `/success`로 돌아오면 준비 정보와 `paymentKey`·주문번호·금액을 확인하고 서버 승인 API를 호출합니다.
4. `/payments`에서 본인 결제 목록을, `/payments/{paymentId}`에서 한 건을 조회합니다.

현재 백엔드의 `PaymentAmountCalculator`는 임시로 10,000원을 반환합니다. 토스 클라이언트 키는 백엔드의 공개 키 조회 API에서 읽고, 시크릿 키는 프런트엔드 코드에 넣지 않습니다. Vite의 `index.html`은 React 실행에 필요한 진입 파일이며 실제 화면은 `src/pages`의 JSX 컴포넌트로 작성했습니다.

## 확인

```powershell
npm run build
npm run lint
```
