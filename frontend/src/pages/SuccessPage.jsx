import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { apiError, approvePayment } from '../api.js'

function SuccessPage() {
  const started = useRef(false)
  const [result, setResult] = useState(null)
  const [message, setMessage] = useState('결제 승인 요청 중…')

  useEffect(() => {
    // 개발 모드 StrictMode의 Effect 재실행으로 승인 API를 두 번 호출하지 않는다.
    if (started.current) return
    started.current = true

    async function approve() {
      const params = new URLSearchParams(window.location.search)
      const paymentKey = params.get('paymentKey')
      const orderId = params.get('orderId')
      const amount = Number(params.get('amount'))
      const pending = JSON.parse(sessionStorage.getItem('paymentTestPending') ?? 'null')

      // 브라우저 값은 참고만 하고 서버에서 준비 금액과 현재 금액을 다시 검증한다.
      if (!paymentKey || !orderId || !Number.isSafeInteger(amount) || amount <= 0
          || pending?.orderId !== orderId || pending?.amount !== amount) {
        setMessage('준비 단계의 주문번호·금액과 SDK 결과가 다릅니다. 다시 준비해 주세요.')
        return
      }

      try {
        const response = await approvePayment({ paymentKey, orderId, amount })
        sessionStorage.removeItem('paymentTestPending')
        setResult(response)
        setMessage('서버 승인 요청이 완료됐습니다.')
      } catch (error) {
        setMessage(apiError(error))
      }
    }

    void approve()
  }, [])

  return (
    <section className="card">
      <p className="eyebrow">3 / 서버 승인</p>
      <h2>결제 결과</h2>
      <p role="status" className="notice">{message}</p>
      {result && <pre>{JSON.stringify(result, null, 2)}</pre>}
      {result && <Link className="button-link secondary-link" to={`/payments/${result.id}`}>저장된 결제 조회</Link>}
      <Link className="button-link" to="/checkout">새 결제 테스트</Link>
    </section>
  )
}

export default SuccessPage
