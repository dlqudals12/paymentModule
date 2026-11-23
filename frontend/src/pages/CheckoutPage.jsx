import { useEffect, useState } from 'react'
import { loadTossPayments } from '@tosspayments/tosspayments-sdk'
import { useLocation } from 'react-router'
import { apiError, loadClientKey, readyPayment } from '../api.js'

function newOrderId() {
  return `order_${Date.now()}_${crypto.randomUUID().slice(0, 8)}`
}

function customerKey() {
  const saved = sessionStorage.getItem('paymentTestCustomerKey')
  if (saved) return saved

  const created = crypto.randomUUID()
  sessionStorage.setItem('paymentTestCustomerKey', created)
  return created
}

function CheckoutPage() {
  const location = useLocation()
  const [orderId, setOrderId] = useState(() => location.state?.ready?.orderId ?? newOrderId())
  const [clientKey, setClientKey] = useState('')
  const [ready, setReady] = useState(() => location.state?.ready ?? null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true

    // 클라이언트 키만 서버 설정에서 받아온다. PG 시크릿 키는 브라우저로 보내지 않는다.
    loadClientKey()
      .then((key) => {
        if (!active) return
        setClientKey(key ?? '')
        if (!key) setMessage('서버의 TOSS_CLIENT_KEY 설정이 비어 있습니다.')
      })
      .catch((error) => {
        if (active) setMessage(apiError(error))
      })

    return () => { active = false }
  }, [])

  async function prepare(event) {
    event.preventDefault()
    setBusy(true)
    setMessage('')
    setReady(null)

    try {
      const result = await readyPayment(orderId.trim())
      setReady(result)
      setMessage('결제 준비가 완료됐습니다. 서버가 확정한 금액으로 결제창을 열 수 있습니다.')
    } catch (error) {
      setMessage(apiError(error))
    } finally {
      setBusy(false)
    }
  }

  async function openPayment() {
    if (!ready) return
    if (!clientKey.trim()) {
      setMessage('토스 테스트 클라이언트 키를 입력해 주세요.')
      return
    }

    setBusy(true)
    setMessage('')

    try {
      sessionStorage.setItem('paymentTestPending', JSON.stringify({
        orderId: ready.orderId,
        amount: ready.amount,
      }))

      const tossPayments = await loadTossPayments(clientKey.trim())
      const payment = tossPayments.payment({ customerKey: customerKey() })

      // SDK 인증 뒤 성공 URL로 돌아오면 그때 서버 승인 API를 호출한다.
      await payment.requestPayment({
        method: 'CARD',
        amount: { currency: 'KRW', value: ready.amount },
        orderId: ready.orderId,
        orderName: '결제 모듈 테스트',
        successUrl: `${window.location.origin}/success`,
        failUrl: `${window.location.origin}/fail`,
      })
    } catch (error) {
      setMessage(error.message ?? '결제창을 열지 못했습니다.')
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <p className="eyebrow">2 / 결제 준비</p>
      <h2>토스 결제창 테스트</h2>
      <p className="muted">먼저 로그인한 뒤, 서버의 ready API로 주문 금액을 확정하세요.</p>

      <form onSubmit={prepare} className="form">
        <label>
          주문번호
          <input value={orderId} onChange={(event) => { setOrderId(event.target.value); setReady(null) }} minLength={6} maxLength={64} required />
        </label>
        <button type="submit" disabled={busy}>{busy ? '요청 중…' : 'ready 호출'}</button>
      </form>

      {ready && (
        <div className="ready-box">
          <p>결제 ID <strong>{ready.id}</strong></p>
          <p>주문번호 <strong>{ready.orderId}</strong></p>
          <p>서버 금액 <strong>{Number(ready.amount).toLocaleString('ko-KR')}원</strong></p>
          <p>상태 <strong>{ready.paymentStatus}</strong></p>

          <p className="muted">API 개별 연동 클라이언트 키(test_ck)는 서버의 payments.toss 설정에서 읽습니다. 시크릿 키는 브라우저에 전달하지 않습니다.</p>
          <button type="button" disabled={busy || !clientKey} onClick={openPayment}>결제창 열기</button>
        </div>
      )}

      {message && <p role="status" className="notice">{message}</p>}
    </section>
  )
}

export default CheckoutPage
