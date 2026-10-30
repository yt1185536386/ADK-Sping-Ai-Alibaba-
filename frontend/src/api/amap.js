// 高德地图接口：重新调用高德工具生成最新地图数据（历史会话"打开地图"用）
import { authFetch } from './http';

export const amapRefresh = (params) => authFetch('/api/amap/refresh', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(params)
}).then(r => r.json());
