// 路由：/login 公开；客户端走 AppLayout 壳；管理端走 AdminLayout 壳（仅 ADMIN）。
// 用 hash 模式——生产环境由 Spring Boot 直接托管静态产物，无 SPA fallback 配置，
// hash 路由下深链（刷新/直达）不需要后端配合。
import { createRouter, createWebHashHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const routes = [
  {
    path: '/login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      { path: '', name: 'chat', component: () => import('@/views/ChatView.vue') },
      {
        path: 'my-deliveries',
        name: 'my-deliveries',
        component: () => import('@/features/delivery/MyDeliveries.vue'),
        meta: { roles: ['ADMIN', 'COURIER'] }
      }
    ]
  },
  // ==================== 管理端（仅系统管理员） ====================
  {
    path: '/admin',
    component: () => import('@/admin/AdminLayout.vue'),
    meta: { roles: ['ADMIN'] },
    children: [
      { path: '', name: 'admin-dashboard', component: () => import('@/admin/views/DashboardView.vue') },
      { path: 'channels', name: 'admin-channels', component: () => import('@/admin/views/ChannelView.vue') },
      { path: 'circuit-breakers', name: 'admin-breakers', component: () => import('@/admin/views/CircuitBreakerView.vue') },
      { path: 'audit', name: 'admin-audit', component: () => import('@/admin/views/CallAuditView.vue') },
      { path: 'business/hr', name: 'admin-hr', component: () => import('@/admin/views/BusinessView.vue'), meta: { group: 'hr' } },
      { path: 'business/wms', name: 'admin-wms', component: () => import('@/admin/views/BusinessView.vue'), meta: { group: 'wms' } },
      { path: 'business/order', name: 'admin-order', component: () => import('@/admin/views/BusinessView.vue'), meta: { group: 'order' } },
      { path: 'business/purchase', name: 'admin-purchase', component: () => import('@/admin/views/BusinessView.vue'), meta: { group: 'purchase' } },
      { path: 'business/delivery', name: 'admin-delivery', component: () => import('@/admin/views/DeliveryView.vue') },
      { path: 'users', name: 'admin-users', component: () => import('@/admin/views/UserView.vue') },
      { path: 'tools', name: 'admin-tools', component: () => import('@/admin/views/ToolView.vue') },
      { path: 'modules', name: 'admin-modules', component: () => import('@/admin/views/ModuleView.vue') },
      { path: 'approvals', name: 'admin-approvals', component: () => import('@/admin/views/ApprovalsView.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
];

const router = createRouter({
  history: createWebHashHistory(),
  routes
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.public) {
    return auth.isAuthed ? '/' : true;
  }
  if (!auth.isAuthed) return '/login';
  // 角色门：此前只有按钮可见性控制（且因 checkAuth bug 实际失效），这里补上真正的拦截。
  // 角色是异步拉取的，直接进管理端深层链接时要等它回来再判定。
  if (to.meta.roles) {
    await auth.ensureRoles();
    if (!to.meta.roles.some(r => auth.roles.includes(r))) return '/';
  }
  return true;
});

export default router;
