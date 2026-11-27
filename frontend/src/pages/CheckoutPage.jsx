import { useRef, useState } from 'react'
import { loadTossPayments } from '@tosspayments/tosspayments-sdk'
import { useLocation } from 'react-router'
import { apiError, readyPayment } from '../api.js'

// 브라우저 SDK에는 공개 클라이언트 키만 전달한다. 시크릿 키는 백엔드에 둔다.
const clientKey = (import.meta.env.VITE_TOSS_CLIENT_KEY ?? '').trim()
// 주문서형 결제위젯에는 gck 클라이언트 키가 필요하다.
const widgetClientKey = /^(test|live)_gck_/.test(clientKey)
const niceClientKey = (import.meta.env.VITE_NICE_CLIENT_KEY ?? '').trim()

/** NICEPAY 결제창 SDK는 공식 스크립트를 필요할 때 한 번만 로드한다. */
function loadNiceSdk() {
  if (window.AUTHNICE) return Promise.resolve(window.AUTHNICE)

  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://pay.nicepay.co.kr/v1/js/'
    script.onload = () => window.AUTHNICE ? resolve(window.AUTHNICE) : reject(new Error('NICEPAY SDK를 사용할 수 없습니다.'))
    script.onerror = () => reject(new Error('NICEPAY SDK를 불러오지 못했습니다.'))
    document.head.append(script)
  })
}

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
  const widgets = useRef(null)
  const [orderId, setOrderId] = useState(() => location.state?.ready?.orderId ?? newOrderId())
  const [provider, setProvider] = useState(() => location.state?.ready?.provider ?? 'TOSS')
  const [ready, setReady] = useState(() => location.state?.ready ?? null)
  const [widgetReady, setWidgetReady] = useState(false)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  async function prepare(event) {
    event.preventDefault()
    setBusy(true)
    setMessage('')
    setReady(null)
    setWidgetReady(false)
    widgets.current = null

    try {
      const result = await readyPayment(orderId.trim(), provider)
      setReady(result)
      setMessage('결제 준비가 완료됐습니다. 서버가 확정한 금액으로 결제수단을 표시할 수 있습니다.')
    } catch (error) {
      setMessage(apiError(error))
    } finally {
      setBusy(false)
    }
  }

  /**
   * 준비 단계에서 확정한 금액으로 결제수단과 약관 위젯을 표시한다.
   */
  async function openWidget() {
    if (!ready) return
    if (!clientKey) {
      setMessage('frontend/.env.local에 VITE_TOSS_CLIENT_KEY를 설정해 주세요.')
      return
    }

    if (!widgetClientKey) {
      setMessage('결제위젯에는 주문서형 클라이언트 키(test_gck)가 필요합니다.')
      return
    }

    setBusy(true)
    setMessage('')

    try {
      const tossPayments = await loadTossPayments(clientKey)
      const instance = tossPayments.widgets({ customerKey: customerKey() })

      // ready에서 받은 서버 금액을 위젯에 먼저 설정해야 결제수단을 렌더링할 수 있다.
      await instance.setAmount({ currency: 'KRW', value: ready.amount })
      await instance.renderPaymentMethods({ selector: '#payment-method' })
      await instance.renderAgreement({ selector: '#agreement', variantKey: 'AGREEMENT' })

      widgets.current = instance
      setWidgetReady(true)
      setMessage('결제수단과 약관을 확인한 뒤 결제하기를 눌러 주세요.')
    } catch (error) {
      setMessage(error.message ?? '결제위젯을 표시하지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }

  /**
   * 결제위젯에서 선택한 수단으로 인증을 요청하고 성공 URL에서 서버 승인을 이어간다.
   */
  async function requestPayment() {
    if (!ready || !widgets.current) return

    setBusy(true)
    setMessage('')

    try {
      sessionStorage.setItem('paymentTestPending', JSON.stringify({
        provider: 'TOSS',
        orderId: ready.orderId,
        amount: ready.amount,
      }))

      // SDK 인증 뒤 성공 URL로 돌아오면 그때 서버 승인 API를 호출한다.
      await widgets.current.requestPayment({
        orderId: ready.orderId,
        orderName: '결제 모듈 테스트',
        successUrl: `${window.location.origin}/success`,
        failUrl: `${window.location.origin}/fail`,
      })
    } catch (error) {
      setMessage(error.message ?? '결제를 요청하지 못했습니다.')
      sessionStorage.removeItem('paymentTestPending')
    } finally {
      setBusy(false)
    }
  }

  /**
   * NICEPAY Server 승인 결제창을 연다. 인증 결과는 프런트 returnUrl에서 받는다.
   */
  async function requestNicePayment() {
    if (!ready || !niceClientKey) return

    setBusy(true)
    setMessage('')

    try {
      const nice = await loadNiceSdk()
      sessionStorage.setItem('paymentTestPending', JSON.stringify({
        provider: 'NICE',
        orderId: ready.orderId,
        amount: ready.amount,
      }))

      nice.requestPay({
        clientId: niceClientKey,
        method: 'card',
        orderId: ready.orderId,
        amount: ready.amount,
        goodsName: '결제 모듈 테스트',
        returnUrl: `${window.location.origin}/nice/return`,
        fnError: (error) => {
          sessionStorage.removeItem('paymentTestPending')
          setMessage(error.errorMsg ?? 'NICEPAY 결제창을 열지 못했습니다.')
        },
      })
    } catch (error) {
      sessionStorage.removeItem('paymentTestPending')
      setMessage(error.message ?? 'NICEPAY 결제창을 열지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <p className="eyebrow">2 / 결제 준비</p>
      <h2>PG 결제창 테스트</h2>
      <p className="muted">먼저 로그인한 뒤, 서버의 ready API로 주문 금액을 확정하세요.</p>

      <form onSubmit={prepare} className="form">
        <label htmlFor="provider">
          결제대행사
          <select id="provider" name="provider" value={provider} disabled={busy}
            onChange={(event) => { setProvider(event.target.value); setReady(null); setWidgetReady(false); widgets.current = null }}>
            <option value="TOSS">토스페이먼츠</option>
            <option value="NICE">나이스페이먼츠</option>
          </select>
        </label>
        <label htmlFor="orderId">
          주문번호
          <input id="orderId" name="orderId" value={orderId} onChange={(event) => { setOrderId(event.target.value); setReady(null); setWidgetReady(false); widgets.current = null }} minLength={6} maxLength={64} disabled={busy} required />
        </label>
        <button type="submit" disabled={busy}>{busy ? '요청 중…' : 'ready 호출'}</button>
      </form>

      {ready && (
        <div className="ready-box">
          <p>결제 ID <strong>{ready.id}</strong></p>
          <p>주문번호 <strong>{ready.orderId}</strong></p>
          <p>서버 금액 <strong>{Number(ready.amount).toLocaleString('ko-KR')}원</strong></p>
          <p>상태 <strong>{ready.paymentStatus}</strong></p>

          <p className="muted">공개 클라이언트 키는 Vite 설정에서 읽습니다. 시크릿 키는 브라우저에 전달하지 않습니다.</p>
          {provider === 'TOSS' && !clientKey && (
            <p role="alert" className="notice">
              frontend/.env.local에 VITE_TOSS_CLIENT_KEY를 설정하고 Vite를 다시 실행해 주세요.
            </p>
          )}
          {provider === 'TOSS' && clientKey && !widgetClientKey && (
            <p role="alert" className="notice">
              현재 키는 결제위젯용 gck 키가 아닙니다. 공개 gck 키와 짝이 맞는 백엔드 gsk 키를 설정해 주세요.
            </p>
          )}
          {provider === 'TOSS' && !widgetReady && (
            <button type="button" disabled={busy || !widgetClientKey} onClick={openWidget}>결제수단 표시</button>
          )}

          {provider === 'TOSS' && <>
            {/* 위젯은 이미 존재하는 DOM 영역에 렌더링한다. */}
            <div id="payment-method" />
            <div id="agreement" />
          </>}

          {provider === 'TOSS' && widgetReady && (
            <button type="button" disabled={busy} onClick={requestPayment}>결제하기</button>
          )}
          {provider === 'NICE' && <>
            {!niceClientKey && <p role="alert" className="notice">VITE_NICE_CLIENT_KEY를 설정해 주세요.</p>}
            <button type="button" disabled={busy || !niceClientKey} onClick={requestNicePayment}>NICEPAY 결제창 열기</button>
          </>}
        </div>
      )}

      {message && <p role="status" className="notice">{message}</p>}
    </section>
  )
}

export default CheckoutPage
