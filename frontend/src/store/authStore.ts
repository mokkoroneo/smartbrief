import { create } from 'zustand';
import { apiClient } from '../utils/apiClient';

// 1. Storeの「型（設計図）」を定義する
interface AuthState {
  isLoggedIn: boolean; // ★tokenではなく、ログイン状態(true/false)だけを管理
  isPremium: boolean;
  isSessionPending: boolean;
  setLoggedIn: (status: boolean) => void;
  resolveSession: (isPremium: boolean | null) => void;
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
  setLoggedIn: (status) => set({ isLoggedIn: status, isSessionPending: false }),

  resolveSession: (isPremium) => set((state) => {
    if (!state.isSessionPending) return state;
    return {
      isLoggedIn: isPremium !== null,
      isPremium: isPremium ?? false,
      isSessionPending: false,
    };
  }),

  logout: async () => {
    // ★バックエンドのCookie削除APIを呼び出して、サーバー側でログアウト処理をさせる
    await apiClient.post('/auth/logout'); 
    set({ isLoggedIn: false, isPremium: false, isSessionPending: false });
  },
}));
