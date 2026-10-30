import { amapJsKey } from '@/api/manage';
import { esc } from './text';

// ================= 高德交互式地图 =================
// 流程：先取后端下发的 JS key + 安全密钥 jscode，注入 window._AMapSecurityConfig，
// 再动态加载 JS API loader，成功后按 spec 渲染 POI 定位或路线规划。未配置 key 时提示。
let _amapReady = null; // Promise，单例加载

export function loadAmap() {
  if (_amapReady) return _amapReady;
  _amapReady = (async () => {
    const cfg = await amapJsKey();
    if (!cfg || !cfg.key) {
      throw new Error('未配置高德前端 JS key：请到高德控制台创建「Web端(JS API)」应用，' +
        '把 key 写入 external_tool(tool_key=amap_js) 的 api_key，安全密钥 jscode 写入 auth_key_name');
    }
    window._AMapSecurityConfig = { securityJsCode: cfg.jscode || '' };
    await new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://webapi.amap.com/loader.js';
      s.onload = resolve;
      s.onerror = () => reject(new Error('高德 JS API loader 加载失败（检查网络或 key）'));
      document.head.appendChild(s);
    });
    return await window.AMapLoader.load({ key: cfg.key, version: '2.0' });
  })();
  _amapReady.catch(() => { _amapReady = null; }); // 失败允许重试
  return _amapReady;
}

// 把 "lng,lat" 解析为 [lng,lat] 数字数组；非法返回 null
function parseLngLat(s) {
  const m = /^(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)$/.exec(String(s || '').trim());
  return m ? [parseFloat(m[1]), parseFloat(m[2])] : null;
}

// 在指定容器渲染一张地图：spec.type=poi(多点定位) / route(路线规划)
export async function initAmapSlot(slot, spec) {
  try {
    const AMap = await loadAmap();
    slot.innerHTML = '';
    const box = document.createElement('div');
    box.className = 'amap-box';
    slot.appendChild(box);
    const map = new AMap.Map(box, { zoom: 12, resizeEnable: true });

    if (spec.type === 'poi' && Array.isArray(spec.pois)) {
      const bounds = [];
      spec.pois.forEach(p => {
        const pos = parseLngLat(p.location);
        if (!pos) return;
        bounds.push(pos);
        const marker = new AMap.Marker({ position: pos, title: p.name, map });
        marker.setLabel({ content: `<div class="amap-label">${esc(p.name || '')}</div>`, offset: new AMap.Pixel(0, 4) });
        marker.on('click', () => new AMap.InfoWindow({
          content: `<div style="font-size:13px"><b>${esc(p.name || '')}</b><br>${esc(p.address || '')}</div>`
        }).open(map, pos));
      });
      if (bounds.length) map.setFitView(null, false, [40, 40, 40, 40]);
    } else if (spec.type === 'route') {
      const from = parseLngLat(spec.from);
      const to = parseLngLat(spec.to);
      if (!from || !to) { slot.innerHTML = '<div class="amap-loading">起终点坐标缺失，无法绘制路线</div>'; return; }
      const plugin = { transit: 'AMap.Transfer', walking: 'AMap.Walking', driving: 'AMap.Driving' }[spec.mode] || 'AMap.Transfer';
      await new Promise(res => AMap.plugin(plugin, res));
      const opts = { map, panel: false, hideMarkers: false };
      let planner;
      if (plugin === 'AMap.Transfer') planner = new AMap.Transfer(Object.assign(opts, { city: spec.city || '' }));
      else if (plugin === 'AMap.Walking') planner = new AMap.Walking(opts);
      else planner = new AMap.Driving(opts);
      planner.search(from, to, (status) => {
        if (status !== 'complete') slot.insertAdjacentHTML('beforeend', '<div class="amap-loading">路线绘制失败，请重试</div>');
        else map.setFitView(null, false, [60, 60, 60, 60]);
      });
    } else {
      slot.innerHTML = '<div class="amap-loading">未识别的地图类型</div>';
    }
  } catch (e) {
    slot.innerHTML = '<div class="amap-loading amap-err">' + esc(e.message) + '</div>';
  }
}
