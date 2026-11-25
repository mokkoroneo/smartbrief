import { create } from 'zustand';
import { apiClient } from '../utils/apiClient';

interface ResolveSessionOptions {
  afterLogin?: boolean;
  isActive?: () => boolean;
}

// 1. Storeの「型（設計図）」を定義する
interface AuthState {
  isLoggedIn: boolean; // ★tokenではなく、ログイン状態(true/false)だけを管理
  isPremium: boolean;
  isSessionPending: boolean;
  resolveSession: (options?: ResolveSessionOptions) => Promise<boolean>;
  logout: () => Promise<void>;
}

// 2. create<AuthState>() と書くことで、Zustandに型を教える
export const useAuthStore = create<AuthState>()((set) => ({
  // --- 状態 (State) ---
  // 初期状態はLocalStorageに頼らず、アプリ起動時に /auth/me を叩いて判定する
  isLoggedIn: false,
  isPremium: false,
  isSessionPending: true,

  // --- アクション (Actions) ---
  resolveSession: async ({ afterLogin = false, isActive } = {}) => {
    const res = await apiClient.get<{ isPremium: boolean }>(
      '/auth/me',
      afterLogin ? { logoutOnUnauthorized: false } : undefined,
    );
    const isPremium = res.ok && res.data ? res.data.isPremium : null;
    if (isActive?.() === false || (afterLogin && typeof isPremium !== 'boolean')) return false;

    set((state) => {
      if (!afterLogin && !state.isSessionPending) return state;
      return {
        isLoggedIn: isPremium !== null,
        isPremium: isPremium ?? false,
        isSessionPending: false,
      };
    });
    return isPremium !== null;
  },

  logout: async () => {
    // ★バックエンドのCookie削除APIを呼び出して、サーバー側でログアウト処理をさせる
    await apiClient.post('/auth/logout'); 
    set({ isLoggedIn: false, isPremium: false, isSessionPending: false });
  },
}));
