<template>
  <div ref="slotEl" class="amap-slot" :class="{ fullscreen: isFs }">
    <div class="amap-head">
      <span class="amap-title">{{ title }}</span>
      <button class="amap-fs-btn" :title="isFs ? '退出全屏' : '全屏显示'" @click="toggleFs">
        <svg v-if="!isFs" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
        <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3"/></svg>
        {{ isFs ? '退出全屏' : '全屏' }}
      </button>
    </div>
    <div v-if="loading" class="amap-loading">地图加载中…</div>
    <div v-else-if="error" class="amap-loading amap-err">{{ error }}</div>
    <div ref="box" class="amap-box"></div>

    <!-- 路线：方案切换 + 步骤（地图在上，描述在下） -->
    <div v-if="spec.type === 'route' && plans.length" class="route-panel">
      <div v-if="plans.length > 1" class="plan-tabs">
        <button v-for="(p, i) in plans" :key="i" class="plan-tab" :class="{ active: i === planIdx }" @click="planIdx = i">
          {{ p.title || ('方案' + (i + 1)) }}
          <span v-if="p.duration" class="plan-dur">{{ p.duration }}</span>
        </button>
      </div>
      <div class="plan-steps">
        <div v-if="curPlan.summary || curPlan.distance || curPlan.walkDistance || curPlan.tolls" class="plan-summary">
          <span v-if="curPlan.distance">全程 {{ curPlan.distance }}</span>
          <span v-if="curPlan.walkDistance">步行 {{ curPlan.walkDistance }}</span>
          <span v-if="curPlan.tolls">{{ curPlan.tolls }}</span>
        </div>
        <ol class="step-list">
          <li v-for="(s, i) in (curPlan.steps || [])" :key="i">{{ s }}</li>
        </ol>
      </div>
    </div>
    <!-- POI：地点简述 -->
    <div v-else-if="spec.type === 'poi' && Array.isArray(spec.pois) && spec.pois.length" class="route-panel">
      <div v-for="(p, i) in spec.pois" :key="i" class="poi-row">
        <span class="poi-name">{{ p.name }}</span>
        <span v-if="p.address" class="poi-addr">{{ p.address }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { loadAmap } from '@/utils/amap';
import { esc } from '@/utils/text';

const props = defineProps({ spec: { type: Object, required: true } });
const slotEl = ref(null);
const box = ref(null);
const loading = ref(true);
const error = ref('');
const isFs = ref(false);
const plans = computed(() => (Array.isArray(props.spec.plans) ? props.spec.plans : []));
const planIdx = ref(0);
const curPlan = computed(() => plans.value[planIdx.value] || {});
const title = computed(() => {
  const s = props.spec;
  if (s.type === 'route') return `${s.origin || ''} → ${s.destination || ''}`;
  if (s.type === 'poi') return '地点标注';
  return '';
});

function parseLngLat(s) {
  const m = /^(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)$/.exec(String(s || '').trim());
  return m ? [parseFloat(m[1]), parseFloat(m[2])] : null;
}

function toggleFs() {
  if (!document.fullscreenElement) slotEl.value?.requestFullscreen?.();
  else document.exitFullscreen?.();
}
function onFsChange() {
  isFs.value = !!document.fullscreenElement;
  setTimeout(() => { try { mapIns?.resize?.(); mapIns?.setFitView?.(null, false, [60, 60, 60, 60]); } catch (e) { /* ignore */ } }, 250);
}
let mapIns = null;
let AMapIns = null;
let plannerRef = null;
let transitPlans = [];   // 公交多方案原始数据（用于切换重绘）
let drawnLine = null;
let drawnMarkers = [];

/** 切换公交方案：清掉 planner 默认渲染，自绘选中方案的完整折线（步行段 + 公交段） */
function drawTransitPlan(plan) {
  if (!mapIns || !AMapIns) return;
  try { plannerRef?.clear?.(); } catch (e) { /* ignore */ }
  if (drawnLine) { drawnLine.setMap(null); drawnLine = null; }
  drawnMarkers.forEach(mk => mk.setMap(null));
  drawnMarkers = [];
  const pts = [];
  (plan && Array.isArray(plan.segments) ? plan.segments : []).forEach(seg => {
    const walkPath = seg.walking && seg.walking.path;
    if (Array.isArray(walkPath) && walkPath.length) pts.push(...walkPath);
    const busPath = seg.bus && seg.bus.buslines && seg.bus.buslines[0] && seg.bus.buslines[0].path;
    if (Array.isArray(busPath) && busPath.length) pts.push(...busPath);
  });
  if (!pts.length) return;
  drawnLine = new AMapIns.Polyline({
    path: pts, map: mapIns, showDir: true,
    strokeColor: '#4f6ef7', strokeWeight: 6, strokeOpacity: .85, lineJoin: 'round'
  });
  drawnMarkers.push(new AMapIns.Marker({
    position: pts[0], map: mapIns,
    label: { content: `<div class="amap-label">${esc(props.spec.origin || '起点')}</div>`, offset: new AMapIns.Pixel(0, 4) }
  }));
  drawnMarkers.push(new AMapIns.Marker({
    position: pts[pts.length - 1], map: mapIns,
    label: { content: `<div class="amap-label">${esc(props.spec.destination || '终点')}</div>`, offset: new AMapIns.Pixel(0, 4) }
  }));
  mapIns.setFitView([drawnLine, ...drawnMarkers], false, [60, 60, 60, 60]);
}

watch(planIdx, (idx) => {
  if (transitPlans.length && transitPlans[idx]) drawTransitPlan(transitPlans[idx]);
});

onMounted(async () => {
  document.addEventListener('fullscreenchange', onFsChange);
  try {
    const AMap = await loadAmap();
    loading.value = false;
    await new Promise(r => setTimeout(r, 0));
    const map = new AMap.Map(box.value, { zoom: 12, resizeEnable: true });
    mapIns = map;
    AMapIns = AMap;
    const spec = props.spec;

    if (spec.type === 'poi' && Array.isArray(spec.pois)) {
      spec.pois.forEach(p => {
        const pos = parseLngLat(p.location);
        if (!pos) return;
        const marker = new AMap.Marker({ position: pos, title: p.name, map });
        marker.setLabel({ content: `<div class="amap-label">${esc(p.name || '')}</div>`, offset: new AMap.Pixel(0, 4) });
        marker.on('click', () => new AMap.InfoWindow({
          content: `<div style="font-size:13px"><b>${esc(p.name || '')}</b><br>${esc(p.address || '')}</div>`
        }).open(map, pos));
      });
      if (spec.pois.some(p => parseLngLat(p.location))) map.setFitView(null, false, [40, 40, 40, 40]);
    } else if (spec.type === 'route') {
      const from = parseLngLat(spec.from);
      const to = parseLngLat(spec.to);
      if (!from || !to) { error.value = '起终点坐标缺失，无法绘制路线'; return; }
      const plugin = { transit: 'AMap.Transfer', walking: 'AMap.Walking', driving: 'AMap.Driving' }[spec.mode] || 'AMap.Transfer';
      await new Promise(res => AMap.plugin(plugin, res));
      const opts = { map, panel: false, hideMarkers: false };
      let planner;
      if (plugin === 'AMap.Transfer') planner = new AMap.Transfer(Object.assign(opts, { city: spec.city || '' }));
      else if (plugin === 'AMap.Walking') planner = new AMap.Walking(opts);
      else planner = new AMap.Driving(opts);
      planner.search(from, to, (status, result) => {
        if (status !== 'complete') { error.value = '路线绘制失败，请重试'; return; }
        map.setFitView(null, false, [60, 60, 60, 60]);
        // 公交：保存多方案原始数据，切换方案时自绘对应路线
        if (plugin === 'AMap.Transfer' && result && Array.isArray(result.plans)) {
          transitPlans = result.plans;
          plannerRef = planner;
        }
      });
    } else {
      error.value = '未识别的地图类型';
    }
  } catch (e) {
    loading.value = false;
    error.value = e.message || String(e);
  }
});

onBeforeUnmount(() => document.removeEventListener('fullscreenchange', onFsChange));
</script>

<style scoped>
.amap-slot { margin: 10px 0 4px; border: 1px solid #e8eaf2; border-radius: 12px; overflow: hidden; background: #f6f7fb; display: flex; flex-direction: column; }
.amap-slot.fullscreen { border-radius: 0; height: 100vh; }
.amap-head { display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; background: #fff; border-bottom: 1px solid #eef0f5; }
.amap-title { font-size: 13px; font-weight: 600; color: #1f2329; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.amap-fs-btn { display: flex; align-items: center; gap: 4px; border: 1px solid #e5e7eb; background: #fff; color: #6b7280; border-radius: 6px; padding: 3px 8px; font-size: 12px; cursor: pointer; transition: all .15s; flex-shrink: 0; }
.amap-fs-btn:hover { color: #4f6ef7; border-color: #c7d2fe; background: #eef2ff; }
.amap-box { width: 100%; height: 300px; flex-shrink: 0; }
.fullscreen .amap-box { height: auto; flex: 1; min-height: 0; }
.amap-loading { padding: 18px 14px; font-size: 12.5px; color: #8a9099; text-align: center; }
.amap-err { color: #d92c2c; line-height: 1.7; white-space: pre-wrap; text-align: left; }
:deep(.amap-label) { background: #fff; border: 1px solid #e8eaf2; border-radius: 6px; padding: 2px 8px; font-size: 12px; color: #1f2329; box-shadow: 0 1px 4px rgba(0,0,0,.08); white-space: nowrap; }

.route-panel { background: #fff; border-top: 1px solid #eef0f5; }
.fullscreen .route-panel { max-height: 40vh; overflow-y: auto; }
.plan-tabs { display: flex; gap: 6px; padding: 10px 12px 0; flex-wrap: wrap; }
.plan-tab { display: inline-flex; align-items: center; gap: 6px; border: 1px solid #e5e7eb; background: #fff; color: #374151; border-radius: 999px; padding: 5px 14px; font-size: 12.5px; cursor: pointer; transition: all .15s; }
.plan-tab:hover { border-color: #c7d2fe; color: #4f6ef7; }
.plan-tab.active { background: #4f6ef7; border-color: #4f6ef7; color: #fff; }
.plan-tab .plan-dur { font-size: 11.5px; opacity: .75; }
.plan-steps { padding: 10px 14px 14px; }
.plan-summary { display: flex; gap: 14px; font-size: 12px; color: #6b7280; margin-bottom: 8px; }
.step-list { margin: 0; padding-left: 20px; font-size: 13px; color: #1f2329; line-height: 1.9; }
.poi-row { display: flex; align-items: baseline; gap: 10px; padding: 8px 14px; font-size: 13px; }
.poi-row + .poi-row { border-top: 1px dashed #f0f1f5; }
.poi-name { font-weight: 600; color: #1f2329; flex-shrink: 0; }
.poi-addr { color: #8a9099; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
