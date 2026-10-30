<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="logo">智</div>
        <h1>企业智能助手</h1>
        <div class="sub">Spring AI Alibaba · 多模块 Agent 统一调度</div>
      </div>

      <el-form class="login-form" size="large" @submit.prevent="submit">
        <el-input
          v-model="username"
          placeholder="用户名"
          :prefix-icon="User"
          autocomplete="username"
          autofocus
        />
        <el-input
          v-model="password"
          type="password"
          placeholder="密码"
          :prefix-icon="Lock"
          show-password
          autocomplete="current-password"
          @keyup.enter="submit"
        />
        <div v-if="error" class="login-error">{{ error }}</div>
        <el-button type="primary" class="login-btn" :loading="loading" native-type="submit">
          登 录
        </el-button>
      </el-form>
    </div>
    <div class="login-footer">HR Agent · 人员职位管理</div>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { User, Lock } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const authStore = useAuthStore();

const username = ref('');
const password = ref('');
const loading = ref(false);
const error = ref('');

async function submit() {
  if (loading.value) return;
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码';
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    await authStore.login(username.value.trim(), password.value);
    router.push('/');
  } catch (e) {
    error.value = e.message || '登录失败';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
/* 与主界面同一套设计语言：#f6f7f9 底、白卡、#e5e7eb 描边、#4f6ef7→#7c5cf6 渐变 */
.login-page {
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #f6f7f9;
  overflow: hidden;
}
.login-card {
  width: 360px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  box-shadow: 0 8px 30px rgba(17, 24, 39, .06);
  padding: 34px 32px 26px;
}
.login-brand { text-align: center; margin-bottom: 24px; }
.logo {
  width: 52px;
  height: 52px;
  margin: 0 auto 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, #4f6ef7, #7c5cf6);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 700;
  box-shadow: 0 6px 16px rgba(79, 110, 247, .28);
}
.login-brand h1 { font-size: 17px; margin: 0 0 4px; font-weight: 600; color: #111827; letter-spacing: .01em; }
.login-brand .sub { color: #9ca3af; font-size: 12px; }

.login-form { display: flex; flex-direction: column; gap: 14px; }
.login-error {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #dc2626;
  font-size: 12px;
  border-radius: 8px;
  padding: 8px 10px;
}
.login-btn { width: 100%; }

.login-footer { margin-top: 18px; font-size: 12px; color: #b6bac2; }
</style>
