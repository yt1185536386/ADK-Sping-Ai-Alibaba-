// 管理端 API 封装：/api/admin/**
// 全部接口仅管理员可访问（后端 SecurityConfig 限制为 ROLE_ADMIN）
import { getJson, authFetch } from '@/api/http';

const json = (body) => ({ headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

async function request(url, options) {
  const res = await authFetch(url, options);
  if (!res.ok) {
    let msg = 'HTTP ' + res.status;
    try {
      const data = await res.json();
      msg = data.error || data.message || msg;
    } catch (e) { /* 非 JSON 响应 */ }
    throw new Error(msg);
  }
  return res.status === 204 ? null : res.json();
}

// ==================== 概览 / 统计 ====================

export const dashboard = (days = 7) => getJson(`/api/admin/stats/dashboard?days=${days}`);
export const statsOverview = (days = 7) => getJson(`/api/admin/stats/overview?days=${days}`);
export const statsTrend = (days = 7) => getJson(`/api/admin/stats/trend?days=${days}`);
/** 自定义时间范围趋势：≤24h 按小时、否则按天，返回 {granularity, trend, trendByModel} */
export const statsTrendRange = (start, end) => {
  const q = new URLSearchParams();
  if (start) q.set('start', start);
  if (end) q.set('end', end);
  return getJson(`/api/admin/stats/trend/range?${q.toString()}`);
};
export const statsByProvider = (days = 7) => getJson(`/api/admin/stats/provider?days=${days}`);
export const statsByModel = (days = 7) => getJson(`/api/admin/stats/model?days=${days}`);
export const statsByUser = (days = 7) => getJson(`/api/admin/stats/user?days=${days}`);

/** 调用明细（审计列表，服务端分页）：返回 {list, total, page, size} */
export const listCalls = (params = {}) => {
  const q = new URLSearchParams();
  if (params.days) q.set('days', params.days);
  if (params.provider) q.set('provider', params.provider);
  if (params.model) q.set('model', params.model);
  if (params.username) q.set('username', params.username);
  if (params.success !== undefined && params.success !== null && params.success !== '') {
    q.set('success', params.success ? 'true' : 'false');
  }
  q.set('page', params.page || 1);
  q.set('size', params.size || 20);
  return getJson(`/api/admin/stats/calls?${q.toString()}`);
};

/** 调用明细时间序列（图表用，窗口内全部记录，上限 5000） */
export const callSeries = (params = {}) => {
  const q = new URLSearchParams();
  if (params.days) q.set('days', params.days);
  if (params.provider) q.set('provider', params.provider);
  if (params.model) q.set('model', params.model);
  if (params.username) q.set('username', params.username);
  if (params.success !== undefined && params.success !== null && params.success !== '') {
    q.set('success', params.success ? 'true' : 'false');
  }
  return getJson(`/api/admin/stats/calls/series?${q.toString()}`);
};

// ==================== 模型来源管理 ====================

export const listChannels = () => getJson('/api/admin/channels');
export const saveChannel = (data) => request('/api/admin/channels', { method: 'POST', ...json(data) });
export const updateChannel = (provider, data) => request(`/api/admin/channels/${encodeURIComponent(provider)}`, { method: 'PUT', ...json(data) });
export const deleteChannel = (provider) => request(`/api/admin/channels/${encodeURIComponent(provider)}`, { method: 'DELETE' });
export const testChannel = (provider, model) => request(`/api/admin/channels/${encodeURIComponent(provider)}/test?model=${encodeURIComponent(model || '')}`, { method: 'POST' });
export const fetchChannelModels = (data) => request('/api/admin/channels/models', { method: 'POST', ...json(data) });

// ==================== 熔断降级 ====================

export const listBreakers = () => getJson('/api/admin/circuit-breakers');
export const updateBreaker = (provider, data) => request(`/api/admin/circuit-breakers/${encodeURIComponent(provider)}`, { method: 'PUT', ...json(data) });
export const tripBreaker = (provider) => request(`/api/admin/circuit-breakers/${encodeURIComponent(provider)}/trip`, { method: 'POST' });
export const resetBreaker = (provider) => request(`/api/admin/circuit-breakers/${encodeURIComponent(provider)}/reset`, { method: 'POST' });

export const listFallbackRules = () => getJson('/api/admin/fallback-rules');
export const saveFallbackRule = (data) => request('/api/admin/fallback-rules', { method: 'POST', ...json(data) });
export const deleteFallbackRule = (id) => request(`/api/admin/fallback-rules/${id}`, { method: 'DELETE' });

// ==================== 用户与角色 ====================

export const listUsers = () => getJson('/api/admin/users');
export const listRoles = () => getJson('/api/admin/users/roles');
export const createUser = (data) => request('/api/admin/users', { method: 'POST', ...json(data) });
export const updateUser = (id, data) => request(`/api/admin/users/${id}`, { method: 'PUT', ...json(data) });
export const deleteUser = (id) => request(`/api/admin/users/${id}`, { method: 'DELETE' });
export const resetUserPassword = (id, password) => request(`/api/admin/users/${id}/password`, { method: 'POST', ...json({ password }) });

// ==================== 系统配置与审计 ====================

export const listTools = () => getJson('/api/admin/tools');
export const saveTool = (data) => request('/api/admin/tools', { method: 'PUT', ...json(data) });
export const deleteTool = (toolKey) => request(`/api/admin/tools/${encodeURIComponent(toolKey)}`, { method: 'DELETE' });
export const listModules = () => getJson('/api/admin/modules');
export const listApprovals = (status) => getJson(`/api/admin/approvals${status ? `?status=${encodeURIComponent(status)}` : ''}`);
export const listThreads = () => getJson('/api/admin/threads');
