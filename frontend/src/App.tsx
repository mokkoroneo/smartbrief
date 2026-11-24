import React, { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate, Outlet, useNavigate, useParams } from 'react-router-dom';
import { theme } from './theme';
import { ToastProvider } from './contexts/ToastContext';
import { apiClient } from './utils/apiClient';
import { useAuthStore } from './store/authStore';
import { useToast } from './contexts/ToastContext';
// コンポーネント
import Dashboard from './components/Dashboard';
import BookSearch from './components/BookSearch';
import BookDetail from './components/BookDetail';
import AuthorList from './components/AuthorList';
import GenreList from './components/GenreList';
import PaymentSuccess from './components/PaymentSuccess';
import ForgotPassword from './components/ForgotPassword';
import ResetPassword from './components/ResetPassword';
import Terms from './components/Terms';
import Privacy from './components/Privacy';
import Legal from './components/Legal';
import Login from './Login'; 
import VerifyEmail from './VerifyEmail';

// --- 型定義 ---
interface UserMeResponse {
  username: string;
  email: string;
  plan: 'FREE' | 'PREMIUM';
  isPremium: boolean;
}

const AppWrapper: React.FC = () => {
  return (
    <ToastProvider>
      <BrowserRouter>
        <AppContent />
      </BrowserRouter>
    </ToastProvider>
  );
};


const AppContent: React.FC = () => {
  const navigate = useNavigate();
  const { resolveSession, logout } = useAuthStore();
  const { showToast } = useToast(); 

  // --- 課金・契約管理（Stripe連携） ---
  const handleCheckout = async () => {
    try {
      const res = await apiClient.post<{ url: string }>('/checkout/create-session');
      
      if (res.ok && res.data?.url) {
        window.location.href = res.data.url; // Stripeの決済画面へジャンプ！
      } else {
        showToast(res.message || '決済画面の取得に失敗しました', 'error');
        if (res.status === 401) logout();
      }
    } catch (err) {
      showToast('通信エラーが発生しました。', 'error');
    }
  };

  const handleManageSubscription = async () => {
    try {
      const res = await apiClient.get<{ url: string }>('/billing/portal');
      
      if (res.ok && res.data?.url) {
        window.location.href = res.data.url; // Stripeの管理画面へジャンプ！
      } else {
        showToast(res.message || '管理画面の取得に失敗しました', 'error');
      }
    } catch (err) {
      showToast('通信エラーが発生しました。', 'error');
    }
  };

  // ★変更点: 起動時にCookieが有効かチェックし、自動でログイン状態を復元する
  useEffect(() => {
    let active = true;
    const checkSession = async () => {
      // apiClientが自動でCookieを送ってくれるので、そのまま /auth/me を叩く
      const res = await apiClient.get<UserMeResponse>('/auth/me');

      if (active) resolveSession(res.ok && res.data ? res.data.isPremium : null);
    };
    checkSession();
    return () => { active = false; };
  }, [resolveSession]);

  return (
    <div style={styles.appRoot}>
      <Routes>
        <Route element={<AuthRoute allowedWhenLoggedIn={false} />}>
          <Route path="/login" element={<Login />} />
        </Route>
        <Route path="/forgot-password" element={<ForgotPassword />} />
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route path="/verify" element={<VerifyEmail />} />
        <Route path="/terms" element={<Terms />} />
        <Route path="/privacy" element={<Privacy />} />
        <Route path="/legal" element={<Legal />} />

        {/* 認証必須ページ */}
        <Route element={<AuthRoute />}>
          <Route path="/" element={
            <Dashboard
              onBookSelect={(id: number) => navigate(`/book/${id}`)}
              onUpgrade={handleCheckout}
              onManage={handleManageSubscription}
            />
          } />
          <Route path="/search" element={<BookSearchPage />} />
          <Route path="/authors" element={<AuthorList onBack={() => navigate('/')} />} />
          <Route path="/genres" element={<GenreList onBack={() => navigate('/')} />} />
          <Route path="/book/:bookId" element={<BookDetailPage />} />
        </Route>
        <Route path="/payment/success" element={<PaymentSuccess />} />
        
        <Route path="*" element={<Navigate to="/" />} />
      </Routes>
    </div>
  );
}

// --- 補助コンポーネント ---
const AuthRoute: React.FC<{ allowedWhenLoggedIn?: boolean }> = ({ allowedWhenLoggedIn = true }) => {
  const { isLoggedIn, isSessionPending } = useAuthStore();

  if (isSessionPending) {
    return <div role="status" style={styles.sessionPending}>認証状態を確認しています…</div>;
  }
  if (isLoggedIn !== allowedWhenLoggedIn) {
    return <Navigate to={allowedWhenLoggedIn ? '/login' : '/'} replace />;
  }
  return <Outlet />;
};

const BookSearchPage: React.FC = () => {
  const navigate = useNavigate();
  return (
    <div style={styles.pageContainer}>
      <button onClick={() => navigate('/')} style={styles.backLink}>← ダッシュボードへ</button>
      <BookSearch onBookSelect={(id: number) => navigate(`/book/${id}`)} />
    </div>
  );
};

const BookDetailPage: React.FC = () => {
  const { bookId } = useParams<{ bookId: string }>(); 
  const navigate = useNavigate();
  return (
    <div style={styles.pageContainer}>
      <BookDetail bookId={bookId} onBack={() => navigate(-1)} />
    </div>
  );
};

const styles: { [key: string]: React.CSSProperties } = {
  appRoot: { minHeight: '100vh', backgroundColor: theme.colors.background, fontFamily: theme.fonts.body, color: theme.colors.textMain },
  sessionPending: { padding: '40px 20px', textAlign: 'center', color: theme.colors.textSub },
  pageContainer: { padding: '20px', maxWidth: '900px', margin: '0 auto' },
  backLink: { background: 'none', border: 'none', color: theme.colors.textSub, cursor: 'pointer', fontSize: '14px', marginBottom: '15px', padding: 0, fontFamily: theme.fonts.heading }
};

export default AppWrapper;
