import { Navigate, NavLink, Route, Routes } from 'react-router'
import LoginPage from './pages/LoginPage.jsx'
import CheckoutPage from './pages/CheckoutPage.jsx'
import SuccessPage from './pages/SuccessPage.jsx'
import FailPage from './pages/FailPage.jsx'
import PaymentsPage from './pages/PaymentsPage.jsx'
import PaymentDetailPage from './pages/PaymentDetailPage.jsx'
import './App.css'

function App() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <p className="eyebrow">paymentModule</p>
          <h1>결제 API 테스트</h1>
        </div>
        <nav aria-label="테스트 화면">
          <NavLink to="/login">로그인</NavLink>
          <NavLink to="/checkout">결제 테스트</NavLink>
          <NavLink to="/payments">결제 목록</NavLink>
        </nav>
      </header>

      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/login" replace />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/success" element={<SuccessPage />} />
          <Route path="/fail" element={<FailPage />} />
          <Route path="/payments" element={<PaymentsPage />} />
          <Route path="/payments/:paymentId" element={<PaymentDetailPage />} />
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </main>
    </div>
  )
}

export default App
