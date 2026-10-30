<template>
  <div class="app-layout">
    <header class="app-header">
      <div class="logo">智</div>
      <div class="title">
        <h1>企业智能助手</h1>
        <div class="sub">Spring AI Alibaba · 多模块 Agent 统一调度</div>
      </div>
      <div class="spacer" />
      <div class="user-card">
        <el-avatar :size="36" class="user-avatar">{{ username.charAt(0).toUpperCase() }}</el-avatar>
        <div class="user-meta">
          <div class="user-name">{{ username }}</div>
          <div class="user-status"><span class="status-dot" /><span>已登录</span></div>
        </div>
        <el-button v-if="isAdmin" size="small" round class="admin-btn" @click="goAdmin">
          <el-icon><Setting /></el-icon>管理端
        </el-button>
        <el-button size="small" round class="logout-btn" @click="logout">退出</el-button>
      </div>
    </header>

    <main class="app-main">
      <!-- 左侧面板 -->
      <transition name="slide-left">
        <aside v-show="leftOpen" class="panel left-panel">
          <div class="panel-body">
            <el-button type="primary" class="new-chat-btn" @click="newChat">
              <el-icon><Plus /></el-icon>新会话
            </el-button>

            <div class="panel-section">
              <div class="section-title">控制台</div>
              <div class="ctrl-stack">
                <div v-if="isAdmin" class="ctrl-field">
                  <label class="ctrl-label">来源</label>
                  <el-select v-model="provider" placeholder="选择渠道" class="ctrl-select borderless" @change="onProviderChange">
                    <el-option v-for="c in channels" :key="c.provider" :label="(c.name || c.provider) + (c.isDefault ? ' ★' : '')" :value="c.provider" />
                  </el-select>
                </div>
                <div v-if="isAdmin" class="ctrl-field">
                  <label class="ctrl-label">模型</label>
                  <el-select v-model="model" placeholder="选择模型" class="ctrl-select borderless">
                    <el-option v-for="m in currentModels" :key="m" :label="m + (m === currentDefaultModel ? '（默认）' : '')" :value="m" />
                  </el-select>
                </div>
                <div class="ctrl-field">
                  <label class="ctrl-label">模块</label>
                  <el-select v-model="moduleKey" placeholder="自动调度" class="ctrl-select borderless">
                    <el-option label="自动调度 ★" value="" />
                    <el-option v-for="m in modules" :key="m.key" :label="m.displayName + '（' + m.agentName + '）'" :value="m.key" />
                  </el-select>
                </div>
              </div>
              <div v-if="!isAdmin" class="panel-tip">模型来源与路由由平台统一管控（管理员可在管理端配置渠道、熔断与降级策略）</div>
              <div v-if="warnText" class="warn-banner">{{ warnText }}</div>
            </div>

            <div class="panel-section thread-section">
              <div class="section-title">
                <span>历史会话</span>
                <div class="section-actions">
                  <el-button v-if="multiSelectMode && selectedThreads.size" size="small" type="danger" plain @click="deleteSelected">
                    删除({{ selectedThreads.size }})
                  </el-button>
                  <el-button size="small" :type="multiSelectMode ? 'primary' : ''" plain :class="{ active: multiSelectMode }" @click="toggleMultiSelect">
                    多选
                  </el-button>
                  <el-button size="small" text @click="loadThreads"><el-icon><Refresh /></el-icon></el-button>
                </div>
              </div>
              <div class="thread-list">
                <div v-if="!threads.length" class="empty-hint">还没有历史会话</div>
                <div v-for="t in threads" :key="t.thread_id" class="thread-item" :class="{ checked: selectedThreads.has(t.thread_id) }" @click="onThreadClick(t)">
                  <el-checkbox v-if="multiSelectMode" :model-value="selectedThreads.has(t.thread_id)" @change="toggleSelect(t.thread_id)" @click.stop />
                  <div class="thread-content">
                    <div class="thread-title" :title="t.first_question">{{ (t.first_question || '').slice(0, 20) }}{{ (t.first_question || '').length > 20 ? '…' : '' }}</div>
                    <div class="thread-meta">{{ fmtTime(t.last_at) }} · {{ t.message_count }} 条</div>
                  </div>
                  <button v-if="!multiSelectMode" class="thread-del" title="删除该会话" @click.stop="removeThread(t)">✕</button>
                </div>
              </div>
            </div>
          </div>
        </aside>
      </transition>
      <button class="grip left-grip" :class="{ closed: !leftOpen }" @click="toggleLeft">
        <el-icon><ArrowLeft v-if="leftOpen" /><ArrowRight v-else /></el-icon>
      </button>

      <!-- 主对话区 / 配送视图（子路由出口） -->
      <div class="chat-wrap">
        <router-view />
      </div>

      <!-- 右侧面板 -->
      <button class="grip right-grip" :class="{ closed: !rightOpen }" @click="toggleRight">
        <el-icon><ArrowRight v-if="rightOpen" /><ArrowLeft v-else /></el-icon>
      </button>
      <transition name="slide-right">
        <aside v-show="rightOpen" class="panel right-panel">
          <div class="panel-body">
            <div v-if="isAdmin || showMyDeliveries" class="panel-section">
              <div class="section-title">工作台</div>
              <div class="mgmt-grid">
                <button v-if="showMyDeliveries" class="mgmt-btn" :class="{ active: route.path === '/my-deliveries' }" @click="router.push('/my-deliveries')">
                  <span class="mgmt-icon">📋</span><span class="mgmt-label">我的配送单</span>
                </button>
                <button v-if="route.path !== '/'" class="mgmt-btn" @click="router.push('/')">
                  <span class="mgmt-icon">💬</span><span class="mgmt-label">返回聊天</span>
                </button>
              </div>
              <div class="panel-tip">员工 / 职位 / 仓库 / 订单 / 配送等管理功能已收敛到管理端</div>
            </div>

            <div class="panel-section">
              <div class="section-title">快捷指令</div>
              <div class="quick-list">
                <button v-for="q in quickCommands" :key="q" class="quick-btn" :title="q" @click="sendQuick(q)">{{ q }}</button>
              </div>
            </div>
          </div>
        </aside>
      </transition>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { Plus, Refresh, ArrowLeft, ArrowRight, Setting } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { useChatStore } from '@/stores/chat';
import { channels as fetchChannels, modules as fetchModules, threads as fetchThreads, deleteThread } from '@/api/chat';
import { fmtTime } from '@/utils/time';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const chatStore = useChatStore();

const channels = ref([]);
const modules = ref([]);
// 渠道/模型/模块选择代理到 chatStore，ChatPanel（子路由组件）从 store 读取
const provider = computed({ get: () => chatStore.provider, set: v => { chatStore.provider = v; } });
const model = computed({ get: () => chatStore.model, set: v => { chatStore.model = v; } });
const moduleKey = computed({ get: () => chatStore.moduleKey, set: v => { chatStore.moduleKey = v; } });
const threads = ref([]);
const selectedThreads = ref(new Set());
const multiSelectMode = ref(false);
const leftOpen = ref(true);
const rightOpen = ref(true);

const username = computed(() => authStore.username || '用户');

/** 系统管理员才显示管理端入口 */
const isAdmin = computed(() => authStore.roles.includes('ADMIN'));
const showMyDeliveries = computed(() => authStore.roles.includes('COURIER') || authStore.roles.includes('ADMIN'));
const quickCommands = [
  '现在公司有哪些职位？', '列出所有在职员工', '新增一个职位：测试工程师，技术部，12k-18k',
  '目前有哪些仓库？', '查一下各商品的库存情况', '有哪些商品低于安全库存？',
  '批量入库到主仓：防化服 30件、投影仪 12台、无线鼠标 25个、A4复印纸 40包',
  '盘点一下仓库的资产', '列出全部资产清单', '查一下陈晓明的个人物品栏'
];

const currentChannel = computed(() => channels.value.find(c => c.provider === provider.value) || null);
const currentModels = computed(() => currentChannel.value?.models || []);
const currentDefaultModel = computed(() => currentChannel.value?.defaultModel || '');
const warnText = computed(() => {
  if (!currentChannel.value || currentChannel.value.keyConfigured) return '';
  return `渠道「${currentChannel.value.name || currentChannel.value.provider}」未配置密钥`;
});

function onProviderChange() {
  const ch = currentChannel.value;
  if (ch && ch.defaultModel && (ch.models || []).includes(ch.defaultModel)) model.value = ch.defaultModel;
}

async function loadChannels() {
  channels.value = await fetchChannels();
  const def = channels.value.find(c => c.isDefault) || channels.value[0];
  if (def) { provider.value = def.provider; onProviderChange(); }
}

async function loadModules() {
  try { modules.value = await fetchModules(); } catch (e) { /* ignore */ }
  moduleKey.value = '';
}

async function loadThreads() {
  try { threads.value = await fetchThreads(30); } catch (e) { threads.value = []; }
  selectedThreads.value = new Set();
}

function toggleMultiSelect() {
  multiSelectMode.value = !multiSelectMode.value;
  selectedThreads.value = new Set();
}

function toggleSelect(tid) {
  const s = new Set(selectedThreads.value);
  if (s.has(tid)) s.delete(tid); else s.add(tid);
  selectedThreads.value = s;
}

function onThreadClick(t) {
  if (multiSelectMode.value) toggleSelect(t.thread_id);
  else openThread(t.thread_id);
}

async function deleteSelected() {
  if (!selectedThreads.value.size) return;
  try {
    await ElMessageBox.confirm(`删除选中的 ${selectedThreads.value.size} 个会话？`, '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch (e) { return; }
  for (const tid of selectedThreads.value) {
    try { await deleteThread(tid); } catch (e) { /* ignore */ }
    chatStore.dispatch('delete-thread', tid);
  }
  selectedThreads.value = new Set();
  multiSelectMode.value = false;
  loadThreads();
}

/** 单行删除按钮：删除单个会话 */
async function removeThread(t) {
  try {
    await ElMessageBox.confirm(`删除会话「${(t.first_question || '').slice(0, 16)}…」？`, '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch (e) { return; }
  try { await deleteThread(t.thread_id); } catch (e) { /* ignore */ }
  chatStore.dispatch('delete-thread', t.thread_id);
  loadThreads();
}

// 壳上的操作经 chatStore 命令桥发给 ChatPanel；不在聊天页时先跳回
function goChat() { if (route.path !== '/') router.push('/'); }
function openThread(tid) { goChat(); chatStore.dispatch('open-thread', tid); }
function newChat() { goChat(); chatStore.dispatch('new-chat'); }
function sendQuick(q) { goChat(); chatStore.dispatch('send', q); }
function goAdmin() { router.push('/admin'); }
function toggleLeft() { leftOpen.value = !leftOpen.value; savePanelState(); }
function toggleRight() { rightOpen.value = !rightOpen.value; savePanelState(); }
async function logout() {
  try {
    await ElMessageBox.confirm('退出登录？', '退出确认', { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' });
  } catch (e) { return; }
  authStore.logout();
  router.push('/login');
}

const PANEL_KEY = 'adk_panels';
function savePanelState() { try { localStorage.setItem(PANEL_KEY, JSON.stringify({ left: leftOpen.value, right: rightOpen.value })); } catch (e) { /* ignore */ } }
function restorePanelState() {
  let st = null;
  try { st = JSON.parse(localStorage.getItem(PANEL_KEY) || 'null'); } catch (e) { /* ignore */ }
  leftOpen.value = st ? st.left !== false : true;
  rightOpen.value = st ? st.right !== false : true;
}

onMounted(async () => { restorePanelState(); await loadChannels(); await loadModules(); await loadThreads(); });
// ChatPanel 载入/新增会话后经 store 通知刷新历史列表
watch(() => chatStore.threadsVersion, loadThreads);
</script>

<style scoped>
.app-layout { display: flex; flex-direction: column; height: 100vh; height: 100dvh; overflow: hidden; background: #f6f7f9; }
.app-header { background: #fff; border-bottom: 1px solid #e5e7eb; padding: 10px 20px; display: flex; align-items: center; gap: 14px; flex-shrink: 0; }
.logo { width: 38px; height: 38px; border-radius: 10px; background: linear-gradient(135deg, #4f6ef7, #7c5cf6); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 700; box-shadow: 0 4px 12px rgba(79,110,247,.28); }
.title h1 { font-size: 16px; margin: 0; font-weight: 600; color: #111827; letter-spacing: .01em; }
.title .sub { color: #9ca3af; font-size: 12px; }
.spacer { flex: 1; }

/* 顶部用户卡片（参考主流产品：头像 + 名称 + 状态点 + 退出） */
.user-card { display: flex; align-items: center; gap: 10px; border: 1px solid #e5e7eb; border-radius: 999px; padding: 5px 6px 5px 6px; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.04); }
.user-avatar { background: linear-gradient(135deg, #10b981, #059669); color: #fff; font-weight: 700; font-size: 15px; flex-shrink: 0; }
.user-meta { line-height: 1.25; padding-right: 2px; }
.user-name { font-size: 13px; font-weight: 600; color: #111827; }
.user-status { font-size: 11px; color: #6b7280; display: flex; align-items: center; gap: 4px; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #10b981; display: inline-block; box-shadow: 0 0 0 3px rgba(16,185,129,.15); }
.logout-btn { color: #6b7280; border-color: #e5e7eb; }
.logout-btn:hover { color: #ef4444; border-color: #fecaca; background: #fef2f2; }
.admin-btn { color: #4f6ef7; border-color: #c7d2fe; background: #eef2ff; }
.admin-btn:hover { color: #fff; background: #4f6ef7; border-color: #4f6ef7; }
.panel-tip { font-size: 11px; color: #9ca3af; line-height: 1.6; margin-top: 8px; }

.app-main { flex: 1; display: flex; min-height: 0; position: relative; overflow: hidden; }
.panel { width: 300px; background: #fff; overflow: hidden; flex-shrink: 0; }
.panel-body { width: 300px; height: 100%; padding: 16px; overflow: hidden; display: flex; flex-direction: column; }
.left-panel { border-right: 1px solid #e5e7eb; }
.right-panel { border-left: 1px solid #e5e7eb; }
.grip { position: absolute; top: 50%; transform: translateY(-50%); z-index: 10; width: 24px; height: 48px; padding: 0; display: flex; align-items: center; justify-content: center; border: 1px solid #e5e7eb; border-radius: 6px; background: #fff; color: #9ca3af; cursor: pointer; box-shadow: 0 2px 6px rgba(0,0,0,.06); transition: all .2s; }
.grip:hover { background: #f3f4f6; color: #4f6ef7; }
.left-grip { left: 288px; }
.left-grip.closed { left: 0; border-radius: 0 6px 6px 0; }
.right-grip { right: 288px; }
.right-grip.closed { right: 0; border-radius: 6px 0 0 6px; }
.chat-wrap { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #f6f7f9; overflow: hidden; }

.panel-section { margin-bottom: 20px; }
.thread-section { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.section-title { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: .05em; color: #9ca3af; margin-bottom: 10px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; }
.section-actions { display: flex; align-items: center; gap: 4px; }
.ctrl-stack { display: flex; flex-direction: column; gap: 10px; }
.ctrl-field { display: flex; flex-direction: column; gap: 4px; }
.ctrl-label { font-size: 12px; color: #6b7280; padding-left: 2px; }
.ctrl-select { width: 100%; }
/* 无边框简洁风格：去边框线，浅灰填充 */
.borderless :deep(.el-select__wrapper) { box-shadow: none; background: #f3f4f6; border-radius: 8px; }
.borderless :deep(.el-select__wrapper:hover) { background: #eef0f3; }
.borderless :deep(.el-select__wrapper.is-focused) { background: #eef2ff; }
.new-chat-btn { width: 100%; margin-bottom: 18px; }
.warn-banner { background: #fef3c7; border: 1px solid #fcd34d; color: #92400e; font-size: 12px; border-radius: 8px; padding: 8px 10px; margin-top: 10px; }
.empty-hint { font-size: 12px; color: #9ca3af; padding: 8px 0; }

.thread-list { display: flex; flex-direction: column; gap: 2px; flex: 1; min-height: 0; overflow-y: auto; }
.thread-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px; cursor: pointer; transition: background .15s; }
.thread-item:hover { background: #f3f4f6; }
.thread-item.checked { background: #eef2ff; }
.thread-content { flex: 1; min-width: 0; }
.thread-del { flex-shrink: 0; width: 20px; height: 20px; border: none; border-radius: 5px; background: transparent; color: #c0c4cc; font-size: 12px; line-height: 1; cursor: pointer; opacity: 0; transition: all .15s; }
.thread-item:hover .thread-del { opacity: 1; }
.thread-del:hover { background: #fef2f2; color: #dc2626; }
.thread-title { font-size: 13px; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.thread-meta { font-size: 11px; color: #9ca3af; margin-top: 2px; }

/* 管理模块：2列网格，等宽等高对齐 */
.mgmt-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.mgmt-btn { display: flex; align-items: center; justify-content: center; gap: 6px; font-size: 13px; color: #374151; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 4px; cursor: pointer; transition: all .15s; }
.mgmt-btn:hover { border-color: #4f6ef7; color: #4f6ef7; background: #eef2ff; }
.mgmt-btn.active { border-color: #4f6ef7; color: #4f6ef7; background: #eef2ff; }
.mgmt-icon { font-size: 14px; }
.mgmt-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 快捷指令：单列列表，左对齐 + 超出省略 */
.quick-list { display: flex; flex-direction: column; gap: 6px; }
.quick-btn { display: block; width: 100%; text-align: left; font-size: 12px; color: #6b7280; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 9px 12px; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; transition: all .15s; }
.quick-btn:hover { color: #4f6ef7; border-color: #c7d2fe; background: #eef2ff; }

.slide-left-enter-active, .slide-left-leave-active { transition: all .25s ease; }
.slide-left-enter-from, .slide-left-leave-to { transform: translateX(-100%); opacity: 0; }
.slide-right-enter-active, .slide-right-leave-active { transition: all .25s ease; }
.slide-right-enter-from, .slide-right-leave-to { transform: translateX(100%); opacity: 0; }
</style>
