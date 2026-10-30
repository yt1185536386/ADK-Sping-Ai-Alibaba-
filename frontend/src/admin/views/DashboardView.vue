<template>
  <div class="dashboard">
    <!-- 顶部指标卡 -->
    <div class="stat-row">
      <el-card v-for="card in cards" :key="card.label" shadow="never" class="stat-card" :body-style="{ padding: '14px 16px' }">
        <div class="stat-label">
          <el-icon :style="{ color: card.color }"><component :is="card.icon" /></el-icon>
          <span>{{ card.label }}</span>
        </div>
        <div class="stat-value">{{ card.value }}</div>
        <div class="stat-sub">{{ card.sub }}</div>
      </el-card>
    </div>

    <!-- 图表区 -->
    <el-row :gutter="12">
      <el-col :span="16">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-header">
              <span>调用量趋势</span>
              <div class="card-header-actions">
                <el-date-picker v-model="range" type="datetimerange" size="small" :shortcuts="shortcuts"
                                range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间"
                                style="width:330px" @change="loadTrend" />
              </div>
            </div>
          </template>
          <div ref="trendEl" class="chart" v-loading="trendLoading"></div>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="never" class="chart-card">
          <template #header><span>用户调用量 Top 10</span></template>
          <div ref="userEl" class="chart" v-loading="loading"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <el-col :span="15">
        <el-card shadow="never" class="chart-card">
          <template #header><span>熔断 / 降级事件</span></template>
          <el-table :data="failoverEvents" size="small" max-height="300" empty-text="暂无熔断 / 降级事件">
            <el-table-column prop="created_at" label="时间" width="160" />
            <el-table-column label="事件" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.event_type === 'breaker_trip'" type="danger" size="small">熔断</el-tag>
                <el-tag v-else type="warning" size="small">降级</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="渠道 · 模型" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <template v-if="row.event_type === 'breaker_trip'">
                  {{ row.from_provider }} 熔断
                </template>
                <template v-else>
                  {{ row.from_provider }}::{{ row.from_model || '-' }}
                  <template v-if="row.to_provider">
                    → <b>{{ row.to_provider }}::{{ row.to_model || '-' }}</b>
                  </template>
                </template>
              </template>
            </el-table-column>
            <el-table-column prop="attempts" label="尝试" width="60" align="center" />
            <el-table-column prop="skipped" label="熔断跳过" width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ row.skipped || '-' }}</template>
            </el-table-column>
            <el-table-column label="结果" width="70" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.success === null || row.success === undefined" type="info" size="small">-</el-tag>
                <el-tag v-else :type="Number(row.success) === 1 ? 'success' : 'danger'" size="small">
                  {{ Number(row.success) === 1 ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="error_msg" label="触发原因" min-width="180" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="9">
        <el-card shadow="never" class="chart-card">
          <template #header><span>失败原因 Top</span></template>
          <el-table :data="topErrors" size="small" max-height="300" empty-text="暂无失败记录">
            <el-table-column prop="provider" label="渠道" width="120" />
            <el-table-column prop="error_msg" label="失败原因" show-overflow-tooltip />
            <el-table-column prop="failures" label="次数" width="70" align="right" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ChatLineRound, Coin, DataAnalysis, Timer, Warning } from '@element-plus/icons-vue';
import { dashboard as fetchDashboard, statsTrendRange } from '@/admin/api';
import { baseOption, useChart } from '@/admin/charts';

const loading = ref(false);
const trendLoading = ref(false);
const overview = ref({});
const trend = ref([]);
const trendByModel = ref([]);
const granularity = ref('hour');
const byUser = ref([]);
const topErrors = ref([]);
const failoverEvents = ref([]);

const trendEl = ref(null);
const userEl = ref(null);

/* ---------------- 时间范围（默认今天，≤24h 按小时、>24h 按天） ---------------- */

function startOfToday() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d;
}

const range = ref([startOfToday(), new Date()]);

const shortcuts = [
  { text: '今天', value: () => [startOfToday(), new Date()] },
  {
    text: '昨天', value: () => {
      const s = startOfToday();
      s.setDate(s.getDate() - 1);
      const e = new Date(s);
      e.setHours(23, 59, 59, 999);
      return [s, e];
    }
  },
  {
    text: '近 7 天', value: () => {
      const s = startOfToday();
      s.setDate(s.getDate() - 6);
      return [s, new Date()];
    }
  },
  {
    text: '近 30 天', value: () => {
      const s = startOfToday();
      s.setDate(s.getDate() - 29);
      return [s, new Date()];
    }
  }
];

function fmtDT(d) {
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/* ---------------- 趋势图：按模型堆叠 ---------------- */

/** 范围内调用量 Top 8 的模型单独分段，其余归为「其他」；每个时间桶记录 calls + tokens */
const modelBreakdown = computed(() => {
  const rows = trendByModel.value || [];
  const totals = new Map();
  for (const r of rows) {
    totals.set(r.model, (totals.get(r.model) || 0) + Number(r.calls));
  }
  const top = [...totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8).map(e => e[0]);
  const topSet = new Set(top);
  const hasOther = totals.size > top.length;
  const models = hasOther ? [...top, '其他'] : top;

  // 透视：model -> { bucket: { calls, tokens } }
  const pivot = new Map(models.map(m => [m, {}]));
  for (const r of rows) {
    const m = topSet.has(r.model) ? r.model : '其他';
    const bucket = pivot.get(m);
    if (!bucket) continue;
    const cell = bucket[r.bucket] || (bucket[r.bucket] = { calls: 0, tokens: 0 });
    cell.calls += Number(r.calls);
    cell.tokens += Number(r.tokens || 0);
  }
  return { models, pivot };
});

const trendOption = computed(() => {
  const buckets = trend.value.map(t => t.bucket);
  const labels = buckets.map(b => granularity.value === 'hour' ? String(b).slice(11) : String(b).slice(5));
  const callsByBucket = trend.value.map(t => Number(t.calls));
  const tokensByBucket = trend.value.map(t => Number(t.tokens));
  const { models, pivot } = modelBreakdown.value;

  return {
    ...baseOption(),
    tooltip: {
      trigger: 'axis', confine: true,
      // 先总量，再各模型明细（次数 + Token 都给）
      formatter(params) {
        if (!params.length) return '';
        const i = params[0].dataIndex;
        const lines = [
          `<b>${buckets[i] || params[0].axisValue}</b>`,
          `总调用量：<b>${callsByBucket[i] ?? 0}</b>　总 Token：<b>${tokensByBucket[i] ?? 0}</b>`
        ];
        const rows = models
          .map((m, idx) => ({ name: m, color: params[idx]?.color, ...(pivot.get(m) || {})[buckets[i]] }))
          .filter(r => r.calls > 0)
          .sort((a, b) => b.calls - a.calls);
        for (const r of rows) {
          const marker = `<span style="display:inline-block;margin-right:4px;border-radius:2px;width:8px;height:8px;background:${r.color}"></span>`;
          lines.push(`${marker}${r.name}：${r.calls} 次 / ${r.tokens} Token`);
        }
        return lines.join('<br/>');
      }
    },
    legend: { top: 4, type: 'scroll' },
    grid: { left: 12, right: 16, top: 44, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: labels, axisLabel: { color: '#6b7280' } },
    yAxis: [
      { type: 'value', name: '调用量', axisLabel: { color: '#6b7280' }, splitLine: { lineStyle: { color: '#f0f2f5' } } },
      { type: 'value', name: 'Token', axisLabel: { color: '#6b7280' }, splitLine: { show: false } }
    ],
    series: [
      ...models.map(m => ({
        name: m, type: 'bar', stack: 'metric', barMaxWidth: 18,
        emphasis: { focus: 'series' },
        data: buckets.map(b => ((pivot.get(m) || {})[b] || {}).calls || 0)
      })),
      // 总 Token 平滑折线（右轴）
      {
        name: '总Token', type: 'line', yAxisIndex: 1,
        smooth: true, symbolSize: 5,
        data: tokensByBucket
      }
    ]
  };
});

const userOption = computed(() => ({
  ...baseOption(),
  tooltip: { trigger: 'axis', confine: true },
  legend: { show: false },
  grid: { left: 8, right: 30, top: 12, bottom: 8, containLabel: true },
  xAxis: { type: 'value', axisLabel: { color: '#6b7280' }, splitLine: { lineStyle: { color: '#f0f2f5' } } },
  yAxis: {
    type: 'category',
    data: [...byUser.value].reverse().map(u => u.username),
    axisLabel: { color: '#6b7280', width: 90, overflow: 'truncate' }
  },
  series: [{
    type: 'bar', barMaxWidth: 16,
    itemStyle: { borderRadius: [0, 4, 4, 0] },
    data: [...byUser.value].reverse().map(u => Number(u.calls))
  }]
}));

useChart(trendEl, trendOption);
useChart(userEl, userOption);

/* ---------------- 指标卡 ---------------- */

const cards = computed(() => {
  const o = overview.value || {};
  return [
    { label: '模型调用量', value: format(o.calls), sub: `近 7 天 · 失败 ${format(o.failures)}`, icon: ChatLineRound, color: '#4f6ef7' },
    { label: 'Token 消耗', value: format(o.tokens), sub: `输入 ${format(o.promptTokens)} / 输出 ${format(o.completionTokens)}`, icon: Coin, color: '#10b981' },
    { label: '调用成功率', value: `${o.successRate ?? 100}%`, sub: `活跃用户 ${o.activeUsers ?? 0} 人`, icon: DataAnalysis, color: '#f59e0b' },
    { label: '平均响应耗时', value: `${format(o.avgLatency)} ms`, sub: `首 token 峰值 ${format(o.maxFirstToken)} ms`, icon: Timer, color: '#8b5cf6' },
    { label: '在用模型数', value: o.modelCount ?? 0, sub: '近 7 天出现过的模型', icon: DataAnalysis, color: '#06b6d4' },
    { label: '异常提示', value: failedModel.value ? '有' : '无', sub: failedModel.value || '无失败调用记录', icon: Warning, color: failedModel.value ? '#ef4444' : '#10b981' }
  ];
});

const failedModel = computed(() => {
  const row = (topErrors.value || [])[0];
  return row ? `${row.provider}：${String(row.error_msg || '').slice(0, 40)}` : null;
});

function format(n) {
  if (n === null || n === undefined) return '0';
  const v = Number(n);
  if (v >= 1000000) return (v / 1000000).toFixed(2) + 'M';
  if (v >= 1000) return (v / 1000).toFixed(1) + 'K';
  return String(v);
}

async function loadTrend() {
  trendLoading.value = true;
  try {
    const [s, e] = range.value || [];
    const data = await statsTrendRange(s ? fmtDT(s) : '', e ? fmtDT(e) : '');
    granularity.value = data.granularity || 'day';
    trend.value = data.trend || [];
    trendByModel.value = data.trendByModel || [];
  } catch (err) {
    console.error(err);
  } finally {
    trendLoading.value = false;
  }
}

async function load() {
  loading.value = true;
  try {
    const data = await fetchDashboard(7);
    overview.value = data.overview || {};
    byUser.value = data.byUser || [];
    topErrors.value = data.topErrors || [];
    failoverEvents.value = data.failoverEvents || [];
  } catch (e) {
    console.error(e);
  } finally {
    loading.value = false;
  }
  loadTrend();
}

onMounted(load);
</script>

<style scoped>
.stat-row {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}
.stat-label { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #6b7280; }
.stat-value { font-size: 22px; font-weight: 600; color: #111827; margin-top: 6px; line-height: 1.2; }
.stat-sub { font-size: 11px; color: #9ca3af; margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chart-card { margin-bottom: 12px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.card-header-actions { display: flex; gap: 8px; align-items: center; }
.chart { height: 340px; width: 100%; }
@media (max-width: 1400px) {
  .stat-row { grid-template-columns: repeat(3, 1fr); }
}
</style>
