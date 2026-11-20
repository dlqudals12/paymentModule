import { useState } from 'react'
import { useNavigate } from 'react-router'
import { apiError, loginUser, registerUser } from '../api.js'

function LoginPage() {
  const navigate = useNavigate()
  const [mode, setMode] = useState('login')
  const [form, setForm] = useState({ id: '', password: '', email: '', name: '' })
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  function updateField(event) {
    const { name, value } = event.target
    setForm((current) => ({ ...current, [name]: value }))
  }

  async function submit(event) {
    event.preventDefault()
    setBusy(true)
    setMessage('')

    try {
      if (mode === 'register') {
        await registerUser(form)
        setMode('login')
        setMessage('가입했습니다. 같은 아이디로 로그인해 주세요.')
        return
      }

      // 로그인 응답의 HttpOnly 쿠키는 브라우저가 보관하고 이후 API 요청에 전송한다.
      await loginUser(form)
      navigate('/checkout')
    } catch (error) {
      setMessage(apiError(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <p className="eyebrow">1 / 인증</p>
      <h2>{mode === 'login' ? '로그인' : '테스트 계정 만들기'}</h2>
      <p className="muted">결제 API는 ROLE_USER 인증이 필요합니다.</p>

      <form onSubmit={submit} className="form">
        <label>
          아이디
          <input name="id" value={form.id} onChange={updateField} required autoComplete="username" />
        </label>
        <label>
          비밀번호
          <input name="password" type="password" value={form.password} onChange={updateField} required autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
        </label>
        {mode === 'register' && (
          <>
            <label>
              이메일
              <input name="email" type="email" value={form.email} onChange={updateField} required />
            </label>
            <label>
              이름
              <input name="name" value={form.name} onChange={updateField} required />
            </label>
          </>
        )}
        <button disabled={busy} type="submit">
          {busy ? '요청 중…' : mode === 'login' ? '로그인' : '회원가입'}
        </button>
      </form>

      {message && <p role="status" className="notice">{message}</p>}
      <button className="text-button" type="button" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setMessage('') }}>
        {mode === 'login' ? '계정이 없으면 회원가입' : '로그인으로 돌아가기'}
      </button>
    </section>
  )
}

export default LoginPage
