// 聊天域接口：渠道/模块/会话历史/消息/HITL 审批
import { getJson, del, authFetch } from './http';

export const channels = () => getJson('/api/channels');
export const modules = () => getJson('/api/modules');
export const threads = (limit = 30) => getJson('/api/threads?limit=' + limit);
export const threadMessages = (tid, limit = 200) => getJson('/api/threads/' + encodeURIComponent(tid) + '?limit=' + limit);
export const deleteThread = (tid) => del('/api/threads/' + encodeURIComponent(tid));
export const deleteMessage = (id) => del('/api/messages/' + encodeURIComponent(id));
export const submitApprovals = (batchId, decisions) => authFetch('/api/approvals', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ batchId, decisions })
}).then(r => r.json());
