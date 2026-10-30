<template>
  <div class="chat-panel">
    <!-- 欢迎页 -->
    <div v-if="!messages.length" class="hero">
      <div class="hero-logo">
        <span>智</span>
      </div>
      <h2 class="hero-title">有什么可以帮你的？</h2>
      <div class="hero-sub">基于企业的人员、仓库、订单数据问答与操作，写操作需人工确认后执行</div>
      <div class="hero-sugg">
        <button v-for="q in heroSuggestions" :key="q" class="sugg-btn" @click="send(q)">
          <el-icon><ChatDotRound /></el-icon>{{ q }}
        </button>
      </div>
    </div>

    <!-- 消息列表 -->
    <div ref="msgBox" class="messages" @scroll="onScroll">
      <div v-for="m in messages" :key="m.key" class="msg" :class="m.role">
        <div class="avatar" :class="m.role">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="msg-body">
          <div class="bubble" :class="{ err: m.isErr, streaming: m.streaming, thinking: m.rawText === '思考中…' }" v-html="render(m.displayText)"></div>
          <div v-if="m.streaming" class="streaming-indicator">
            <span class="dot"></span><span class="dot"></span><span class="dot"></span>
          </div>

          <AmapMap v-for="(spec, i) in m.amapSpecs" :key="i + '-' + (m.refreshTick || 0)" :spec="spec" />

          <div v-if="m.role === 'bot' && m.amapSpecs && m.amapSpecs.length" class="map-open-row">
            <button class="map-open-btn" :disabled="m.mapLoading" @click="refreshMap(m)">
              <el-icon><Location /></el-icon>{{ m.mapLoading ? '正在生成…' : '打开地图' }}
            </button>
          </div>

          <div v-if="m.meta" class="meta">{{ m.meta }}</div>

          <div class="msg-actions" :class="{ user: m.role === 'user' }">
            <button class="msg-act" @click="copyText(m.rawText)" title="复制">
              <el-icon><CopyDocument /></el-icon>
            </button>
            <template v-if="m.role === 'user'">
              <button class="msg-act" @click="resend(m)" title="重新发送">
                <el-icon><Refresh /></el-icon>
              </button>
              <button class="msg-act danger" @click="deleteMsgPair(m)" title="删除">
                <el-icon><Delete /></el-icon>
              </button>
            </template>
            <template v-else>
              <button class="msg-act" @click="regenerate(m)" title="重新生成">
                <el-icon><RefreshRight /></el-icon>
              </button>
            </template>
          </div>
        </div>
      </div>

      <!-- 审批卡片 -->
      <ApprovalCard
        v-if="approvalData"
        ref="approvalRef"
        :data="approvalData"
        @done="onApprovalDone"
        @voided="onApprovalVoided"
      />

      <!-- 出库单卡片 -->
      <OutboundFormCard
        v-if="outboundFormData"
        :data="outboundFormData"
        @done="onOutboundDone"
        @cancel="onOutboundCancel"
      />

      <!-- 配送单卡片 -->
      <DeliveryFormCard
        v-if="deliveryFormData"
        :data="deliveryFormData"
        @done="onDeliveryDone"
        @cancel="onDeliveryCancel"
      />
    </div>

    <!-- 输入区 -->
    <footer class="input-area">
      <div v-if="!messages.length" class="chips">
        <button v-for="q in chips" :key="q" class="chip" @click="send(q)">{{ q }}</button>
      </div>
      <div class="input-wrapper">
        <el-input
          v-model="input"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 8 }"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          class="chat-input"
          @keydown.enter.exact.prevent="send()"
        />
        <el-button
          v-if="sending"
          type="danger"
          circle
          class="send-btn"
          title="停止生成"
          @click="stopSending"
        >
          <span class="stop-icon"></span>
        </el-button>
        <el-button
          v-else
          type="primary"
          circle
          class="send-btn"
          :disabled="!input.trim()"
          @click="send()"
        >
          <el-icon><Top /></el-icon>
        </el-button>
      </div>
      <div class="foot-note">
        <span>{{ statusText }}</span> · 回答由 AI 生成，内容仅供参考
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick } from 'vue';
import { ElMessage } from 'element-plus';
import { Top, ChatDotRound, CopyDocument, Refresh, Delete, RefreshRight, Location } from '@element-plus/icons-vue';
import { postJson } from '@/api/http';
import { streamChat } from '@/features/chat/useChatStream';
import { threadMessages, deleteMessage } from '@/api/chat';
import { amapRefresh } from '@/api/amap';
import { extractAmap, renderMarkdown } from '@/utils/text';
import AmapMap from '@/components/AmapMap.vue';
import ApprovalCard from './ApprovalCard.vue';
import OutboundFormCard from './OutboundFormCard.vue';
import DeliveryFormCard from './DeliveryFormCard.vue';

const props = defineProps({
  provider: String,
  model: String,
  moduleKey: String
});
const emit = defineEmits(['thread-loaded', 'approval-voided']);

const input = ref('');
const messages = ref([]);
const sending = ref(false);
/** 当前流式请求的中止控制器：点「停止」时 abort，断开 SSE 连接 */
const aborter = ref(null);

/** 停止当前问题的生成：断开连接，同时通知后端中断模型调用（不再消耗 Token） */
function stopSending() {
  if (!aborter.value) return;
  aborter.value.abort();
  // 后端中断该会话的生成线程；失败不影响前端已停止的状态
  postJson('/api/chat/stop', { threadId: threadId.value }).catch(() => {});
}

const statusText = ref('就绪');
const threadId = ref('web-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7));
const approvalData = ref(null);
const approvalRef = ref(null);
const outboundFormData = ref(null);
const deliveryFormData = ref(null);
const msgBox = ref(null);
const autoScroll = ref(true);

let msgKey = 0;

const heroSuggestions = [
  '现在公司有哪些职位？', '查一下各商品的库存情况', '列出最近的订单以及配送状态', '有哪些商品低于安全库存？'
];
const chips = [
  '列出所有在职员工', '目前有哪些仓库？', '查一下各商品的库存情况', '有哪些商品低于安全库存？',
  '列出最近的订单以及配送状态', '盘点一下仓库的资产'
];

function render(text) { return renderMarkdown(text || ''); }

function scrollBottom() { if (autoScroll.value && msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight; }
function onScroll() {
  if (!msgBox.value) return;
  const { scrollTop, scrollHeight, clientHeight } = msgBox.value;
  autoScroll.value = scrollHeight - scrollTop - clientHeight < 60;
}

function addMsg(role, text, meta = '', isErr = false, msgId = null, amapSpecs = []) {
  const m = reactive({ key: ++msgKey, role, rawText: text, displayText: text, meta, isErr, msgId, amapSpecs, streaming: false, pairUserText: '' });
  messages.value.push(m);
  nextTick(scrollBottom);
  return m;
}

/** "打开地图"按钮：用消息标记里保存的参数重新调用高德工具，生成最新地图数据 */
async function refreshMap(m) {
  const specs = m.amapSpecs || [];
  if (!specs.length || m.mapLoading) return;
  m.mapLoading = true;
  statusText.value = '正在重新生成地图…';
  try {
    const newSpecs = [];
    for (const spec of specs) {
      try {
        let res = null;
        if (spec.type === 'route') {
          res = await amapRefresh({ type: 'route', origin: spec.origin, destination: spec.destination, city: spec.city, mode: spec.mode });
        } else if (spec.type === 'poi') {
          const kw = ((spec.pois && spec.pois[0] && spec.pois[0].name) || '').replace(/（.*?）$/, '').trim();
          res = await amapRefresh({ type: 'poi', keywords: kw, city: spec.city });
        }
        if (res && Array.isArray(res.amap)) {
          res.amap.forEach(s => { try { newSpecs.push(typeof s === 'string' ? JSON.parse(s) : s); } catch (e) { /* ignore */ } });
        }
      } catch (e) { /* 单个刷新失败保留原 spec */ }
    }
    m.refreshTick = (m.refreshTick || 0) + 1;
    if (newSpecs.length) m.amapSpecs = newSpecs;
    statusText.value = newSpecs.length ? '地图已更新（最新数据）' : '地图刷新失败';
  } catch (e) {
    statusText.value = '地图刷新失败：' + e.message;
  } finally {
    m.mapLoading = false;
  }
}

async function copyText(text) {
  try { await navigator.clipboard.writeText(text); ElMessage.success('已复制'); } catch (e) { ElMessage.error('复制失败'); }
}
function resend(m) { input.value = m.rawText; send(); }
async function deleteMsgPair(m) {
  if (m.msgId) { try { await deleteMessage(m.msgId); } catch (e) { /* ignore */ } }
  const idx = messages.value.findIndex(x => x.key === m.key);
  if (idx >= 0) { messages.value.splice(idx, 1); if (messages.value[idx]?.role === 'bot') messages.value.splice(idx, 1); }
}
async function regenerate(botMsg) {
  if (sending.value) return;
  const question = (botMsg.pairUserText || '').trim();
  if (!question) { statusText.value = '找不到对应的提问，无法重新生成'; return; }
  if (botMsg.msgId) { try { await deleteMessage(botMsg.msgId); } catch (e) { /* ignore */ } }
  const idx = messages.value.findIndex(x => x.key === botMsg.key);
  if (idx >= 0) messages.value.splice(idx, 1);
  input.value = question;
  send();
}

function onApprovalDone(out) {
  approvalData.value = null;
  if (out.status === 'pending_approval') {
    addMsg('bot', out.reply || '还有操作需要确认。');
    approvalData.value = out;
    statusText.value = '等待人工确认';
  } else {
    addMsg('bot', out.reply || '(无回复)', `${out.module || ''} · 人工确认后执行`, out.status === 'error');
    statusText.value = '就绪';
  }
}
function onApprovalVoided() { emit('approval-voided'); }

function onOutboundDone(res) {
  outboundFormData.value = null;
  addMsg('bot', res.reply || '出库完成。', '仓库管理 · 出库单确认执行', res.status === 'error');
  statusText.value = '就绪';
}
function onOutboundCancel() {
  outboundFormData.value = null;
  addMsg('bot', '已取消本次出库。');
  statusText.value = '就绪';
}
function onDeliveryDone(res) {
  deliveryFormData.value = null;
  addMsg('bot', res.reply || '配送单已创建。', '订单管理 · 配送单确认执行', res.status === 'error');
  statusText.value = '就绪';
}
function onDeliveryCancel() {
  deliveryFormData.value = null;
  addMsg('bot', '已取消本次配送。');
  statusText.value = '就绪';
}

async function send(text) {
  const q = (text || input.value).trim();
  if (!q || sending.value) return;
  if (approvalData.value && /^(确认|确定|同意|批准|可以|行|好|好的|是|是的|yes|ok|y)$/i.test(q)) {
    addMsg('user', q);
    approvalRef.value?.approve();
    return;
  }
  if (approvalData.value) approvalRef.value?.voidCard();
  if (outboundFormData.value) outboundFormData.value = null;
  if (deliveryFormData.value) deliveryFormData.value = null;
  input.value = '';
  sending.value = true;
  statusText.value = `请求中：${props.provider || ''} / ${props.model || ''} / ${props.moduleKey || '自动调度'}`;
  const userMsg = addMsg('user', q);
  addMsg('bot', '思考中…');
  const waiting = messages.value[messages.value.length - 1];
  let streamText = '';
  let streamMsg = null;
  let metaLine = '';
  let userMsgId = null;

  const ensureStream = () => {
    if (streamMsg) return streamMsg;
    const idx = messages.value.findIndex(x => x.key === waiting.key);
    if (idx >= 0) messages.value.splice(idx, 1);
    streamMsg = addMsg('bot', '');
    streamMsg.streaming = true;
    return streamMsg;
  };
  const paintStream = () => { if (streamMsg) { streamMsg.displayText = streamText; nextTick(scrollBottom); } };

  const onEvent = (event, data) => {
    if (event === 'meta') {
      const route = data.routed && data.module ? ' → ' + data.module : '';
      metaLine = `${props.provider || ''} · ${data.model || props.model}${route}`;
      userMsgId = data.userMsgId || null;
      if (userMsgId) userMsg.msgId = userMsgId;
      statusText.value = `回复中：${data.moduleName || data.module || '自动调度'}`;
    } else if (event === 'chunk') {
      streamText += (data.text || '');
      ensureStream();
      paintStream();
    } else if (event === 'done') {
      if (!streamMsg) { streamText = data.reply || '(空回复)'; ensureStream(); paintStream(); }
      streamMsg.streaming = false;
      const { clean, specs: textSpecs } = extractAmap(data.reply || streamText || '(空回复)');
      streamMsg.displayText = clean;
      streamMsg.rawText = clean;
      let specs = [];
      if (Array.isArray(data.amap) && data.amap.length) specs = data.amap.map(s => { try { return typeof s === 'string' ? JSON.parse(s) : s; } catch (e) { return null; } }).filter(Boolean);
      else specs = textSpecs;
      streamMsg.amapSpecs = specs;
      streamMsg.meta = `${metaLine} · thread=${threadId.value}`;
      streamMsg.msgId = data.assistantMsgId || null;
      streamMsg.pairUserText = q;
      statusText.value = '就绪';
      emit('thread-loaded');
    } else if (event === 'pending') {
      const idx = messages.value.findIndex(x => x.key === waiting.key);
      if (idx >= 0) messages.value.splice(idx, 1);
      addMsg('bot', data.reply || '以下操作会改动数据，需要你确认后才会执行。', `${props.provider || ''} · ${props.model} · ${data.moduleName || ''}`);
      approvalData.value = data;
      statusText.value = '等待人工确认';
    } else if (event === 'outbound_form' || event === 'delivery_form') {
      const idx = messages.value.findIndex(x => x.key === waiting.key);
      if (idx >= 0) messages.value.splice(idx, 1);
      const isOutbound = event === 'outbound_form';
      addMsg('bot', data.reply || (isOutbound ? '请填写出库单。' : '请填写配送单。'),
        `${props.provider || ''} · ${props.model} · ${data.moduleName || ''}`);
      if (isOutbound) outboundFormData.value = data.form;
      else deliveryFormData.value = data.form;
      statusText.value = isOutbound ? '请填写出库单' : '请填写配送单';
    } else if (event === 'error') {
      const idx = messages.value.findIndex(x => x.key === waiting.key);
      if (idx >= 0) messages.value.splice(idx, 1);
      if (streamMsg) { const sidx = messages.value.findIndex(x => x.key === streamMsg.key); if (sidx >= 0) messages.value.splice(sidx, 1); }
      addMsg('bot', data.message || '调用失败', '', true);
      statusText.value = '请求失败';
    }
  };

  try {
    aborter.value = new AbortController();
    await streamChat({
      message: q, threadId: threadId.value,
      provider: props.provider, model: props.model, module: props.moduleKey,
      signal: aborter.value.signal, onEvent
    });
    if (!streamMsg && !approvalData.value && statusText.value.startsWith('回复中')) {
      const idx = messages.value.findIndex(x => x.key === waiting.key);
      if (idx >= 0) messages.value.splice(idx, 1);
      addMsg('bot', '(连接已结束，未收到完整回复)', '', true);
    }
  } catch (e) {
    const idx = messages.value.findIndex(x => x.key === waiting.key);
    if (idx >= 0) messages.value.splice(idx, 1);
    if (e.name === 'AbortError') {
      // 用户点了「停止」：保留已流出的部分内容，不当作失败
      if (streamMsg) {
        streamMsg.streaming = false;
        streamMsg.displayText = streamText || '';
        streamMsg.rawText = streamText || '';
        streamMsg.meta = (metaLine ? metaLine + ' · ' : '') + '已手动停止';
        if (!streamText) {
          const sidx = messages.value.findIndex(x => x.key === streamMsg.key);
          if (sidx >= 0) messages.value.splice(sidx, 1);
          addMsg('bot', '（已停止生成）', '', false);
        }
      } else {
        addMsg('bot', '（已停止生成）', '', false);
      }
      statusText.value = '已停止';
    } else {
      if (streamMsg) { const sidx = messages.value.findIndex(x => x.key === streamMsg.key); if (sidx >= 0) messages.value.splice(sidx, 1); }
      addMsg('bot', '请求失败：' + e.message, '', true);
      statusText.value = '请求失败';
    }
  } finally { sending.value = false; aborter.value = null; }
}

async function openThread(tid) {
  const data = await threadMessages(tid, 200);
  threadId.value = tid;
  messages.value = [];
  let lastUserText = '';
  (data.messages || []).forEach(m => {
    const meta = m.role === 'user' ? '' : `${m.provider ? m.provider + ' · ' : ''}${m.model || ''}`.trim();
    const role = m.role === 'user' ? 'user' : 'bot';
    const { clean, specs } = role === 'bot' ? extractAmap(m.content) : { clean: m.content, specs: [] };
    const node = addMsg(role, clean, meta, false, m.id, specs);
    if (role === 'user') lastUserText = m.content;
    else node.pairUserText = lastUserText;
  });
  statusText.value = `已载入历史会话 ${tid}`;
}

function newChat() {
  threadId.value = 'web-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  messages.value = [];
  approvalData.value = null;
  outboundFormData.value = null;
  deliveryFormData.value = null;
  statusText.value = '就绪';
}

defineExpose({ send, openThread, newChat, threadId });
</script>

<style scoped>
.chat-panel { display: flex; flex-direction: column; height: 100%; overflow: hidden; }
.hero { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 40px 20px; text-align: center; flex: 1; }
.hero-logo { width: 72px; height: 72px; border-radius: 20px; background: linear-gradient(135deg, #6366f1, #8b5cf6); display: flex; align-items: center; justify-content: center; box-shadow: 0 12px 32px rgba(99,102,241,.35); margin-bottom: 24px; }
.hero-logo span { font-size: 36px; font-weight: 700; color: #fff; }
.hero-title { font-size: 28px; margin: 0 0 10px; font-weight: 700; color: #111827; }
.hero-sub { color: #6b7280; font-size: 14px; margin-bottom: 32px; }
.hero-sugg { display: flex; flex-direction: column; gap: 12px; width: min(480px, 92%); }
.sugg-btn { display: flex; align-items: center; gap: 10px; padding: 14px 18px; border: 1px solid #e5e7eb; border-radius: 12px; background: #fff; font-size: 14px; color: #374151; cursor: pointer; transition: all .15s; text-align: left; }
.sugg-btn:hover { border-color: #6366f1; background: #eef2ff; color: #4338ca; }

.messages { flex: 1; overflow-y: auto; padding: 24px 24px 16px; display: flex; flex-direction: column; gap: 20px; min-height: 0; }
.msg { display: flex; gap: 12px; max-width: 900px; width: 100%; }
.msg.user { align-self: flex-end; flex-direction: row-reverse; }
.avatar { width: 36px; height: 36px; border-radius: 10px; flex: 0 0 36px; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 600; }
.avatar.user { background: linear-gradient(135deg, #6366f1, #8b5cf6); color: #fff; }
.avatar.bot { background: #f3f4f6; color: #6366f1; border: 1px solid #e5e7eb; }
.msg-body { min-width: 0; flex: 1; max-width: calc(80% - 48px); }
.msg.user .msg-body { display: flex; flex-direction: column; align-items: flex-end; }
.bubble { display: inline-block; background: #fff; border: 1px solid #e5e7eb; border-radius: 16px; padding: 14px 18px; font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; box-shadow: 0 1px 3px rgba(0,0,0,.04); max-width: 100%; }
.msg.user .bubble { background: linear-gradient(135deg, #6366f1, #8b5cf6); color: #fff; border: none; }
.msg.user .bubble :deep(code) { background: rgba(255,255,255,.2); color: #fff; }
.bubble.err { border-color: #fecaca; background: #fef2f2; color: #dc2626; }
.bubble.thinking { color: #6b7280; display: inline-block; }
.bubble.thinking::before { content: ''; display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #6366f1; margin-right: 8px; animation: thinkingPulse 1.2s ease-in-out infinite; }
@keyframes thinkingPulse { 0%, 100% { opacity: .3; transform: scale(.8); } 50% { opacity: 1; transform: scale(1.2); } }
.bubble :deep(table) { border-collapse: collapse; margin: 10px 0; font-size: 13px; width: 100%; }
.bubble :deep(th), .bubble :deep(td) { border: 1px solid #e5e7eb; padding: 6px 12px; text-align: left; }
.bubble :deep(th) { background: #f9fafb; font-weight: 600; }
.bubble :deep(code) { background: #f3f4f6; padding: 2px 6px; border-radius: 4px; font-size: 13px; }
.bubble.streaming { border-color: #c7d2fe; }
.streaming-indicator { display: flex; gap: 4px; margin-top: 8px; }
.streaming-indicator .dot { width: 6px; height: 6px; border-radius: 50%; background: #6366f1; animation: bounce 1.4s infinite ease-in-out both; }
.streaming-indicator .dot:nth-child(1) { animation-delay: -0.32s; }
.streaming-indicator .dot:nth-child(2) { animation-delay: -0.16s; }
@keyframes bounce { 0%, 80%, 100% { transform: scale(.6); opacity: .5; } 40% { transform: scale(1); opacity: 1; } }
.thinking-dots { display: inline-flex; gap: 4px; margin-right: 8px; }
.thinking-dots span { width: 6px; height: 6px; border-radius: 50%; background: #6366f1; animation: bounce 1.4s infinite ease-in-out both; }
.thinking-dots span:nth-child(1) { animation-delay: -0.32s; }
.thinking-dots span:nth-child(2) { animation-delay: -0.16s; }
.meta { font-size: 12px; color: #9ca3af; margin-top: 6px; }
.map-open-row { margin-top: 8px; }
.map-open-btn { display: inline-flex; align-items: center; gap: 6px; border: 1px solid #e5e7eb; background: #fff; color: #4f6ef7; border-radius: 999px; padding: 5px 14px; font-size: 12.5px; cursor: pointer; transition: all .15s; }
.map-open-btn:hover:not(:disabled) { border-color: #4f6ef7; background: #eef2ff; }
.map-open-btn:disabled { opacity: .6; cursor: not-allowed; }
.msg-actions { display: flex; gap: 2px; margin-top: 6px; opacity: 0; transition: opacity .15s; }
.msg:hover .msg-actions { opacity: 1; }
.msg-actions.user { justify-content: flex-end; }
.msg-act { display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; border: none; border-radius: 6px; background: transparent; color: #9ca3af; cursor: pointer; transition: all .15s; }
.msg-act:hover { background: #f3f4f6; color: #6366f1; }
.msg-act.danger:hover { background: #fef2f2; color: #dc2626; }

.input-area { padding: 0 24px 20px; flex-shrink: 0; }
.chips { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; margin: 0 auto 14px; max-width: 900px; }
.chip { padding: 8px 16px; border: 1px solid #e5e7eb; border-radius: 999px; background: #fff; font-size: 13px; color: #6b7280; cursor: pointer; transition: all .15s; }
.chip:hover { border-color: #6366f1; color: #6366f1; background: #eef2ff; }
.input-wrapper { max-width: 900px; margin: 0 auto; background: #fff; border: 1px solid #e5e7eb; border-radius: 24px; box-shadow: 0 4px 20px rgba(0,0,0,.06); display: flex; align-items: flex-end; gap: 10px; padding: 12px 14px 12px 20px; transition: all .15s; }
.input-wrapper:focus-within { border-color: #6366f1; box-shadow: 0 4px 24px rgba(99,102,241,.15); }
.chat-input :deep(.el-textarea__inner) { border: none; box-shadow: none; padding: 8px 0; background: transparent; font-size: 14px; min-height: 28px; max-height: 200px; resize: none; }
.send-btn { width: 36px; height: 36px; flex: 0 0 36px; }
.send-btn:disabled { opacity: .5; }
.stop-icon { width: 12px; height: 12px; background: #fff; border-radius: 2px; display: inline-block; }
.foot-note { text-align: center; font-size: 12px; color: #9ca3af; margin-top: 10px; }
</style>
