<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <div class="card-title">模型来源管理</div>
          <div class="card-sub">维护模型渠道（api_key 表）：协议、地址、可用模型、密钥与优先级。保存后模型实例缓存立即刷新，无需重启后端。</div>
        </div>
        <div class="header-actions">
          <el-button size="small" @click="load">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>新增渠道
          </el-button>
        </div>
      </div>
    </template>

    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="provider" label="渠道标识" width="130" />
      <el-table-column prop="name" label="名称" width="140" />
      <el-table-column prop="vendor" label="厂商" width="100" />
      <el-table-column prop="protocol" label="协议" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.protocol || 'openai' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="baseUrl" label="接口地址" min-width="220" show-overflow-tooltip />
      <el-table-column label="可用模型" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="m in (row.chatModels || []).slice(0, 4)" :key="m" size="small" effect="plain" class="model-tag">{{ m }}</el-tag>
          <span v-if="(row.chatModels || []).length > 4" class="more">+{{ row.chatModels.length - 4 }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="defaultModel" label="默认模型" width="150" show-overflow-tooltip />
      <el-table-column label="密钥" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.keyConfigured ? 'success' : 'danger'" size="small">
            {{ row.keyConfigured ? '已配置' : '未配置' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="权重" width="70" align="center" prop="weight" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="默认" width="70" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.isDefault" type="primary" size="small">默认</el-tag>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="success" size="small" @click="test(row)">测试</el-button>
          <el-button link type="warning" size="small" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button v-if="!row.isDefault" link type="primary" size="small" @click="setDefault(row)">设为默认</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 编辑 / 新增 -->
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新增模型来源' : `编辑：${form.provider}`"
               width="680px" destroy-on-close>
      <el-form :model="form" label-width="100px" size="default">
        <el-form-item label="渠道标识" required>
          <el-input v-model="form.provider" :disabled="formMode === 'edit'" placeholder="如 bailian / deepseek / company" />
          <div class="form-tip">唯一标识，对应 api_key.provider，客户端与降级规则按它引用</div>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="如：百炼模型" /></el-form-item>
        <el-form-item label="厂商"><el-input v-model="form.vendor" placeholder="如 qwen / deepseek / openai" /></el-form-item>
        <el-form-item label="协议">
          <el-select v-model="form.protocol" style="width:100%">
            <el-option label="openai（OpenAI 兼容）" value="openai" />
            <el-option label="dashscope" value="dashscope" />
          </el-select>
        </el-form-item>
        <el-form-item label="接口地址"><el-input v-model="form.baseUrl" placeholder="如 https://dashscope.aliyuncs.com/compatible-mode/v1" /></el-form-item>
        <el-form-item label="可用模型">
          <div class="model-row">
            <el-select v-model="form.models" multiple filterable allow-create default-first-option
                       style="flex:1" placeholder="下拉选择或输入模型名后回车添加">
              <el-option v-for="m in allModelOptions" :key="m" :label="m" :value="m" />
            </el-select>
            <el-button :loading="fetchingModels" @click="fetchModels">拉取模型</el-button>
          </div>
          <div class="form-tip">「拉取模型」从渠道 /models 接口获取可选列表；含对话模型与向量模型，也可手动输入</div>
        </el-form-item>
        <el-form-item label="默认模型">
          <el-select v-model="form.defaultModel" filterable allow-create default-first-option clearable
                     style="width:100%" placeholder="前端未指定模型时使用">
            <el-option v-for="m in allModelOptions" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="向量模型">
          <el-select v-model="form.embeddingModel" filterable allow-create default-first-option clearable
                     style="width:100%" placeholder="用于语义检索的 embedding 模型">
            <el-option v-for="m in embeddingOptions" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="密钥">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="formMode === 'edit' ? '留空表示不修改当前密钥' : '输入该渠道的 API Key'" />
          <div class="form-tip">密钥加密入库，列表与接口都不会回显明文</div>
        </el-form-item>
        <el-form-item label="权重">
          <el-input-number v-model="form.weight" :min="0" :max="999" controls-position="right" style="width:160px" />
          <div class="form-tip">数值越大越优先（默认渠道由「设为默认」控制）</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          <el-divider direction="vertical" />
          <el-switch v-model="form.isDefault" active-text="设为默认渠道" />
        </el-form-item>
        <el-form-item label="来源说明"><el-input v-model="form.source" placeholder="如：阿里云百炼（OpenAI 兼容模式）" /></el-form-item>
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
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { deleteChannel, fetchChannelModels, listChannels, saveChannel, testChannel, updateChannel } from '@/admin/api';

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const formVisible = ref(false);
const formMode = ref('create');
const fetchedModels = ref([]);
const fetchingModels = ref(false);

const emptyForm = {
  provider: '', name: '', vendor: '', protocol: 'openai', baseUrl: '',
  models: [], defaultModel: '', embeddingModel: '', apiKey: '',
  weight: 0, enabled: true, isDefault: false, source: '', remark: ''
};
const form = reactive({ ...emptyForm });

/** 下拉候选：已拉取的模型 ∪ 表单里已有的模型（保证回显值始终可显示） */
const allModelOptions = computed(() => {
  const set = new Set([...fetchedModels.value, ...(form.models || [])]);
  if (form.defaultModel) set.add(form.defaultModel);
  if (form.embeddingModel) set.add(form.embeddingModel);
  return [...set];
});

/** 向量模型候选：优先 embedding 命名的模型；没有匹配时退化为全量 */
const embeddingOptions = computed(() => {
  const embed = allModelOptions.value.filter(m => /embed|bge|gte|text-embedding|\be5\b/i.test(m));
  return embed.length ? embed : allModelOptions.value;
});

async function fetchModels() {
  fetchingModels.value = true;
  try {
    const res = await fetchChannelModels({ provider: form.provider, baseUrl: form.baseUrl, apiKey: form.apiKey });
    if (res.ok) {
      fetchedModels.value = res.models || [];
      ElMessage.success(`已拉取 ${fetchedModels.value.length} 个模型`);
    } else {
      ElMessage.error(`拉取失败：${res.error || '未知错误'}`);
    }
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    fetchingModels.value = false;
  }
}

async function load() {
  loading.value = true;
  try {
    rows.value = await listChannels();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  Object.keys(form).forEach(k => delete form[k]);
  Object.assign(form, emptyForm, { models: [] });
  fetchedModels.value = [];
  formMode.value = 'create';
  formVisible.value = true;
}

function openEdit(row) {
  Object.keys(form).forEach(k => delete form[k]);
  Object.assign(form, {
    provider: row.provider,
    name: row.name || '',
    vendor: row.vendor || '',
    protocol: row.protocol || 'openai',
    baseUrl: row.baseUrl || '',
    models: [...(row.models || [])],
    defaultModel: row.defaultModel || '',
    embeddingModel: row.embeddingModel || '',
    apiKey: '',
    weight: row.weight || 0,
    enabled: row.enabled !== false,
    isDefault: !!row.isDefault,
    source: row.source || '',
    remark: row.remark || ''
  });
  fetchedModels.value = [];
  formMode.value = 'edit';
  formVisible.value = true;
}

async function save() {
  if (!form.provider || !form.provider.trim()) {
    ElMessage.warning('请填写渠道标识');
    return;
  }
  saving.value = true;
  try {
    if (formMode.value === 'create') await saveChannel({ ...form });
    else await updateChannel(form.provider, { ...form });
    ElMessage.success('已保存，模型实例缓存已刷新');
    formVisible.value = false;
    load();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row) {
  try {
    await updateChannel(row.provider, { ...row, enabled: !row.enabled });
    ElMessage.success(row.enabled ? '已停用' : '已启用');
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function setDefault(row) {
  try {
    await updateChannel(row.provider, { ...row, isDefault: true });
    ElMessage.success('已设为默认渠道');
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function test(row) {
  loading.value = true;
  try {
    const res = await testChannel(row.provider, row.defaultModel);
    if (res.ok) ElMessage.success(`连通正常：${(res.sample || '').slice(0, 60) || '调用成功'}`);
    else ElMessage.error(`连通失败：${res.error || '未知错误'}`);
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除渠道「${row.name || row.provider}」？`, '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch (e) { return; }
  try {
    await deleteChannel(row.provider);
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
.header-actions { display: flex; gap: 8px; flex-shrink: 0; }
.model-tag { margin: 1px 3px 1px 0; }
.more { font-size: 11px; color: #9ca3af; }
.text-muted { color: #c0c4cc; }
.model-row { display: flex; gap: 8px; width: 100%; }
.form-tip { font-size: 11px; color: #9ca3af; line-height: 1.5; margin-top: 2px; }
</style>
