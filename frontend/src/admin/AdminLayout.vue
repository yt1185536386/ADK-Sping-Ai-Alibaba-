<template>
  <el-container class="admin-layout">
    <!-- 顶部：系统名称 + 用户信息 -->
    <el-header class="admin-header">
      <div class="brand">
        <div class="brand-logo">智</div>
        <div class="brand-text">
          <div class="brand-name">企业智能助手 · 管理端</div>
          <div class="brand-sub">HR-Agent Admin Console</div>
        </div>
      </div>

      <div class="header-right">
        <el-button class="back-btn" size="small" @click="goClient">
          <el-icon><ChatDotRound /></el-icon>返回客户端
        </el-button>
        <el-divider direction="vertical" />
        <el-dropdown trigger="click" @command="onCommand">
          <div class="user-card">
            <el-avatar :size="30" class="user-avatar">{{ initial }}</el-avatar>
            <div class="user-meta">
              <div class="user-name">{{ authStore.username || '管理员' }}</div>
              <div class="user-role">{{ roleLabel }}</div>
            </div>
            <el-icon class="arrow"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="client" :icon="ChatDotRound">返回客户端</el-dropdown-item>
              <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container class="admin-body">
      <!-- 左侧菜单 -->
      <el-aside width="220px" class="admin-aside">
        <el-menu
          :default-active="activePath"
          class="admin-menu"
          router
          background-color="#ffffff"
          text-color="#4b5563"
          active-text-color="#4f6ef7"
        >
          <template v-for="group in menus" :key="group.title || group.path">
            <!-- 一级菜单（无子项） -->
            <el-menu-item v-if="!group.children" :index="group.path">
              <el-icon><component :is="group.icon" /></el-icon>
              <span>{{ group.title }}</span>
            </el-menu-item>
            <!-- 分组菜单 -->
            <el-sub-menu v-else :index="group.title">
              <template #title>
                <el-icon><component :is="group.icon" /></el-icon>
                <span>{{ group.title }}</span>
              </template>
              <el-menu-item v-for="child in group.children" :key="child.path" :index="child.path">
                <el-icon><component :is="child.icon" /></el-icon>
                <span>{{ child.title }}</span>
              </el-menu-item>
            </el-sub-menu>
          </template>
        </el-menu>
      </el-aside>

      <!-- 右侧内容区 -->
      <el-main class="admin-main">
        <!-- 同一路由组件承载多个菜单（如业务数据 4 个子页共用 BusinessView）时，
             用 fullPath 做 key 强制重建组件，否则切换菜单只换标题不重载数据 -->
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="route.fullPath" />
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import {
  ArrowDown, Avatar, Box, ChatDotRound, Connection, Cpu, Document,
  Goods, Lightning, Odometer, Setting, SwitchButton, Tickets, Tools, User, Van
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

/** 左侧菜单（按企业后台的信息架构分组：概览 → 模型 → 业务数据 → 权限 → 系统） */
const menus = [
  { path: '/admin', title: '概览', icon: Odometer },
  {
    title: '模型管理',
    icon: Connection,
    children: [
      { path: '/admin/channels', title: '模型来源管理', icon: Connection },
      { path: '/admin/circuit-breakers', title: '熔断降级管理', icon: Lightning },
      { path: '/admin/audit', title: '调用审计', icon: Document }
    ]
  },
  {
    title: '业务数据',
    icon: Box,
    children: [
      { path: '/admin/business/hr', title: '员工与职位', icon: User },
      { path: '/admin/business/wms', title: '仓储与库存', icon: Box },
      { path: '/admin/business/order', title: '订单与配送', icon: Van },
      { path: '/admin/business/purchase', title: '采购与退货', icon: Goods }
    ]
  },
  {
    title: '权限管理',
    icon: Avatar,
    children: [
      { path: '/admin/users', title: '用户与角色', icon: Avatar }
    ]
  },
  {
    title: '系统配置',
    icon: Setting,
    children: [
      { path: '/admin/tools', title: '外部工具配置', icon: Tools },
      { path: '/admin/modules', title: '模块与 Agent', icon: Cpu },
      { path: '/admin/approvals', title: '审批与会话审计', icon: Tickets }
    ]
  }
];

const activePath = computed(() => route.path);
const initial = computed(() => (authStore.username || 'A').charAt(0).toUpperCase());
const roleLabel = computed(() => {
  const roles = authStore.roles || [];
  if (roles.includes('ADMIN')) return '系统管理员';
  if (roles.includes('APPROVER')) return '审批员';
  if (roles.includes('COURIER')) return '配送员';
  return '普通用户';
});

function goClient() {
  router.push('/');
}

async function logout() {
  try {
    await ElMessageBox.confirm('退出登录？', '退出确认', { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' });
  } catch (e) { return; }
  authStore.logout();
  router.push('/login');
}

function onCommand(cmd) {
  if (cmd === 'client') goClient();
  else if (cmd === 'logout') logout();
}
</script>

<style scoped>
.admin-layout { height: 100vh; height: 100dvh; overflow: hidden; }

/* 顶部栏 */
.admin-header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
  flex-shrink: 0;
}
.brand { display: flex; align-items: center; gap: 10px; }
.brand-logo {
  width: 32px; height: 32px; border-radius: 8px;
  background: linear-gradient(135deg, #4f6ef7, #7c5cf6);
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 15px;
}
.brand-name { font-size: 15px; font-weight: 600; color: #111827; line-height: 1.2; }
.brand-sub { font-size: 11px; color: #9ca3af; }
.header-right { display: flex; align-items: center; gap: 6px; }
.user-card {
  display: flex; align-items: center; gap: 8px; cursor: pointer;
  border: 1px solid #e5e7eb; border-radius: 999px; padding: 4px 10px 4px 4px; background: #fff;
}
.user-card:hover { border-color: #c7d2fe; background: #f8faff; }
.user-avatar { background: linear-gradient(135deg, #10b981, #059669); color: #fff; font-weight: 700; }
.user-meta { line-height: 1.2; }
.user-name { font-size: 13px; font-weight: 600; color: #111827; }
.user-role { font-size: 11px; color: #9ca3af; }
.arrow { color: #9ca3af; font-size: 12px; }

/* 主体：占满窗口剩余高度，左右两栏都在自己内部滚动 */
.admin-body {
  flex: 1;
  min-height: 0;        /* flex 子元素允许收缩，否则溢出时不出现滚动条 */
  overflow: hidden;
}

/* 侧边菜单：高度=剩余高度，内部滚动，宽度 100% 占满侧栏 */
.admin-aside {
  background: #fff;
  border-right: 1px solid #e5e7eb;
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  flex-shrink: 0;
}
.admin-menu { border-right: none; width: 100%; }
.admin-menu :deep(.el-menu-item.is-active) {
  background: #eef2ff;
  font-weight: 600;
}
.admin-menu :deep(.el-sub-menu__title:hover) { background: #f5f7fa; }

/* 内容区：高度=剩余高度，内部滚动，宽度 100% 占满剩余空间 */
.admin-main {
  background: #f5f7fa;
  padding: 16px;
  height: 100%;
  width: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  box-sizing: border-box;
}
</style>
