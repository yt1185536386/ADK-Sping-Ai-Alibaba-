<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <div class="card-title">外部工具配置</div>
          <div class="card-sub">MCP 工具（天气 / 高德地图等）的接口地址与密钥，密钥加密存储，接口不回显明文</div>
        </div>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </template>

    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="toolKey" label="工具标识" width="120" />
      <el-table-column prop="name" label="名称" width="140" />
      <el-table-column prop="baseUrl" label="接口地址" min-width="220" show-overflow-tooltip />
      <el-table-column prop="authType" label="鉴权方式" width="120" />
      <el-table-column prop="authKeyName" label="鉴权参数名" width="120" />
      <el-table-column prop="timeoutMs" label="超时(ms)" width="100" align="right" />
      <el-table-column label="密钥" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.keyConfigured ? 'success' : 'info'" size="small">
            {{ row.keyConfigured ? '已配置' : '免鉴权/未配置' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="140" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" title="外部工具配置" width="560px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="工具标识">
          <el-input v-model="form.toolKey" :disabled="formMode === 'edit'" />
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="接口地址"><el-input v-model="form.baseUrl" /></el-form-item>
        <el-form-item label="鉴权方式">
          <el-select v-model="form.authType" style="width:100%">
            <el-option label="none（免鉴权）" value="none" />
            <el-option label="query-key（query 参数）" value="query-key" />
            <el-option label="bearer" value="bearer" />
            <el-option label="header-key" value="header-key" />
            <el-option label="js-key（前端 JS API）" value="js-key" />
          </el-select>
        </el-form-item>
        <el-form-item label="鉴权参数名">
          <el-input v-model="form.authKeyName" placeholder="如 key / Authorization" />
        </el-form-item>
        <el-form-item label="密钥">
          <el-input v-model="form.apiKey" type="password" show-password placeholder="留空表示不修改当前密钥" />
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="form.timeoutMs" :min="1000" :max="120000" :step="1000" controls-position="right" style="width:160px" />
        </el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { deleteTool, listTools, saveTool } from '@/admin/api';

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const formVisible = ref(false);
const formMode = ref('create');
const form = reactive({ toolKey: '', name: '', baseUrl: '', authType: 'none', authKeyName: '', apiKey: '', timeoutMs: 10000, enabled: true, remark: '' });

async function load() {
  loading.value = true;
  try {
    rows.value = await listTools();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

function openEdit(row) {
  Object.assign(form, {
    toolKey: row.toolKey,
    name: row.name || '',
    baseUrl: row.baseUrl || '',
    authType: row.authType || 'none',
    authKeyName: row.authKeyName || '',
    apiKey: '',
    timeoutMs: row.timeoutMs || 10000,
    enabled: row.enabled !== false,
    remark: row.remark || ''
  });
  formMode.value = 'edit';
  formVisible.value = true;
}

async function save() {
  if (!form.toolKey || !form.toolKey.trim()) {
    ElMessage.warning('请填写工具标识');
    return;
  }
  saving.value = true;
  try {
    await saveTool({ ...form });
    ElMessage.success('已保存');
    formVisible.value = false;
    load();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除工具「${row.name || row.toolKey}」？`, '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch (e) { return; }
  try {
    await deleteTool(row.toolKey);
    ElMessage.success('已删除');
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(load);
</script>

<style scoped>
.card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.card-title { font-size: 15px; font-weight: 600; color: #111827; }
.card-sub { font-size: 12px; color: #9ca3af; margin-top: 3px; }
</style>
