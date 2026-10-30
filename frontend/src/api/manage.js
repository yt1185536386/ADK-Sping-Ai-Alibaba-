// 通用管理 CRUD 接口（/api/manage/**）与高德 JS Key
import { getJson, authFetch } from './http';

export const manage = (url) => getJson(url);

/** 管理模块写操作：失败抛出后端 error 信息 */
export async function manageCreate(url, data) {
  const res = await authFetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || 'HTTP ' + res.status);
  return res.json();
}

export async function manageUpdate(url, data) {
  const res = await authFetch(url, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || 'HTTP ' + res.status);
  return res.json();
}

export async function manageDelete(url) {
  const res = await authFetch(url, { method: 'DELETE' });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || 'HTTP ' + res.status);
  return res.json();
}

export const amapJsKey = () => getJson('/api/manage/amapJsKey');
