import { Link, Route, Routes } from 'react-router-dom'
import BroadcastDetailPage from './pages/BroadcastDetailPage'
import BroadcastListPage from './pages/BroadcastListPage'
import LiveStudioPage from './pages/LiveStudioPage'
import NewLivePage from './pages/NewLivePage'
import NewRecordingPage from './pages/NewRecordingPage'

export default function App() {
  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">
          치지직 클론
        </Link>
        <nav>
          <Link to="/live/new" className="button primary">
            라이브 방송 시작
          </Link>
          <Link to="/recordings/new" className="button">
            녹화방송 등록
          </Link>
        </nav>
      </header>
      <main className="container">
        {/* Steps 1-2: no login, every page is public. */}
        <Routes>
          <Route path="/" element={<BroadcastListPage />} />
          <Route path="/live/new" element={<NewLivePage />} />
          <Route path="/live/:id/studio" element={<LiveStudioPage />} />
          <Route path="/recordings/new" element={<NewRecordingPage />} />
          <Route path="/broadcasts/:id" element={<BroadcastDetailPage />} />
          <Route path="*" element={<p className="state">페이지를 찾을 수 없습니다.</p>} />
        </Routes>
      </main>
    </>
  )
}
