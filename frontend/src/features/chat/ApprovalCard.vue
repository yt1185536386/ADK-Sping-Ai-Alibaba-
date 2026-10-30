<template>
  <div class="approval-card">
    <!-- 头部：警示图标 + 标题 + 提示（与表单卡片同一布局，琥珀色仅作语义警示） -->
    <div class="apv-head">
      <div class="apv-icon">
        <el-icon :size="18"><WarningFilled /></el-icon>
      </div>
      <div class="apv-head-text">
        <div class="apv-title">需要人工确认 · {{ moduleName }}</div>
        <div class="apv-hint">{{ hintText }}</div>
      </div>
    </div>

    <div v-for="item in items" :key="item.id" class="apv-item">
      <div class="apv-tool">
        <el-tag size="small" effect="plain">{{ item.tool }}</el-tag>
        <span style="margin-left:6px">{{ item.toolLabel || '' }}</span>
      </div>

      <!-- 逐件勾选 -->
      <div v-if="item.choices && item.choices.length" class="apv-choice">
        <div v-if="item.choiceHint" class="apv-choice-hint">{{ item.choiceHint }}</div>
        <el-checkbox
          v-for="c in item.choices"
          :key="c.value"
          v-model="checkedMap[item.id]"
          :label="c.value"
          :value="c.value"
          @change="syncChoice(item)"
        >{{ c.label }}</el-checkbox>
        <div class="apv-choice-count">已选 {{ (checkedMap[item.id] || []).length }} 件</div>
      </div>

      <!-- 明细预览 -->
      <div v-if="item.preview && item.preview.rows && item.preview.rows.length" class="apv-preview">
        <div class="apv-preview-title">{{ item.preview.title || '明细预览' }}</div>
        <div v-if="item.preview.hint" class="apv-preview-hint">{{ item.preview.hint }}</div>
        <div class="apv-preview-scroll">
          <el-table :data="item.preview.rows" size="small" border style="width:100%">
            <el-table-column
              v-for="col in item.preview.columns"
              :key="col.key"
              :prop="col.key"
              :label="col.label"
              :align="col.align === 'right' ? 'right' : 'left'"
              :class-name="col.wide ? 'wide' : ''"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <el-tag v-if="col.key === 'itemState' && row.itemStateTone" :type="row.itemStateTone === 'new' ? 'success' : 'info'" size="small" effect="light">
                  {{ row[col.key] }}
                </el-tag>
                <span v-else>{{ row[col.key] }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-if="item.preview.summary" class="apv-preview-summary">{{ item.preview.summary }}</div>
      </div>

      <!-- 原始参数：有可视化核对时默认收起 -->
      <div v-if="item.arguments">
        <div
          v-if="hasVisual(item)"
          class="apv-args-toggle"
          @click="toggleArgs(item.id)"
        >{{ argsOpen[item.id] ? '▾ 收起原始参数' : '▸ 展开原始参数（一般不用改）' }}</div>
        <el-input
          v-show="!hasVisual(item) || argsOpen[item.id]"
          v-model="argsMap[item.id]"
          type="textarea"
          :rows="Math.min(8, Math.max(2, prettyArgs(item.arguments).split('\n').length))"
          class="apv-args"
          spellcheck="false"
        />
      </div>

      <el-input v-model="reasonMap[item.id]" placeholder="备注 / 拒绝理由（可选）" class="apv-reason" />
    </div>

    <div class="apv-actions">
      <el-button :disabled="submitting" @click="submit('REJECT')">拒绝</el-button>
      <el-button type="primary" :disabled="submitting" :loading="submitting" @click="submit('APPROVE')">批准执行</el-button>
    </div>
    <div class="apv-note">{{ noteText }}</div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue';
import { WarningFilled } from '@element-plus/icons-vue';
import { submitApprovals } from '@/api/chat';

const props = defineProps({
  data: { type: Object, required: true }
});
const emit = defineEmits(['done', 'voided']);

const items = computed(() => props.data.approvals || []);
const moduleName = computed(() => props.data.moduleName || props.data.module || '');
const hasChoices = computed(() => items.value.some(a => a.choices && a.choices.length));
const hasPreview = computed(() => items.value.some(a => a.preview && a.preview.rows && a.preview.rows.length));
const hintText = computed(() => {
  if (hasPreview.value) return '下面把这次要写入的明细逐行列出来了，核对无误后点一次「批准执行」即可整批写入 —— 不需要逐条确认。';
  if (hasChoices.value) return '下面把每一件可选物品都列出来了，勾选你实际要处理的那几件即可（默认勾选的是 AI 选中的）。';
  return '参数可直接修改；改动过的话，点「批准执行」会按你修改后的参数执行。';
});

const argsMap = reactive({});
const reasonMap = reactive({});
const checkedMap = reactive({});
const argsOpen = reactive({});
const submitting = ref(false);
const noteText = ref('该操作会写入数据库，确认后即执行。');
const voided = ref(false);

function prettyArgs(raw) {
  try { return JSON.stringify(JSON.parse(raw), null, 2); } catch (e) { return raw || ''; }
}

function hasVisual(item) {
  return (item.choices && item.choices.length) || (item.preview && item.preview.rows && item.preview.rows.length);
}

function toggleArgs(id) {
  argsOpen[id] = !argsOpen[id];
}

function syncChoice(item) {
  if (!item.choiceField) return;
  let obj;
  try { obj = JSON.parse(argsMap[item.id]); } catch (e) { return; }
  obj[item.choiceField] = (checkedMap[item.id] || []).join(',');
  argsMap[item.id] = JSON.stringify(obj, null, 2);
}

onMounted(() => {
  items.value.forEach(item => {
    argsMap[item.id] = prettyArgs(item.arguments);
    reasonMap[item.id] = '';
    checkedMap[item.id] = (item.choices || []).filter(c => c.checked).map(c => c.value);
    argsOpen[item.id] = false;
  });
});

function voidCard() {
  voided.value = true;
  noteText.value = '已作废：你开始了新的对话。如需执行，请重新发起这次操作。';
  emit('voided');
}

async function submit(action) {
  if (voided.value || submitting.value) return;
  let bad = null;
  const decisions = items.value.map(item => {
    const current = (argsMap[item.id] || '').trim();
    let act = action;
    if (action === 'APPROVE' && item.arguments && current && current !== prettyArgs(item.arguments)) act = 'EDIT';
    const d = { id: item.id, action: act, reason: (reasonMap[item.id] || '').trim() };
    if (act === 'EDIT') {
      let parsed = null;
      try { parsed = JSON.parse(current); } catch (e) { bad = bad || (item.id + '：参数不是合法的 JSON（' + e.message + '）'); }
      if (parsed !== null && (typeof parsed !== 'object' || Array.isArray(parsed))) {
        bad = bad || (item.id + '：参数必须是一个 JSON 对象');
      }
      d.arguments = JSON.stringify(parsed !== null ? parsed : current);
    }
    return d;
  });
  if (bad) {
    emit('done', { status: 'error', reply: '提交前请先修正参数：' + bad });
    return;
  }
  submitting.value = true;
  try {
    const out = await submitApprovals(props.data.batchId, decisions);
    emit('done', out);
  } catch (e) {
    emit('done', { status: 'error', reply: '提交失败：' + e.message });
  } finally {
    submitting.value = false;
  }
}

defineExpose({ voidCard, approve: () => submit('APPROVE') });
</script>

<style scoped>
/* ===== 与 FormCard 同一套卡片语言：白底 + 中性边框 + 16px 圆角，琥珀色仅作警示语义 ===== */
.approval-card {
  max-width: 640px;
  width: 100%;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  box-shadow: 0 8px 30px rgba(15, 23, 42, .08);
  padding: 20px 22px 16px;
  max-height: min(70vh, 600px);
  overflow-y: auto;
}
.apv-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 14px;
  margin-bottom: 4px;
  border-bottom: 1px solid #f1f3f5;
}
.apv-icon {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #f59e0b, #f97316);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(245, 158, 11, .25);
}
.apv-title { font-size: 15px; font-weight: 600; color: #111827; line-height: 1.4; }
.apv-hint { font-size: 12.5px; color: #6b7280; margin-top: 3px; line-height: 1.6; }
.apv-item { border-top: 1px dashed #e5e7eb; padding: 12px 0; }
.apv-item:first-of-type { border-top: none; padding-top: 4px; }
.apv-tool { font-size: 13px; margin-bottom: 6px; }
.apv-args { width: 100%; font-family: Consolas, 'Courier New', monospace; font-size: 12px; }
.apv-args-toggle { font-size: 12px; color: #6366f1; margin-bottom: 6px; cursor: pointer; }
.apv-args-toggle:hover { text-decoration: underline; }
.apv-choice { border: 1px solid #eef0f3; border-radius: 8px; padding: 10px 12px; margin-bottom: 8px; background: #fafbfc; }
.apv-choice-hint { font-size: 12.5px; color: #92400e; margin-bottom: 6px; }
.apv-choice-count { font-size: 12px; color: #9ca3af; margin-top: 5px; }
.apv-preview { border: 1px solid #eef0f3; border-radius: 8px; margin-bottom: 8px; background: #fff; overflow: hidden; }
.apv-preview-title { font-size: 12.5px; font-weight: 500; color: #374151; padding: 8px 12px; border-bottom: 1px solid #eef0f3; background: #fafbfc; }
.apv-preview-hint { font-size: 12px; color: #9ca3af; padding: 6px 12px 0; line-height: 1.6; }
.apv-preview-scroll { max-height: 260px; overflow: auto; margin: 6px 0 0; }
.apv-preview-summary { font-size: 12.5px; color: #1f2329; padding: 8px 12px; border-top: 1px solid #eef0f3; background: #fafbfc; }
.apv-reason { margin-top: 6px; }
.apv-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 14px; padding-top: 14px; border-top: 1px solid #f1f3f5; }
.apv-note { font-size: 12px; color: #9ca3af; margin-top: 8px; }
</style>
