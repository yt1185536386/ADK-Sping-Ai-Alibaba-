// 登录态 store：用户名/角色/认证状态的唯一权威来源。
// 凭据本身仍存 localStorage（api/auth.js），store 是其响应式视图。
import { defineStore } from 'pinia';
import { getAuth, setAuth, clearAuth, login as apiLogin, checkAuth } from '@/api/auth';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    username: '',
    roles: [],
    isAuthed: false,
    authChecked: false, // 首次恢复是否完成（路由守卫依赖，须在 router 安装前同步置位）
    rolesLoaded: false  // 角色是否已拉取（角色门依赖）
  }),
  actions: {
    /** 页面加载时从 localStorage 同步恢复登录态；后台校验失败由 401 回调踢出 */
    restore() {
      const auth = getAuth();
      if (auth) {
        this.isAuthed = true;
        this.username = auth.username || '';
        this.checkSession(); // 后台校验 + 拉取角色，不阻塞首屏
      }
      this.authChecked = true;
    },
    async login(username, password) {
      const me = await apiLogin(username, password);
      setAuth(username, password);
      this.username = username;
      this.roles = me?.roles || [];
      this.rolesLoaded = true;
      this.isAuthed = true;
    },
    /** 用本地凭据校验会话并刷新角色（修复了原 AppLayout 误调 api.checkAuth 导致角色永远为空的问题） */
    async checkSession() {
      try {
        const me = await checkAuth();
        if (!me) { this.logout(); return; }
        this.username = me.username || this.username;
        this.roles = me.roles || [];
        this.rolesLoaded = true;
      } catch (e) { /* 网络抖动不踢人 */ }
    },
    /**
     * 确保角色已就绪：角色门（管理端 / 配送管理）依赖 roles，
     * 而角色是异步拉取的——首次直达深层链接时必须等它回来再判定。
     */
    async ensureRoles() {
      if (this.rolesLoaded) return;
      await this.checkSession();
    },
    logout() {
      clearAuth();
      this.username = '';
      this.roles = [];
      this.rolesLoaded = false;
      this.isAuthed = false;
    }
  }
});
