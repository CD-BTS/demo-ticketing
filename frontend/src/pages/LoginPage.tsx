import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth/AuthContext'

export default function LoginPage() {
  const { login } = useAuth()
  const [id, setId] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const v = id.trim()
    if (!/^[A-Za-z0-9_-]{3,30}$/.test(v)) {
      setError('아이디는 영문·숫자·_- 3~30자로 입력해 주세요.')
      return
    }
    setBusy(true)
    setError('')
    try {
      await login(v)
    } catch (err) {
      setError(err instanceof Error ? err.message : '로그인에 실패했습니다.')
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <div className="brand brand-lg">
          <span className="brand-dot" /> TICKET<b>/FDS</b>
        </div>
        <h1>예매를 시작하려면<br />사용자 ID를 입력하세요</h1>
        <p className="muted">처음 입력한 ID는 자동으로 등록됩니다.</p>
        <label className="field">
          <span>사용자 ID</span>
          <input
            autoFocus
            value={id}
            onChange={(e) => setId(e.target.value)}
            placeholder="예: minchae01"
            autoComplete="username"
          />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="btn btn-primary btn-block" disabled={busy}>
          {busy ? '확인 중…' : '시작하기'}
        </button>
      </form>
    </div>
  )
}
