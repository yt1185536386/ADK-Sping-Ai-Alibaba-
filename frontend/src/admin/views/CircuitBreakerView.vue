<template>
  <div>
    <el-card shadow="never" class="mb">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">熔断策略</div>
            <div class="card-sub">
              状态机：CLOSED（正常）→ 统计窗口内失败次数达阈值 → OPEN（熔断，停止路由）→ 冷却结束转 HALF_OPEN（放行探测）→ 成功则恢复，失败则重新熔断。
            </div>
          </div>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>

      <el-table :data="rows" size="small" stripe border v-loading="loading">
        <el-table-column prop="provider" label="渠道" width="130" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="stateType(row.state)" size="small">{{ stateText(row.state) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用熔断" width="100" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="v => toggleEnabled(row, v)" />
          </template>
        </el-table-column>
        <el-table-column prop="failureThreshold" label="失败阈值" width="90" align="center" />
        <el-table-column prop="windowSeconds" label="统计窗口(s)" width="110" align="center" />
        <el-table-column prop="openSeconds" label="冷却时长(s)" width="110" align="center" />
        <el-table-column prop="halfOpenMax" label="半开探测数" width="110" align="center" />
        <el-table-column prop="failCount" label="当前失败数" width="100" align="center" />
        <el-table-column label="剩余冷却" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.remainingSeconds > 0">{{ row.remainingSeconds }}s</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastError" label="最近失败原因" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="170" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openPolicy(row)">策略</el-button>
            <el-button link type="danger" size="small" @click="trip(row)">手动熔断</el-button>
            <el-button link type="success" size="small" @click="reset(row)">手动恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <div class="card-title">降级链</div>
            <div class="card-sub">
              主渠道熔断或调用失败时按优先级降级到备用渠道。为某渠道配置了降级规则后，该渠道不再自动追加「同模型 / 默认模型」候选。
            </div>
          </div>
          <div class="header-actions">
            <el-button size="small" @click="loadRules">刷新</el-button>
            <el-button size="small" type="primary" @click="openRule()">
              <el-icon><Plus /></el-icon>新增规则
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="rules" size="small" stripe border v-loading="loadingRules">
        <el-table-column prop="provider" label="主渠道" width="150" />
        <el-table-column prop="targetProvider" label="降级目标" width="150" />
        <el-table-column label="目标模型" width="180">
          <template #default="{ row }">
            <span>{{ row.targetModel || '（取目标渠道默认模型）' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="130" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRule(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 熔断策略编辑 -->
    <el-dialog v-model="policyVisible" title="熔断策略" width="520px" destroy-on-close>
      <el-form :model="policy" label-width="130px">
        <el-form-item label="渠道">
          <el-input :model-value="policy.provider" disabled />
        </el-form-item>
        <el-form-item label="启用熔断">
          <el-switch v-model="policy.enabled" />
        </el-form-item>
        <el-form-item label="失败阈值">
          <el-input-number v-model="policy.failureThreshold" :min="1" :max="100" controls-position="right" style="width:160px" />
          <div class="form-tip">统计窗口内累计失败达到该次数即熔断</div>
        </el-form-item>
        <el-form-item label="统计窗口(秒)">
          <el-input-number v-model="policy.windowSeconds" :min="1" :max="3600" controls-position="right" style="width:160px" />
          <div class="form-tip">失败计数的统计窗口，超过该时长未再失败则重新计数</div>
        </el-form-item>
        <el-form-item label="冷却时长(秒)">
          <el-input-number v-model="policy.openSeconds" :min="1" :max="86400" controls-position="right" style="width:160px" />
          <div class="form-tip">熔断后多久转为半开状态</div>
        </el-form-item>
        <el-form-item label="半开探测数">
          <el-input-number v-model="policy.halfOpenMax" :min="0" :max="10" controls-position="right" style="width:160px" />
          <div class="form-tip">半开状态下允许的探测调用次数</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePolicy">保存</el-button>
      </template>
    </el-dialog>

    <!-- 降级规则编辑 -->
    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? '编辑降级规则' : '新增降级规则'" width="520px" destroy-on-close>
      <el-form :model="ruleForm" label-width="110px">
        <el-form-item label="主渠道" required>
          <el-select v-model="ruleForm.provider" style="width:100%" placeholder="主渠道">
            <el-option v-for="c in channels" :key="c.provider" :label="`${c.name || c.provider}（${c.provider}）`" :value="c.provider" />
          </el-select>
        </el-form-item>
        <el-form-item label="降级目标" required>
          <el-select v-model="ruleForm.targetProvider" style="width:100%" placeholder="备用渠道">
            <el-option v-for="c in channels" :key="c.provider" :label="`${c.name || c.provider}（${c.provider}）`" :value="c.provider" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标模型">
          <el-input v-model="ruleForm.targetModel" placeholder="留空则取目标渠道默认模型" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="ruleForm.priority" :min="0" :max="99" controls-position="right" style="width:160px" />
          <div class="form-tip">数值小的先降级</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="ruleForm.enabled" active-text="启用" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="ruleForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import {
  deleteFallbackRule, listBreakers, listChannels, listFallbackRules,
  resetBreaker, saveFallbackRule, tripBreaker, updateBreaker
} from '@/admin/api';

const loading = ref(false);
const loadingRules = ref(false);
const saving = ref(false);
const rows = ref([]);
const rules = ref([]);
const channels = ref([]);

const policyVisible = ref(false);
const policy = reactive({ provider: '', enabled: true, failureThreshold: 3, windowSeconds: 60, openSeconds: 120, halfOpenMax: 1 });

const ruleVisible = ref(false);
const ruleForm = reactive({ id: null, provider: '', targetProvider: '', targetModel: '', priority: 0, enabled: true, remark: '' });

function stateType(state) {
  return { CLOSED: 'success', OPEN: 'danger', HALF_OPEN: 'warning' }[state] || 'info';
}
function stateText(state) {
  return { CLOSED: '正常', OPEN: '已熔断', HALF_OPEN: '半开探测' }[state] || state;
}

async function load() {
  loading.value = true;
  try {
    rows.value = await listBreakers();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

async function loadRules() {
  loadingRules.value = true;
  try {
    rules.value = await listFallbackRules();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loadingRules.value = false;
  }
}

async function loadChannels() {
  try {
    channels.value = await listChannels();
  } catch (e) { /* ignore */ }
}

function openPolicy(row) {
  Object.assign(policy, {
    provider: row.provider,
    enabled: row.enabled,
    failureThreshold: row.failureThreshold,
    windowSeconds: row.windowSeconds,
    openSeconds: row.openSeconds,
    halfOpenMax: row.halfOpenMax
  });
  policyVisible.value = true;
}

async function savePolicy() {
  saving.value = true;
  try {
    await updateBreaker(policy.provider, { ...policy });
    ElMessage.success('策略已更新（10 秒内生效）');
    policyVisible.value = false;
    load();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row, v) {
  try {
    await updateBreaker(row.provider, { ...row, enabled: v });
    ElMessage.success(v ? '已启用熔断' : '已关闭熔断');
    load();
  } catch (e) {
    ElMessage.error(e.message);
    load();
  }
}

async function trip(row) {
  try {
    await ElMessageBox.confirm(`确认手动熔断渠道「${row.provider}」？该渠道会立即停止参与路由，需手动恢复。`,
      '手动熔断', { type: 'warning', confirmButtonText: '熔断', cancelButtonText: '取消' });
  } catch (e) { return; }
  try {
    await tripBreaker(row.provider);
    ElMessage.success('已熔断');
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function reset(row) {
  try {
    await resetBreaker(row.provider);
    ElMessage.success('已恢复');
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function openRule(row) {
  Object.assign(ruleForm, {
    id: row?.id ?? null,
    provider: row?.provider || channels.value[0]?.provider || '',
    targetProvider: row?.targetProvider || '',
    targetModel: row?.targetModel || '',
    priority: row?.priority ?? 0,
    enabled: row ? row.enabled : true,
    remark: row?.remark || ''
  });
  ruleVisible.value = true;
}

async function saveRule() {
  if (!ruleForm.provider || !ruleForm.targetProvider) {
    ElMessage.warning('请选择主渠道与降级目标');
    return;
  }
  if (ruleForm.provider === ruleForm.targetProvider) {
    ElMessage.warning('降级目标不能与主渠道相同');
    return;
  }
  saving.value = true;
  try {
    await saveFallbackRule({ ...ruleForm });
    ElMessage.success('已保存');
    ruleVisible.value = false;
    loadRules();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function removeRule(row) {
  try {
    await ElMessageBox.confirm(`确认删除降级规则「${row.provider} → ${row.targetProvider}」？`, '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch (e) { return; }
  try {
    await deleteFallbackRule(row.id);
    ElMessage.success('已删除');
    loadRules();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(() => {
  load();
  loadRules();
  loadChannels();
});
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.card-title { font-size: 15px; font-weight: 600; color: #111827; }
.card-sub { font-size: 12px; color: #9ca3af; margin-top: 3px; line-height: 1.6; }
.header-actions { display: flex; gap: 8px; flex-shrink: 0; }
.text-muted { color: #c0c4cc; }
.form-tip { font-size: 11px; color: #9ca3af; line-height: 1.5; margin-top: 2px; }
</style>
