<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <div class="card-title">{{ title }}</div>
          <div class="card-sub">{{ sub }}</div>
        </div>
      </div>
    </template>
    <CrudTable :tabs="tabs" />
  </el-card>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import CrudTable from '@/admin/components/CrudTable.vue';
import { EMPLOYEE_TABS, ORDER_TABS, PURCHASE_TABS, WMS_TABS } from '@/admin/businessDefs';

const route = useRoute();

const GROUPS = {
  hr: { title: '员工与职位', sub: '人力资源主数据：员工档案与职位编制（在岗员工的职位不可删除）', tabs: EMPLOYEE_TABS },
  wms: { title: '仓储与库存', sub: '仓库、SKU 与实时库存（低于安全库存会标记为预警）', tabs: WMS_TABS },
  order: { title: '订单与配送单', sub: '销售订单与配送单台账（复杂业务办理仍建议通过对话完成）', tabs: ORDER_TABS },
  purchase: { title: '采购与退货', sub: '采购入库与退货入库，状态置为「已入库」会自动增加库存', tabs: PURCHASE_TABS }
};

const group = computed(() => GROUPS[route.meta.group] || GROUPS.hr);
const title = computed(() => group.value.title);
const sub = computed(() => group.value.sub);
const tabs = computed(() => group.value.tabs);
</script>

<style scoped>
.card-header { display: flex; align-items: flex-start; justify-content: space-between; }
.card-title { font-size: 15px; font-weight: 600; color: #111827; }
.card-sub { font-size: 12px; color: #9ca3af; margin-top: 3px; }
</style>
