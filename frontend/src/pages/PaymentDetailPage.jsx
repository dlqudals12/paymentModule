import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { apiError, loadPayment } from '../api.js'

function PaymentDetailPage() {
  const { paymentId } = useParams()
  const [payment, setPayment] = useState(null)
  const [message, setMessage] = useState('결제를 조회하는 중…')

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

  return (
    <section className="card">
      <p className="eyebrow">저장된 결제 조회</p>
      <h2>결제 #{paymentId}</h2>
      {message && <p role="status" className="notice">{message}</p>}
      {payment && <pre>{JSON.stringify(payment, null, 2)}</pre>}
      <Link className="button-link" to="/payments">목록으로</Link>
    </section>
  )
}

export default PaymentDetailPage
