import { Link, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import RequireAuth from './auth/RequireAuth'
import BroadcastDetailPage from './pages/BroadcastDetailPage'
import BroadcastListPage from './pages/BroadcastListPage'
import LiveStudioPage from './pages/LiveStudioPage'
import LoginPage from './pages/LoginPage'
import NewLivePage from './pages/NewLivePage'
import NewRecordingPage from './pages/NewRecordingPage'
import SignupPage from './pages/SignupPage'

export default function App() {
  const { user, loading, logout } = useAuth()
  const navigate = useNavigate()

  async function onLogout() {
    await logout()
    navigate('/')
  }

  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">
          치지직 클론
        </Link>
        <nav>
          {/* Creating needs a login; logged-out visitors are redirected to /login (the server enforces it too). */}
          <Link to="/live/new" className="button primary">
            라이브 방송 시작
          </Link>
          <Link to="/recordings/new" className="button">
            녹화방송 등록
          </Link>
          {!loading && user && (
            <>
              <span className="nickname" title={`@${user.username}`}>
                {user.nickname}
              </span>
              <button type="button" className="button" onClick={onLogout}>
                로그아웃
              </button>
            </>
          )}
          {!loading && !user && (
            <>
              <Link to="/login" className="button">
                로그인
              </Link>
              <Link to="/signup" className="button">
                회원가입
              </Link>
            </>
          )}
        </nav>
      </header>
      <main className="container">
        {/* Browsing and watching are public; creating and managing broadcasts need a login. */}
        <Routes>
          <Route path="/" element={<BroadcastListPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/live/new" element={<RequireAuth><NewLivePage /></RequireAuth>} />
          <Route path="/live/:id/studio" element={<RequireAuth><LiveStudioPage /></RequireAuth>} />
          <Route path="/recordings/new" element={<RequireAuth><NewRecordingPage /></RequireAuth>} />
          <Route path="/broadcasts/:id" element={<BroadcastDetailPage />} />
          <Route path="*" element={<p className="state">페이지를 찾을 수 없습니다.</p>} />
        </Routes>
      </main>
    </>
  )
}
