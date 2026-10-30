// 带认证的 fetch 封装与通用请求助手。
// 任何接口返回 401 都会清掉凭据并通知路由层回到登录页（见 onUnauthorized）。
import { authHeaders, clearAuth } from './auth';

/** 401 时回调（App.vue 注册，用于跳回登录页） */
let unauthorizedHandler = null;
export function onUnauthorized(fn) { unauthorizedHandler = fn; }

export async function authFetch(url, options = {}) {
  const headers = { ...authHeaders(), ...(options.headers || {}) };
  const res = await fetch(url, { ...options, headers });
  if (res.status === 401) {
    clearAuth();
    if (unauthorizedHandler) unauthorizedHandler();
    throw new Error('认证已失效，请重新登录');
  }
  return res;
}

export async function getJson(url) {
  const res = await authFetch(url);
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

export async function del(url) {
  const res = await authFetch(url, { method: 'DELETE' });
  return res.ok;
}

/** 通用 POST，失败抛出后端 error 信息 */
export async function postJson(url, data) {
  const res = await authFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: data != null ? JSON.stringify(data) : undefined
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || 'HTTP ' + res.status);
  return res.json();
}
