<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <div class="card-title">用户与角色</div>
          <div class="card-sub">
            角色说明：ADMIN 系统管理员（可进入管理端）/ APPROVER 审批员 / USER 普通用户 / COURIER 配送员（仅可查看自己的配送单）
          </div>
        </div>
        <div class="header-actions">
          <el-button size="small" @click="load">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>新增用户
          </el-button>
        </div>
      </div>
    </template>

    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="username" label="登录账号" width="130" />
      <el-table-column prop="display_name" label="显示名" width="120" />
      <el-table-column label="角色" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="roleType(row.role)" size="small">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关联员工" width="150">
        <template #default="{ row }">
          <span v-if="row.employee_name">
            {{ row.employee_name }}
            <span v-if="row.phone" class="text-muted">（{{ row.phone }}）</span>
          </span>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="Number(row.enabled) === 1 ? 'success' : 'info'" size="small">
            {{ Number(row.enabled) === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" width="170" :formatter="timeCol" />
      <el-table-column label="操作" width="200" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="warning" size="small" @click="openPassword(row)">重置密码</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新增用户' : `编辑：${form.username}`" width="520px" destroy-on-close>
      <el-form :model="form" label-width="90px">
        <el-form-item label="登录账号" required>
          <el-input v-model="form.username" :disabled="formMode === 'edit'" />
        </el-form-item>
        <el-form-item label="显示名"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item v-if="formMode === 'create'" label="初始密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width:100%">
            <el-option v-for="r in roles" :key="r" :label="roleLabel(r)" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联员工">
          <el-select v-model="form.employeeId" clearable filterable placeholder="配送员必须关联在职员工" style="width:100%">
            <el-option v-for="e in employees" :key="e.id" :label="`${e.name}${e.status ? '（' + e.status + '）' : ''}`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="禁用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="pwdVisible" title="重置密码" width="420px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="用户"><el-input :model-value="pwdForm.username" disabled /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="pwdForm.password" type="password" show-password placeholder="至少 6 位" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePassword">确认重置</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { createUser, deleteUser, listRoles, listUsers, resetUserPassword, updateUser } from '@/admin/api';
import { manage } from '@/api/manage';
import { fmtTime } from '@/utils/time';

const timeCol = (row, col, val) => fmtTime(val);

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const roles = ref(['ADMIN', 'APPROVER', 'USER', 'COURIER']);
const employees = ref([]);

const formVisible = ref(false);
const formMode = ref('create');
const form = reactive({ id: null, username: '', displayName: '', password: '', role: 'USER', employeeId: null, enabled: true });

const pwdVisible = ref(false);
const pwdForm = reactive({ id: null, username: '', password: '' });

const ROLE_LABELS = { ADMIN: '系统管理员', APPROVER: '审批员', USER: '普通用户', COURIER: '配送员' };
function roleLabel(r) { return ROLE_LABELS[r] || r; }
function roleType(r) { return { ADMIN: 'danger', APPROVER: 'warning', COURIER: 'primary' }[r] || 'info'; }

async function load() {
  loading.value = true;
  try {
    [rows.value, roles.value] = await Promise.all([listUsers(), listRoles()]);
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
  try {
    employees.value = await manage('/api/manage/employees?limit=500');
  } catch (e) { /* ignore */ }
}

function openCreate() {
  Object.assign(form, { id: null, username: '', displayName: '', password: '', role: 'USER', employeeId: null, enabled: true });
  formMode.value = 'create';
  formVisible.value = true;
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    username: row.username,
    displayName: row.display_name || '',
    password: '',
    role: row.role || 'USER',
    employeeId: row.employee_id || null,
    enabled: Number(row.enabled) === 1
  });
  formMode.value = 'edit';
  formVisible.value = true;
}

async function save() {
  saving.value = true;
  try {
    if (formMode.value === 'create') await createUser({ ...form });
    else await updateUser(form.id, { ...form });
    ElMessage.success('已保存');
    formVisible.value = false;
    load();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

function openPassword(row) {
  Object.assign(pwdForm, { id: row.id, username: row.username, password: '' });
  pwdVisible.value = true;
}

async function savePassword() {
  if (!pwdForm.password || pwdForm.password.length < 6) {
    ElMessage.warning('密码长度至少 6 位');
    return;
  }
  saving.value = true;
  try {
    await resetUserPassword(pwdForm.id, pwdForm.password);
    ElMessage.success('密码已重置');
    pwdVisible.value = false;
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    saving.value = false;
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除用户「${row.username}」？`, '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch (e) { return; }
  try {
    await deleteUser(row.id);
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
.card-sub { font-size: 12px; color: #9ca3af; margin-top: 3px; line-height: 1.6; }
.header-actions { display: flex; gap: 8px; flex-shrink: 0; }
.text-muted { color: #9ca3af; }
</style>
