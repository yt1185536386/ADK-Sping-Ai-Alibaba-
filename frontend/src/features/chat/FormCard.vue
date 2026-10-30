<template>
  <div class="form-card">
    <!-- 头部：图标 + 标题 + 提示 -->
    <div class="fc-head">
      <div class="fc-icon">
        <el-icon :size="18"><component :is="icon" /></el-icon>
      </div>
      <div class="fc-head-text">
        <div class="fc-title">{{ data.title || '表单' }}</div>
        <div v-if="data.hint" class="fc-hint">{{ data.hint }}</div>
      </div>
    </div>

    <!-- 动态表单：根据后端下发 fields 渲染 -->
    <el-form label-position="top" class="fc-form" @submit.prevent>
      <el-form-item
        v-for="f in data.fields"
        :key="f.name"
        :label="f.label"
        :required="f.required"
      >
        <!-- 下拉（可搜索） -->
        <el-select
          v-if="f.type === 'select'"
          v-model="form[f.name]"
          :placeholder="f.placeholder || '请选择' + f.label"
          filterable
          clearable
          style="width: 100%"
        >
          <el-option
            v-for="opt in f.options"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <!-- 数字：按 schema 的 min/max/precision 渲染 -->
        <el-input-number
          v-else-if="f.type === 'number'"
          v-model="form[f.name]"
          :min="f.min ?? 1"
          :max="f.max ?? 1000000"
          :precision="f.precision ?? 0"
          :step="f.precision ? 0.01 : 1"
          controls-position="right"
          style="width: 100%"
          :placeholder="f.placeholder"
        />
        <!-- 日期时间 -->
        <el-date-picker
          v-else-if="f.type === 'date'"
          v-model="form[f.name]"
          type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss"
          :placeholder="f.placeholder || '请选择' + f.label"
          style="width: 100%"
        />
        <!-- 多行文本 -->
        <el-input
          v-else-if="f.type === 'textarea'"
          v-model="form[f.name]"
          type="textarea"
          :rows="2"
          :placeholder="f.placeholder || '请输入' + f.label"
        />
        <!-- 单行文本 -->
        <el-input v-else v-model="form[f.name]" :placeholder="f.placeholder || '请输入' + f.label" clearable />
      </el-form-item>

      <!-- 所选商品的库存提示 -->
      <div v-if="stockHint" class="fc-stock">
        <el-icon :size="14"><InfoFilled /></el-icon>
        <span>{{ stockHint }}</span>
      </div>
    </el-form>

    <!-- 操作区 -->
    <div class="fc-actions">
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="emit('submit', { ...form })">
        {{ submitText }}
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import { InfoFilled } from '@element-plus/icons-vue';

const props = defineProps({
  data: { type: Object, required: true },      // { title, hint, fields, itemOptions, warehouseOptions }
  icon: { type: [Object, Function], required: true },
  submitText: { type: String, default: '确认提交' },
  initial: { type: Object, default: () => ({}) },
  submitting: { type: Boolean, default: false }
});
const emit = defineEmits(['submit', 'cancel']);

// 初始值：wrapper 传入的默认值 + 字段兜底
const form = ref({ ...(props.initial || {}) });
(props.data.fields || []).forEach(f => {
  if (form.value[f.name] === undefined) form.value[f.name] = f.type === 'number' ? null : '';
});

// 当前所选商品的库存提示
const stockHint = computed(() => {
  if (!form.value.sku) return '';
  const item = (props.data.itemOptions || []).find(o => o.value === form.value.sku);
  if (!item) return '';
  const wh = (props.data.warehouseOptions || []).find(o => o.value === form.value.warehouseCode);
  const whText = wh ? `（指定仓库：${wh.label}）` : '（未指定仓库时将自动选择库存充足的仓库）';
  return `当前总库存：${item.total_stock}${item.unit || ''}${whText}`;
});

// 校验：所有标记 required 的字段必填，数字必须 > 0
const canSubmit = computed(() =>
  (props.data.fields || []).every(f => {
    if (!f.required) return true;
    const v = form.value[f.name];
    if (f.type === 'number') return v !== null && v !== undefined && v > 0;
    return v !== null && v !== undefined && String(v).trim() !== '';
  })
);
</script>

<style scoped>
/* ===== 统一卡片容器：与聊天气泡同一套中性色 + 系统主色点缀 ===== */
.form-card {
  max-width: 560px;
  width: 100%;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  box-shadow: 0 8px 30px rgba(15, 23, 42, .08);
  padding: 20px 22px 16px;
  align-self: flex-start;
}

/* 头部：图标徽章 + 标题/提示，底部细分隔线 */
.fc-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 14px;
  margin-bottom: 16px;
  border-bottom: 1px solid #f1f3f5;
}
.fc-icon {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(99, 102, 241, .25);
}
.fc-title { font-size: 15px; font-weight: 600; color: #111827; line-height: 1.4; }
.fc-hint { font-size: 12.5px; color: #6b7280; margin-top: 3px; line-height: 1.6; }

/* 表单：顶标签布局，统一间距与标签字重 */
.fc-form :deep(.el-form-item) { margin-bottom: 16px; }
.fc-form :deep(.el-form-item__label) {
  padding-bottom: 6px !important;
  font-size: 13px;
  font-weight: 500;
  color: #374151;
  line-height: 1.4;
}

/* 库存提示：主色浅底信息条 */
.fc-stock {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12.5px;
  color: #4f46e5;
  background: #eef2ff;
  border: 1px solid #e0e7ff;
  border-radius: 8px;
  padding: 8px 12px;
  line-height: 1.6;
}
.fc-stock .el-icon { margin-top: 2px; flex: 0 0 14px; }

/* 操作区：右对齐，顶部分隔线 */
.fc-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid #f1f3f5;
}
</style>
