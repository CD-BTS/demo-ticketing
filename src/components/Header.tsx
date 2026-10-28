import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export default function Header() {
  const { userId, logout } = useAuth()
  const nav = useNavigate()
  return (
    <header className="header">
      <div className="header-inner">
        <NavLink to="/" className="brand">
          <span className="brand-dot" /> TICKET |<b>BTS</b>
        </NavLink>
        <nav className="nav">
          <NavLink to="/" end>공연</NavLink>
          <NavLink to="/my">마이페이지</NavLink>
        </nav>
        <div className="who">
          <span className="who-id">{userId}</span>
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => {
              logout()
              nav('/')
            }}
          >
            로그아웃
          </button>
        </div>
      </div>
    </header>
  )
}
