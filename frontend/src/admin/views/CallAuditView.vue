<template>
  <el-card shadow="never" class="audit-card">
    <template #header>
      <div class="card-header">
        <div>
          <div class="card-title">调用审计</div>
          <div class="card-sub">llm_call_log 表：每次模型调用一行，含渠道、模型、用户、Token、耗时与失败原因</div>
        </div>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </template>

    <div class="filter-bar">
      <el-select v-model="filter.days" style="width:120px" @change="load">
        <el-option label="今日" :value="1" />
        <el-option label="近 7 天" :value="7" />
        <el-option label="近 30 天" :value="30" />
      </el-select>
      <el-input v-model="filter.provider" placeholder="渠道" clearable style="width:130px" @keyup.enter="load" @clear="load" />
      <el-input v-model="filter.model" placeholder="模型" clearable style="width:160px" @keyup.enter="load" @clear="load" />
      <el-input v-model="filter.username" placeholder="用户" clearable style="width:130px" @keyup.enter="load" @clear="load" />
      <el-select v-model="filter.success" placeholder="结果" clearable style="width:110px" @change="load">
        <el-option label="成功" :value="true" />
        <el-option label="失败" :value="false" />
      </el-select>
      <el-button type="primary" size="small" @click="load">查询</el-button>
      <el-button size="small" @click="resetFilter">重置</el-button>
      <span class="count">共 {{ total }} 条</span>
    </div>

    <!-- 图表与表格 1:1 平分剩余高度 -->
    <div class="content" v-loading="loading">
      <div ref="chartEl" class="chart"></div>
      <div class="table-wrap">
        <el-table :data="rows" size="small" stripe border height="100%">
          <el-table-column prop="created_at" label="时间" width="160" />
          <el-table-column prop="username" label="用户" width="110">
            <template #default="{ row }">{{ row.username || '未登录' }}</template>
          </el-table-column>
          <el-table-column prop="module_key" label="模块" width="110" />
          <el-table-column prop="provider" label="渠道" width="110" />
          <el-table-column prop="model" label="模型" width="160" show-overflow-tooltip />
          <el-table-column prop="prompt_tokens" label="输入" width="80" align="right" />
          <el-table-column prop="completion_tokens" label="输出" width="80" align="right" />
          <el-table-column prop="total_tokens" label="Token" width="90" align="right" />
          <el-table-column prop="latency_ms" label="耗时(ms)" width="100" align="right" />
          <el-table-column prop="first_token_ms" label="首token(ms)" width="110" align="right" />
          <el-table-column label="结果" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="Number(row.success) === 1 ? 'success' : 'danger'" size="small">
                {{ Number(row.success) === 1 ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="error_msg" label="失败原因" min-width="220" show-overflow-tooltip />
        </el-table>
      </div>
      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="size"
                       :total="total" :page-sizes="[20, 50, 100, 200]"
                       layout="total, sizes, prev, pager, next, jumper" background small
                       @current-change="loadList" @size-change="loadList" />
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { callSeries, listCalls } from '@/admin/api';
import { baseOption, useChart } from '@/admin/charts';

const loading = ref(false);
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const seriesRows = ref([]);
const filter = reactive({ days: 7, provider: '', model: '', username: '', success: '' });

const chartEl = ref(null);

/** 时间序列图：横轴为每次调用的时间，左轴 Token、右轴耗时 */
const chartOption = computed(() => {
  const times = seriesRows.value.map(r => r.created_at);
  const pick = (key) => seriesRows.value.map(r => r[key] == null ? 0 : Number(r[key]));
  return {
    ...baseOption(),
    tooltip: {
      trigger: 'axis', confine: true,
      formatter(params) {
        const i = params[0]?.dataIndex ?? 0;
        const r = seriesRows.value[i] || {};
        const lines = [
          `<b>${r.created_at || ''}</b>`,
          `${r.username || '未登录'} · ${r.provider || '-'} · ${r.model || '-'}`,
          `结果：${Number(r.success) === 1 ? '成功' : '失败'}`
        ];
        for (const p of params) lines.push(`${p.marker}${p.seriesName}：${p.value}`);
        return lines.join('<br/>');
      }
    },
    legend: { top: 0, icon: 'roundRect', itemWidth: 12, itemHeight: 8 },
    grid: { left: 12, right: 16, top: 30, bottom: 28, containLabel: true },
    xAxis: { type: 'category', data: times, axisLabel: { hideOverlap: true } },
    yAxis: [
      { type: 'value', name: 'Token' },
      { type: 'value', name: '毫秒' }
    ],
    dataZoom: [
      { type: 'inside' },
      { type: 'slider', height: 16, bottom: 4 }
    ],
    series: [
      { name: '输入Token', type: 'line', data: pick('prompt_tokens'), showSymbol: false, smooth: true },
      { name: '输出Token', type: 'line', data: pick('completion_tokens'), showSymbol: false, smooth: true },
      { name: '总Token', type: 'line', data: pick('total_tokens'), showSymbol: false, smooth: true },
      { name: '耗时(ms)', type: 'line', yAxisIndex: 1, data: pick('latency_ms'), showSymbol: false, smooth: true },
      { name: '首token(ms)', type: 'line', yAxisIndex: 1, data: pick('first_token_ms'), showSymbol: false, smooth: true }
    ]
  };
});

useChart(chartEl, chartOption);

async function loadList() {
  loading.value = true;
  try {
    const res = await listCalls({ ...filter, page: page.value, size: size.value });
    rows.value = res.list || [];
    total.value = res.total || 0;
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

async function loadSeries() {
  try {
    seriesRows.value = await callSeries({ ...filter });
  } catch (e) {
    ElMessage.error(e.message);
  }
}

/** 查询 / 刷新：回到第一页，列表与图表一起重载 */
function load() {
  page.value = 1;
  loadList();
  loadSeries();
}

function resetFilter() {
  Object.assign(filter, { days: 7, provider: '', model: '', username: '', success: '' });
  load();
}

onMounted(load);
</script>

<style scoped>
.audit-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 170px);
  box-sizing: border-box;
}
.card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.card-title { font-size: 15px; font-weight: 600; color: #111827; }
.card-sub { font-size: 12px; color: #9ca3af; margin-top: 3px; }
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; flex-shrink: 0; }
.count { margin-left: auto; font-size: 12px; color: #9ca3af; }
/* 图表固定占剩余空间的一半，表格 + 分页占另一半 */
.content { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.chart { height: 50%; flex-shrink: 0; width: 100%; }
.table-wrap { flex: 1; min-height: 0; margin-top: 8px; }
.pager { display: flex; justify-content: flex-end; padding-top: 8px; flex-shrink: 0; }
</style>
