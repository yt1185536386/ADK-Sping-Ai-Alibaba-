<template>
  <FormCard
    :data="data"
    :icon="Van"
    submit-text="确认配送"
    :initial="initial"
    :submitting="submitting"
    @submit="submit"
    @cancel="emit('cancel')"
  />
</template>

<script setup>
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Van } from '@element-plus/icons-vue';
import FormCard from './FormCard.vue';
import { deliveryFormSubmit } from '@/api/delivery';

const props = defineProps({ data: { type: Object, required: true } });
const emit = defineEmits(['done', 'cancel']);

const submitting = ref(false);
const initial = {
  sku: '', quantity: null, customer: '', customerPhone: '', address: '',
  employee: '', warehouseCode: '', deadline: '', unitPrice: null, remark: ''
};

async function submit(form) {
  if (submitting.value) return;
  submitting.value = true;
  try {
    const res = await deliveryFormSubmit(form);
    emit('done', res);
  } catch (e) {
    ElMessage.error(e.message || '提交失败');
  } finally {
    submitting.value = false;
  }
}
</script>
