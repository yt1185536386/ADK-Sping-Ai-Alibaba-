<template>
  <div class="my-deliveries">
    <div class="page-header">
      <h2>我的配送单</h2>
      <div class="header-actions">
        <el-button @click="loadList">刷新</el-button>
      </div>
    </div>

    <!-- 状态筛选 -->
    <div class="filter-bar">
      <el-radio-group v-model="statusFilter" @change="loadList">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button label="待配送">待配送</el-radio-button>
        <el-radio-button label="配送中">配送中</el-radio-button>
        <el-radio-button label="已送达">已送达</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 配送单卡片列表 -->
    <div v-if="rows.length" class="card-list">
      <div v-for="row in rows" :key="row.id" class="delivery-card" :class="row.status">
        <div class="card-head">
          <span class="delivery-no">{{ row.delivery_no }}</span>
          <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
        </div>
        <div class="card-body">
          <div class="info-row">
            <span class="label">订单：</span>
            <span>{{ row.order_no }} / {{ row.customer }}</span>
          </div>
          <div class="info-row">
            <span class="label">商品：</span>
            <span>{{ row.item_name }} × {{ row.quantity }} {{ row.unit }}</span>
          </div>
          <div class="info-row">
            <span class="label">发货仓：</span>
            <span>{{ row.warehouse_name }}（{{ row.warehouse_code }}）</span>
          </div>
          <div class="info-row">
            <span class="label">目的地：</span>
            <span>{{ row.destination || row.address || '-' }}</span>
          </div>
          <div class="info-row">
            <span class="label">截止：</span>
            <span>{{ row.deadline || '-' }}</span>
          </div>
          <div v-if="row.outbound_no" class="info-row">
            <span class="label">出库单：</span>
            <span>{{ row.outbound_no }}</span>
          </div>
        </div>
        <div class="card-foot">
          <el-button v-if="row.status === '配送中'" type="primary" size="small" @click="viewRoute(row)">
            <el-icon><MapLocation /></el-icon>查看路线
          </el-button>
          <el-button v-if="row.status === '配送中'" type="success" size="small" @click="confirmDelivered(row)">
            <el-icon><CircleCheck /></el-icon>确认送达
          </el-button>
          <el-button size="small" @click="viewDetail(row)">详情</el-button>
        </div>
      </div>
    </div>
    <el-empty v-else description="暂无配送单" />

    <!-- 详情/路线对话框 -->
    <el-dialog v-model="detailVisible" :title="detailTitle" width="720px">
      <div v-if="detail" class="detail-body">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="配送单号">{{ detail.delivery_no }}</el-descriptions-item>
          <el-descriptions-item label="订单号">{{ detail.order_no }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.customer }}</el-descriptions-item>
          <el-descriptions-item label="商品">{{ detail.item_name }} × {{ detail.quantity }} {{ detail.unit }}</el-descriptions-item>
          <el-descriptions-item label="发货仓库">{{ detail.warehouse_name }}（{{ detail.warehouse_code }}）</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)" size="small">{{ detail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="出库单号">{{ detail.outbound_no || '-' }}</el-descriptions-item>
          <el-descriptions-item label="配送地址" :span="2">{{ detail.address || '-' }}</el-descriptions-item>
          <el-descriptions-item label="目的地" :span="2">{{ detail.destination || '-' }}</el-descriptions-item>
          <el-descriptions-item label="截止时间" :span="2">{{ detail.deadline || '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 路线信息 -->
        <div v-if="routeInfo" class="route-info">
          <h4>配送路线</h4>
          <p>距离：{{ routeInfo.distanceText }} | 预计时长：{{ routeInfo.durationText }}</p>
          <ol class="step-list">
            <li v-for="(s, i) in routeInfo.steps" :key="i">{{ s }}</li>
          </ol>
        </div>
        <div v-else class="route-info empty">
          <p>暂无路线规划数据</p>
        </div>

        <!-- 地图展示 -->
        <div v-if="detail.from_lng && detail.to_lng" class="map-container">
          <AmapMap :spec="routeMapSpec" />
        </div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { MapLocation, CircleCheck } from '@element-plus/icons-vue';
import { deliveryMyList, deliveryMyDetail, deliveryMyDelivered } from '@/api/delivery';
import AmapMap from '@/components/AmapMap.vue';

const loading = ref(false);
const rows = ref([]);
const statusFilter = ref('');
const detailVisible = ref(false);
const detail = ref(null);

const detailTitle = computed(() => detail.value ? `配送单详情：${detail.value.delivery_no}` : '配送单详情');

const routeInfo = computed(() => {
  if (!detail.value?.route_json) return null;
  try { return JSON.parse(detail.value.route_json); } catch { return null; }
});

const routeMapSpec = computed(() => {
  if (!detail.value?.from_lng || !detail.value?.to_lng) return null;
  return {
    type: 'route',
    mode: 'driving',
    origin: detail.value.warehouse_name || '起点',
    destination: detail.value.destination || '终点',
    from: `${detail.value.from_lng},${detail.value.from_lat}`,
    to: `${detail.value.to_lng},${detail.value.to_lat}`
  };
});

function statusType(s) {
  return { '待配送': 'info', '配送中': 'warning', '已送达': 'success', '已取消': 'danger' }[s] || 'info';
}

async function loadList() {
  loading.value = true;
  try {
    rows.value = await deliveryMyList(statusFilter.value || undefined);
  } catch (e) {
    ElMessage.error(e.message);
  } finally { loading.value = false; }
}

function viewRoute(row) {
  viewDetail(row);
}

async function viewDetail(row) {
  try {
    detail.value = await deliveryMyDetail(row.delivery_no);
    detailVisible.value = true;
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function confirmDelivered(row) {
  try {
    await ElMessageBox.confirm(`确认送达配送单「${row.delivery_no}」？`, '送达确认', { type: 'success' });
  } catch { return; }
  try {
    await deliveryMyDelivered(row.delivery_no);
    ElMessage.success('已确认送达');
    loadList();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

onMounted(() => loadList());
</script>

<style scoped>
.my-deliveries { padding: 20px; background: #f6f7f9; min-height: 100vh; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 18px; color: #1f2329; }
.filter-bar { margin-bottom: 16px; }
.card-list { display: flex; flex-direction: column; gap: 12px; }
.delivery-card { background: #fff; border-radius: 12px; border: 1px solid #e5e7eb; overflow: hidden; }
.delivery-card.配送中 { border-left: 4px solid #e6a23c; }
.delivery-card.已送达 { border-left: 4px solid #67c23a; }
.card-head { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; background: #fafafa; border-bottom: 1px solid #f0f0f0; }
.delivery-no { font-weight: 600; font-size: 14px; color: #1f2329; }
.card-body { padding: 12px 16px; }
.info-row { display: flex; font-size: 13px; margin-bottom: 6px; }
.info-row .label { color: #909399; width: 60px; flex-shrink: 0; }
.card-foot { padding: 12px 16px; border-top: 1px solid #f0f0f0; display: flex; gap: 8px; }
.detail-body { max-height: 60vh; overflow-y: auto; }
.route-info { margin-top: 16px; padding: 12px; background: #f6f7fb; border-radius: 8px; }
.route-info h4 { margin: 0 0 8px; font-size: 14px; color: #1f2329; }
.route-info p { margin: 0 0 8px; font-size: 13px; color: #6b7280; }
.route-info.empty p { color: #9ca3af; }
.step-list { margin: 0; padding-left: 20px; font-size: 13px; color: #1f2329; line-height: 1.8; }
.map-container { margin-top: 12px; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
</style>
