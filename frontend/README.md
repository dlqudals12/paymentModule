# 결제 API 테스트 화면

React·Vite로 백엔드 결제 흐름을 브라우저에서 확인하는 개발용 화면입니다.

## 실행

1. Docker와 MySQL을 사용할 수 있는 환경에서 Spring Boot 백엔드를 `localhost:8080`으로 실행합니다.
2. `frontend/.env.local`의 `VITE_TOSS_CLIENT_KEY`에 주문서형 공개 테스트 키(`test_gck`)를 입력합니다. 백엔드의 `payments.toss.secret-key`에는 짝이 맞는 `test_gsk`가 필요합니다. 시크릿 키는 프런트 설정에 넣지 않습니다.
3. 이 폴더에서 아래 명령을 실행합니다. 키 값을 바꿨다면 Vite를 다시 실행합니다.

```powershell
npm ci
npm run dev
```

Vite가 표시한 주소로 접속합니다. `/apis` 요청은 `vite.config.js`가 백엔드로 프록시하므로 별도의 브라우저 CORS 설정 없이 로그인 쿠키가 전달됩니다.

이 화면은 토스 SDK의 `widgets()`를 사용합니다. `test_gck`·`test_gsk`를 같은 키 쌍으로 사용합니다. 키를 바꿨다면 Vite와 백엔드를 모두 다시 실행합니다.

## 화면 흐름

1. `/login`에서 회원가입 또는 로그인합니다. 로그인 후 `/checkout`으로 이동합니다.
2. 주문번호로 `ready`를 호출합니다. 서버가 반환한 금액으로 결제수단과 약관 위젯을 표시합니다.
3. 결제하기를 누르면 위젯에서 선택한 결제수단으로 인증합니다. 성공 URL `/success`로 돌아오면 준비 정보와 `paymentKey`·주문번호·금액을 확인하고 서버 승인 API를 호출합니다.
4. `/payments`에서 본인 결제 목록을, `/payments/{paymentId}`에서 한 건을 조회합니다.

현재 백엔드의 `PaymentAmountCalculator`는 임시로 10,000원을 반환합니다. 토스 공개 클라이언트 키는 Vite의 `VITE_TOSS_CLIENT_KEY`에서 읽고, 시크릿 키는 프런트엔드 코드에 넣지 않습니다. 키가 비어 있거나 위젯 키가 아니면 결제수단 버튼 위에 설정 안내를 표시합니다. Vite의 `index.html`은 React 실행에 필요한 진입 파일이며 실제 화면은 `src/pages`의 JSX 컴포넌트로 작성했습니다.

## 확인

```powershell
npm run build
npm run lint
```
