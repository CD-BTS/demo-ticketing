import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ticketing } from '../api/ticketing'
import type { Concert } from '../api/types'
import { dateTime, won } from '../components/format'

export default function ConcertListPage() {
  const [concerts, setConcerts] = useState<Concert[] | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    ticketing
      .listConcerts()
      .then(setConcerts)
      .catch((e: Error) => setError(e.message))
  }, [])

  if (error) return <p className="error">{error}</p>
  if (!concerts) return <p className="muted">공연 목록을 불러오는 중…</p>

  return (
    <>
      <h1 className="page-title">예매 가능한 공연</h1>
      <div className="grid">
        {concerts.map((c) => (
          <Link key={c.id} to={`/concerts/${c.id}`} className="card concert-card">
            <div
              className="poster"
              style={{ background: `hsl(${c.hue} 60% 38%)` }}
            >
              <span className="poster-artist">{c.artist}</span>
              <span className="poster-title">{c.title}</span>
            </div>
            <div className="card-body">
              <p className="tagline">{c.tagline}</p>
              <p className="meta">{dateTime(c.startAt)}</p>
              <p className="meta">{c.venue}</p>
              <p className="price">{won(c.priceFrom)}~</p>
            </div>
          </Link>
        ))}
      </div>
    </>
  )
}
