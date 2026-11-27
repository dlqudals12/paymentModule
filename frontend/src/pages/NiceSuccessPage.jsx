import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { apiError, approvePayment } from '../api.js'

/** NICEPAY 인증 결과를 로그인 사용자의 기존 결제 API로 승인한다. */
function NiceSuccessPage() {
  const started = useRef(false)
  const [result, setResult] = useState(null)
  const [message, setMessage] = useState('결제 승인 요청 중…')
  const [confirmation] = useState(() => {
    const values = new URLSearchParams(window.location.hash.slice(1))
    const pending = JSON.parse(sessionStorage.getItem('paymentTestPending') ?? 'null')
    const amount = Number(values.get('amount'))
    const orderId = values.get('orderId')
    const invalid = !values.get('tid') || !values.get('authToken') || !values.get('signature')
      || !Number.isSafeInteger(amount) || amount <= 0
      || pending?.provider !== 'NICE' || pending?.orderId !== orderId
      || pending?.amount !== amount

    return { values, amount, orderId, invalid }
  })

  useEffect(() => {
    if (started.current) return
    started.current = true

    // 인증 토큰이 브라우저 주소창에 남지 않도록 먼저 제거한다.
    window.history.replaceState(null, '', window.location.pathname)
    if (confirmation.invalid) return

    async function approve() {
      try {
        const response = await approvePayment({
          provider: 'NICE',
          externalPaymentId: confirmation.values.get('tid'),
          orderId: confirmation.orderId,
          amount: confirmation.amount,
          clientId: confirmation.values.get('clientId'),
          authToken: confirmation.values.get('authToken'),
          signature: confirmation.values.get('signature'),
        })
        sessionStorage.removeItem('paymentTestPending')
        setResult(response)
        setMessage('서버 승인 요청이 완료됐습니다.')
      } catch (error) {
        setMessage(apiError(error))
      }
    }

    void approve()
  }, [confirmation])

  return (
    <section className="card">
      <p className="eyebrow">3 / 서버 승인</p>
      <h2>NICEPAY 결제 결과</h2>
      <p role="status" className="notice">
        {confirmation.invalid ? '준비 단계의 주문번호·금액과 NICEPAY 인증 결과가 다릅니다.' : message}
      </p>
      {result && <pre>{JSON.stringify(result, null, 2)}</pre>}
      {result && <Link className="button-link secondary-link" to={`/payments/${result.id}`}>저장된 결제 조회</Link>}
      <Link className="button-link" to="/checkout">새 결제 테스트</Link>
    </section>
  )
}

export default NiceSuccessPage
