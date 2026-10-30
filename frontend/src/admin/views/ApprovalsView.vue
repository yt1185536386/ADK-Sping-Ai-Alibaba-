<template>
  <div>
    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">人工审批记录</div>
            <div class="card-sub">写操作在对话中经 HITL 确认后执行，这里保留完整审批台账（审计用）</div>
          </div>
          <el-button size="small" @click="loadApprovals">刷新</el-button>
        </div>
      </template>

      <div class="filter-bar">
        <el-select v-model="status" placeholder="全部状态" clearable style="width:140px" @change="loadApprovals">
          <el-option label="待审批" value="PENDING" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已修改参数" value="EDITED" />
        </el-select>
      </div>

      <el-table :data="approvals" size="small" stripe border v-loading="loading" height="calc(100vh - 330px)">
        <el-table-column prop="id" label="审批单号" width="150" />
        <el-table-column prop="thread_id" label="会话" width="130" show-overflow-tooltip />
        <el-table-column prop="module_key" label="模块" width="100" />
        <el-table-column prop="tool_name" label="工具" width="150" />
        <el-table-column prop="description" label="审批说明" min-width="240" show-overflow-tooltip />
        <el-table-column prop="provider" label="渠道" width="110" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="创建时间" width="170" :formatter="timeCol" />
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">会话记录</div>
            <div class="card-sub">用户会话与消息量（审计用）</div>
          </div>
          <el-button size="small" @click="loadThreads">刷新</el-button>
        </div>
      </template>
      <el-table :data="threads" size="small" stripe border v-loading="loading" height="300">
        <el-table-column prop="thread_id" label="会话 ID" width="200" show-overflow-tooltip />
        <el-table-column prop="first_question" label="首个提问" min-width="260" show-overflow-tooltip />
        <el-table-column prop="message_count" label="消息数" width="90" align="right" />
        <el-table-column prop="user_messages" label="提问数" width="90" align="right" />
        <el-table-column prop="provider" label="渠道" width="110" />
        <el-table-column prop="model" label="模型" width="160" show-overflow-tooltip />
        <el-table-column prop="last_at" label="最后活跃" width="170" :formatter="timeCol" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { listApprovals, listThreads } from '@/admin/api';
import { fmtTime } from '@/utils/time';

const timeCol = (row, col, val) => fmtTime(val);

const loading = ref(false);
const approvals = ref([]);
const threads = ref([]);
const status = ref('');

function statusType(s) {
  return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', EDITED: 'primary' }[s] || 'info';
}
function statusText(s) {
  return { PENDING: '待审批', APPROVED: '已批准', REJECTED: '已拒绝', EDITED: '已改参' }[s] || s;
}

async function loadApprovals() {
  loading.value = true;
  try {
    approvals.value = await listApprovals(status.value || undefined);
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

async function loadThreads() {
  loading.value = true;
  try {
    threads.value = await listThreads();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  loadApprovals();
  loadThreads();
});
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.card-title { font-size: 15px; font-weight: 600; color: #111827; }
.card-sub { font-size: 12px; color: #9ca3af; margin-top: 3px; }
.filter-bar { display: flex; gap: 8px; margin-bottom: 10px; }
</style>
