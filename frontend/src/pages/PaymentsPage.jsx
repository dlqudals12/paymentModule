import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { apiError, loadPayments } from '../api.js'

function PaymentsPage() {
  const [page, setPage] = useState(0)
  const [result, setResult] = useState(null)
  const [message, setMessage] = useState('결제 목록을 불러오는 중…')

  useEffect(() => {
    let active = true
    loadPayments(page)
      .then((data) => {
        if (!active) return
        setResult(data)
        setMessage('')
      })
      .catch((error) => {
        if (active) setMessage(apiError(error))
      })

    return () => { active = false }
  }, [page])

  return (
    <section className="card wide-card">
      <p className="eyebrow">저장된 결제 조회</p>
      <h2>내 결제 목록</h2>
      <p className="muted">로그인한 사용자의 결제만 최신순으로 표시합니다.</p>

      {message && <p role="status" className="notice">{message}</p>}
      {result && result.content.length === 0 && <p className="notice">저장된 결제가 없습니다.</p>}
      {result && result.content.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead><tr><th>ID</th><th>주문번호</th><th>금액</th><th>결제 상태</th><th>주문 상태</th></tr></thead>
            <tbody>
              {result.content.map((payment) => (
                <tr key={payment.paymentId}>
                  <td><Link to={`/payments/${payment.paymentId}`}>{payment.paymentId}</Link></td>
                  <td>{payment.orderId}</td>
                  <td>{Number(payment.amount).toLocaleString('ko-KR')}원</td>
                  <td>{payment.paymentStatus}</td>
                  <td>{payment.orderStatus}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {result && (
        <div className="pagination">
          <button type="button" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>이전</button>
          <span>{page + 1} / {Math.max(result.totalPages, 1)}</span>
          <button type="button" disabled={result.last} onClick={() => setPage((current) => current + 1)}>다음</button>
        </div>
      )}
    </section>
  )
}

export default PaymentsPage
