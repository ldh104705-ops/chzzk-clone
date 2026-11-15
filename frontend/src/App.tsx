import { Link, Route, Routes } from 'react-router-dom'
import BroadcastDetailPage from './pages/BroadcastDetailPage'
import BroadcastListPage from './pages/BroadcastListPage'
import NewRecordingPage from './pages/NewRecordingPage'

export default function App() {
  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">
          치지직 클론
        </Link>
        <nav>
          <Link to="/recordings/new" className="button">
            녹화방송 등록
          </Link>
        </nav>
      </header>
      <main className="container">
        {/* Step 1: no login, every page is public. */}
        <Routes>
          <Route path="/" element={<BroadcastListPage />} />
          <Route path="/recordings/new" element={<NewRecordingPage />} />
          <Route path="/broadcasts/:id" element={<BroadcastDetailPage />} />
          <Route path="*" element={<p className="state">페이지를 찾을 수 없습니다.</p>} />
        </Routes>
      </main>
    </>
  )
}
