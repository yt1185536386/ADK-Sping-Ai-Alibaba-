<template>
  <div>
    <el-tabs v-model="tabIndex" @tab-change="loadTab">
      <el-tab-pane v-for="(t, i) in tabs" :key="t.name" :label="t.name" :name="String(i)" />
    </el-tabs>

    <div class="toolbar">
      <el-button v-if="currentTab.form" type="primary" size="small" @click="openCreate">
        <el-icon><Plus /></el-icon>新增
      </el-button>
      <el-button size="small" @click="loadTab">刷新</el-button>
      <span class="toolbar-count">共 {{ rows.length }} 条</span>
    </div>

    <el-table :data="rows" size="small" stripe border v-loading="loading" height="calc(100vh - 300px)">
      <el-table-column
        v-for="col in currentCols"
        :key="col[0]"
        :prop="col[0]"
        :label="col[1]"
        :class-name="col[2] ? 'wrap' : ''"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <el-tag v-if="col[3]" :type="statusType(row[col[0]])" size="small" effect="light">{{ fmtVal(row[col[0]]) }}</el-tag>
          <span v-else>{{ fmtVal(row[col[0]]) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="currentTab.form" :width="currentTab.extra ? 168 : 120" fixed="right" align="center">
        <template #header>操作</template>
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="currentTab.extra === 'cancelDelivery' && row.status !== '已送达' && row.status !== '已取消'"
            link type="warning" size="small" @click="cancelRow(row)"
          >撤销</el-button>
          <el-button link type="danger" size="small" @click="removeRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增 / 编辑表单 -->
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新增' : '编辑'" width="520px" append-to-body destroy-on-close>
      <el-form :model="form" label-width="90px" size="default">
        <el-form-item v-for="f in visibleFormFields" :key="f.key" :label="f.label" :required="f.required">
          <el-input v-if="f.type === 'readonly'" :model-value="form[f.key] || '—'" disabled />
          <el-select v-else-if="f.type === 'select' && f.ref" v-model="form[f.key]" filterable clearable style="width:100%">
            <el-option v-for="o in refList(f.ref)" :key="o.id" :label="refLabel(f.ref, o)" :value="o.id" />
          </el-select>
          <el-select v-else-if="f.type === 'select'" v-model="form[f.key]" clearable style="width:100%">
            <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
          </el-select>
          <el-date-picker v-else-if="f.type === 'date'" v-model="form[f.key]" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          <el-date-picker v-else-if="f.type === 'datetime'" v-model="form[f.key]" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
          <el-input-number v-else-if="f.type === 'number'" v-model="form[f.key]" :min="0" :controls-position="'right'" style="width:100%" />
          <el-input v-else-if="f.type === 'textarea'" v-model="form[f.key]" type="textarea" :rows="2" />
          <el-input v-else v-model="form[f.key]" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { manage, manageCreate, manageDelete, manageUpdate } from '@/api/manage';
import { REF_DEFS } from '@/admin/businessDefs';

const props = defineProps({
  /** 业务表定义（见 admin/businessDefs.js） */
  tabs: { type: Array, required: true }
});

const tabIndex = ref('0');
const rows = ref([]);
const loading = ref(false);
const saving = ref(false);
const currentTab = computed(() => props.tabs[Number(tabIndex.value)] || {});
const currentCols = computed(() => currentTab.value.cols || []);

const formVisible = ref(false);
const formMode = ref('create');
const formEditId = ref(null);
const form = reactive({});

const visibleFormFields = computed(() =>
  (currentTab.value.form || []).filter(f =>
    !(f.onlyCreate && formMode.value === 'edit') && !(f.onlyEdit && formMode.value === 'create'))
);

const refData = reactive({ positions: [], warehouses: [], items: [], orders: [], couriers: [] });
function refList(name) { return refData[name] || []; }
function refLabel(name, o) { return REF_DEFS[name] ? REF_DEFS[name].label(o) : o.name; }

async function loadRefs(tab) {
  const names = [...new Set((tab.form || []).filter(f => f.ref).map(f => f.ref))];
  await Promise.all(names.map(async n => {
    if (refData[n]?.length) return;
    try { refData[n] = await manage(REF_DEFS[n].url); } catch (e) { refData[n] = []; }
  }));
}

function snake(k) { return k.replace(/[A-Z]/g, c => '_' + c.toLowerCase()); }

function openCreate() {
  formMode.value = 'create';
  formEditId.value = null;
  Object.keys(form).forEach(k => delete form[k]);
  (currentTab.value.form || []).forEach(f => { form[f.key] = f.type === 'number' ? null : ''; });
  loadRefs(currentTab.value);
  formVisible.value = true;
}

function openEdit(row) {
  formMode.value = 'edit';
  formEditId.value = row.id;
  Object.keys(form).forEach(k => delete form[k]);
  (currentTab.value.form || []).forEach(f => {
    if (typeof f.value === 'function') { form[f.key] = f.value(row); return; }
    const v = row[f.key] !== undefined ? row[f.key] : row[snake(f.key)];
    form[f.key] = v === null || v === undefined ? (f.type === 'number' ? null : '') : v;
  });
  loadRefs(currentTab.value);
  formVisible.value = true;
}

async function saveForm() {
  const tab = currentTab.value;
  for (const f of tab.form || []) {
    if (f.type === 'readonly') continue;
    if (f.onlyCreate && formMode.value === 'edit') continue;
    if (f.required && (form[f.key] === '' || form[f.key] === null || form[f.key] === undefined)) {
      ElMessage.warning(`请填写「${f.label}」`);
      return;
    }
  }
  const payload = {};
  (tab.form || []).forEach(f => {
    if (f.type === 'readonly') return;
    if (f.onlyCreate && formMode.value === 'edit') return;
    payload[f.key] = form[f.key] === '' ? null : form[f.key];
  });
  saving.value = true;
  try {
    if (formMode.value === 'create') await manageCreate(tab.url, payload);
    else await manageUpdate(tab.url + '/' + formEditId.value, payload);
    ElMessage.success(formMode.value === 'create' ? '新增成功' : '已保存');
    formVisible.value = false;
    loadTab();
  } catch (e) {
    ElMessage.error(e.message);
  } finally { saving.value = false; }
}

async function removeRow(row) {
  const label = row.name || row.title || row.order_no || row.delivery_no || row.purchase_no || row.return_no || ('ID ' + row.id);
  try {
    await ElMessageBox.confirm(`确认删除「${label}」？该操作不可恢复。`, '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch (e) { return; }
  try {
    await manageDelete(currentTab.value.url + '/' + row.id);
    ElMessage.success('已删除');
    loadTab();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function cancelRow(row) {
  try {
    await ElMessageBox.confirm(`确认撤销配送单「${row.delivery_no}」？关联订单将同步取消。`, '撤销确认',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' });
  } catch (e) { return; }
  try {
    await manageCreate(currentTab.value.url + '/' + row.id + '/cancel', {});
    ElMessage.success('已撤销');
    loadTab();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function fmtVal(v) {
  if (v === null || v === undefined || v === '') return '—';
  return String(v).replace('T', ' ').replace(/\.0$/, '');
}

function statusType(v) {
  if (v === null || v === undefined) return 'info';
  const s = String(v);
  const good = ['在职', '启用', '正常', '已送达', '已入库', '已发货'];
  const bad = ['离职', '停用', '预警', '已取消', '已退货'];
  if (good.includes(s)) return 'success';
  if (bad.includes(s)) return 'danger';
  return 'info';
}

async function loadTab() {
  const tab = currentTab.value;
  if (!tab.url) return;
  loading.value = true;
  rows.value = [];
  try {
    rows.value = await manage(tab.url);
  } catch (e) {
    rows.value = [];
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}

onMounted(loadTab);

// 父组件复用本组件但传入不同 tabs（如业务数据 4 个子菜单共用 BusinessView）时，
// 重置到第一个页签并重新拉数据，避免停留在旧业务表的页签下
watch(() => props.tabs, (next, prev) => {
  if (next === prev) return;
  tabIndex.value = '0';
  loadTab();
});
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.toolbar-count { margin-left: auto; font-size: 12px; color: #9ca3af; }
:deep(.wrap) { white-space: normal; word-break: break-all; }
</style>
