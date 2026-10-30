<template>
  <div>
    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">模块与 Agent</div>
            <div class="card-sub">已注册的业务模块：统一调度会按职责描述把请求路由到对应模块 Agent</div>
          </div>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>

      <el-table :data="rows" size="small" stripe border v-loading="loading">
        <el-table-column prop="key" label="模块标识" width="120" />
        <el-table-column prop="displayName" label="模块名称" width="140" />
        <el-table-column prop="agentName" label="Agent 名" width="150" />
        <el-table-column prop="description" label="职责描述" min-width="280" show-overflow-tooltip />
        <el-table-column label="工具绑定" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="t in (row.toolBindings || [])" :key="t" size="small" effect="plain" class="tag-item">{{ t }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="需人工审批的写操作" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="(v, k) in (row.approvalTools || {})" :key="k" size="small" type="warning" class="tag-item" :title="v">{{ k }}</el-tag>
            <span v-if="!row.approvalTools || !Object.keys(row.approvalTools).length" class="text-muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="提示词" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewPrompt(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="promptVisible" :title="`系统提示词：${current.displayName || current.key || ''}`" width="760px">
      <pre class="prompt-box">{{ current.systemPrompt || '（无）' }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { listModules } from '@/admin/api';

const loading = ref(false);
const rows = ref([]);
const promptVisible = ref(false);
const current = ref({});

async function load() {
  loading.value = true;
  try {
    rows.value = await listModules();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

function viewPrompt(row) {
  current.value = row;
  promptVisible.value = true;
}

onMounted(load);
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.card-title { font-size: 15px; font-weight: 600; color: #111827; }
.card-sub { font-size: 12px; color: #9ca3af; margin-top: 3px; }
.tag-item { margin: 1px 4px 1px 0; }
.text-muted { color: #9ca3af; }
.prompt-box {
  white-space: pre-wrap;
  word-break: break-all;
  background: #f6f7f9;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.7;
  color: #374151;
  max-height: 60vh;
  overflow: auto;
  margin: 0;
}
</style>
