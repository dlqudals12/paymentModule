import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { apiError, loadPayment, retryPayment } from '../api.js'

function PaymentDetailPage() {
  const { paymentId } = useParams()
  const navigate = useNavigate()
  const [payment, setPayment] = useState(null)
  const [message, setMessage] = useState('결제를 조회하는 중…')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    loadPayment(paymentId)
      .then((data) => {
        if (!active) return
        setPayment(data)
        setMessage('')
      })
      .catch((error) => {
        if (active) setMessage(apiError(error))
      })

    return () => { active = false }
  }, [paymentId])

  /** 실패 결제는 재준비하고, 결과 미확인 결제는 먼저 PG 결과를 확인한다. */
  async function retry() {
    setBusy(true)
    setMessage('기존 결제 결과를 확인하는 중…')

    try {
      const result = await retryPayment(paymentId)
      setPayment(result)

      if (result.paymentStatus === 'READY') {
        navigate('/checkout', { state: { ready: result } })
        return
      }

      setMessage('기존 결제가 완료된 것으로 확인됐습니다.')
    } catch (error) {
      setMessage(apiError(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <p className="eyebrow">저장된 결제 조회</p>
      <h2>결제 #{paymentId}</h2>
      {message && <p role="status" className="notice">{message}</p>}
      {payment && <pre>{JSON.stringify(payment, null, 2)}</pre>}
      {['TOSS', 'NICE'].includes(payment?.provider) && ['FAILED', 'UNKNOWN'].includes(payment.paymentStatus) && (
        <button type="button" disabled={busy} onClick={retry}>
          {busy ? '확인 중…' : payment.paymentStatus === 'UNKNOWN' ? '결과 확인 후 재결제' : '재결제'}
        </button>
      )}
      <Link className="button-link" to="/payments">목록으로</Link>
    </section>
  )
}

export default PaymentDetailPage
