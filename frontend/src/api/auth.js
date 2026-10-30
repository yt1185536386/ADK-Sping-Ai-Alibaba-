// 凭据存储与登录/会话校验。
// HTTP Basic 认证；凭据由登录页收集后存 localStorage。
// 生产环境应改为 JWT/OAuth2。

const AUTH_KEY = 'hr_agent_auth';

export function getAuth() {
  const saved = localStorage.getItem(AUTH_KEY);
  if (saved) { try { return JSON.parse(saved); } catch (e) { /* 落库脏数据走默认 */ } }
  return null;
}

export function setAuth(username, password) {
  localStorage.setItem(AUTH_KEY, JSON.stringify({ username, password }));
}

export function clearAuth() {
  localStorage.removeItem(AUTH_KEY);
}

export function authHeaders() {
  const auth = getAuth();
  if (!auth) return {};
  return { Authorization: 'Basic ' + btoa(auth.username + ':' + auth.password) };
}

/** 登录：用入参凭据（不读本地存储）调 /api/auth/check 验证，成功返回 {username, roles} */
export async function login(username, password) {
  const res = await fetch('/api/auth/check', {
    headers: { Authorization: 'Basic ' + btoa(username + ':' + password) }
  });
  if (res.status === 401) throw new Error('用户名或密码错误');
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

/** 用本地存储的凭据验证会话是否仍有效（页面刷新后恢复登录态），无效返回 null */
export async function checkAuth() {
  if (!getAuth()) return null;
  const res = await fetch('/api/auth/check', { headers: authHeaders() });
  if (res.status === 401) { clearAuth(); return null; }
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}
