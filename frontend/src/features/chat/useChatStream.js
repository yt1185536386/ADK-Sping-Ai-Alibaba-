// SSE 流式对话客户端：把 /api/chat/stream 的协议解析（event:/data: 帧切分、JSON 解码）
// 从界面组件中独立出来，ChatPanel 只关心事件语义。
import { authFetch } from '@/api/http';

/**
 * 发起一次流式对话。
 *
 * @param {object} opts
 * @param {string} opts.message  用户消息
 * @param {string} opts.threadId 会话标识
 * @param {string} [opts.provider] 渠道（空则由后端默认）
 * @param {string} [opts.model]    模型
 * @param {string} [opts.module]   业务模块 key（空走自动调度）
 * @param {AbortSignal} [opts.signal] 中止信号（点「停止」时 abort）
 * @param {(event: string, data: object) => void} opts.onEvent
 *        事件回调：meta / chunk / done / pending / outbound_form / delivery_form / error
 */
export async function streamChat({ message, threadId, provider, model, module, signal, onEvent }) {
  const res = await authFetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    signal,
    body: JSON.stringify({ message, threadId, provider: provider || null, model, module: module || null })
  });
  if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);

  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buf = '', curEvent = '', curData = '';

  const dispatch = () => {
    if (!curEvent) { curData = ''; return; }
    let data = {};
    try { data = JSON.parse(curData || '{}'); } catch (e) { /* 忽略半包 JSON */ }
    onEvent(curEvent, data);
    curEvent = ''; curData = '';
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).replace(/\r$/, '');
      buf = buf.slice(idx + 1);
      if (line === '') { dispatch(); continue; }          // 空行 = 一帧结束
      if (line.startsWith('event:')) { curEvent = line.slice(6).trim(); curData = ''; }
      else if (line.startsWith('data:')) { curData = (curData ? curData + '\n' : '') + line.slice(5).trim(); }
    }
  }
  dispatch(); // 冲刷最后一帧（连接结束时没有空行的情况）
}
