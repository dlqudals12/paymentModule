import { Link } from 'react-router'

function FailPage() {
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  const message = params.get('message')

  return (
    <section className="card">
      <p className="eyebrow">결제창 결과</p>
      <h2>결제 요청 실패</h2>
      <p role="alert" className="notice">{message ?? '결제 요청이 완료되지 않았습니다.'}</p>
      {code && <p className="muted">오류 코드: {code}</p>}
      <Link className="button-link" to="/checkout">다시 시도</Link>
    </section>
  )
}

export default FailPage
