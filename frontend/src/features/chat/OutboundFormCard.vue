<template>
  <FormCard
    :data="data"
    :icon="Box"
    submit-text="确认出库"
    :initial="initial"
    :submitting="submitting"
    @submit="submit"
    @cancel="emit('cancel')"
  />
</template>

<script setup>
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Box } from '@element-plus/icons-vue';
import FormCard from './FormCard.vue';
import { outboundSubmit } from '@/api/delivery';

const props = defineProps({ data: { type: Object, required: true } });
const emit = defineEmits(['done', 'cancel']);

const submitting = ref(false);
const initial = { warehouseCode: '', sku: '', quantity: null, receiver: '', operator: '' };

async function submit(form) {
  if (submitting.value) return;
  submitting.value = true;
  try {
    const res = await outboundSubmit(form);
    emit('done', res);
  } catch (e) {
    ElMessage.error(e.message || '提交失败');
  } finally {
    submitting.value = false;
  }
}
</script>
