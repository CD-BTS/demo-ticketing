import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import Header from './components/Header'
import ConcertPage from './pages/ConcertPage'
import ConcertListPage from './pages/ConcertListPage'
import LoginPage from './pages/LoginPage'
import MyPage from './pages/MyPage'
import PaymentPage from './pages/PaymentPage'

export default function App() {
  const { userId } = useAuth()

  if (!userId) {
    return (
      <Routes>
        <Route path="*" element={<LoginPage />} />
      </Routes>
    )
  }

  return (
    <>
      <Header />
      <main className="container">
        <Routes>
          <Route path="/" element={<ConcertListPage />} />
          <Route path="/concerts/:id" element={<ConcertPage />} />
          <Route path="/concerts/:id/payment" element={<PaymentPage />} />
          <Route path="/my" element={<MyPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </>
  )
}
