<template>
  <el-card shadow="never">
    <div class="page-header">
      <h2>配送作业台</h2>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>创建配送单
        </el-button>
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
        <el-radio-button label="已取消">已取消</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 配送单列表 -->
    <el-table :data="rows" v-loading="loading" stripe border style="width:100%">
      <el-table-column prop="delivery_no" label="配送单号" width="140" />
      <el-table-column prop="order_no" label="订单号" width="140" />
      <el-table-column prop="customer" label="客户" width="100" />
      <el-table-column prop="item_name" label="商品" width="120">
        <template #default="{ row }">{{ row.item_name }}（{{ row.quantity }} {{ row.unit }}）</template>
      </el-table-column>
      <el-table-column prop="warehouse_name" label="发货仓库" width="110" />
      <el-table-column prop="courier_name" label="配送员" width="100">
        <template #default="{ row }">
          <span v-if="row.courier_name">{{ row.courier_name }}</span>
          <span v-else class="text-muted">未指派</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="destination" label="目的地" min-width="140" show-overflow-tooltip />
      <el-table-column prop="deadline" label="截止时间" width="150" />
      <el-table-column label="操作" width="200" fixed="right" align="center">
        <template #default="{ row }">
          <el-button v-if="row.status === '待配送'" link type="primary" size="small" @click="confirmOutbound(row)">确认出库</el-button>
          <el-button v-if="row.status === '配送中'" link type="success" size="small" @click="viewRoute(row)">查看路线</el-button>
          <el-button v-if="row.status === '待配送'" link type="warning" size="small" @click="cancelDelivery(row)">取消</el-button>
          <el-button link type="info" size="small" @click="viewDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建配送单对话框 -->
    <el-dialog v-model="createVisible" title="创建配送单" width="720px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="关联订单" required>
          <el-select v-model="form.orderId" filterable placeholder="选择待配送订单" style="width:100%" @change="onOrderChange">
            <el-option v-for="o in orders" :key="o.id" :label="`${o.order_no} / ${o.customer} / ${o.item_name} ×${o.quantity}`" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="发货仓库" required>
          <el-select v-model="form.warehouseId" placeholder="选择发货仓库" style="width:100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="`${w.name}（${w.code}）`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="配送员" required>
          <el-select v-model="form.courierId" placeholder="选择配送员" style="width:100%">
            <el-option v-for="c in couriers" :key="c.id" :label="`${c.display_name}（${c.username}）`" :value="c.id" />
          </el-select>
          <div v-if="!couriers.length" class="form-tip">
            暂无配送员账号，请先在「权限管理 → 用户与角色」中创建 COURIER 角色的用户
          </div>
        </el-form-item>
        <el-form-item label="客户联系方式">
          <el-input v-model="form.customerPhone" placeholder="收货人手机号" />
        </el-form-item>
        <el-form-item label="配送地址">
          <el-input v-model="form.address" placeholder="详细地址" />
        </el-form-item>
        <el-form-item label="目的地">
          <el-input v-model="form.destination" placeholder="目的地名称（如 XX大厦）" />
        </el-form-item>
        <el-form-item label="地图选点">
          <div class="map-pick-row">
            <el-button size="small" :type="picking === 'from' ? 'primary' : ''" @click="startPick('from')">
              {{ picking === 'from' ? '点击地图选起点…' : '选起点' }}
            </el-button>
            <span v-if="form.fromLng" class="coord">{{ form.fromLng }}, {{ form.fromLat }}</span>
            <el-button size="small" :type="picking === 'to' ? 'primary' : ''" @click="startPick('to')">
              {{ picking === 'to' ? '点击地图选终点…' : '选终点' }}
            </el-button>
            <span v-if="form.toLng" class="coord">{{ form.toLng }}, {{ form.toLat }}</span>
          </div>
          <div v-if="mapVisible" class="map-container pick-map">
            <div ref="pickMapEl" class="pick-map-inner" />
            <div class="map-hint">点击地图设置{{ picking === 'from' ? '起点' : '终点' }}坐标</div>
          </div>
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker v-model="form.deadline" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCreate">创建配送单</el-button>
      </template>
    </el-dialog>

    <!-- 详情/路线对话框 -->
    <el-dialog v-model="detailVisible" :title="detailTitle" width="720px">
      <div v-if="detail" class="detail-body">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="配送单号">{{ detail.delivery_no }}</el-descriptions-item>
          <el-descriptions-item label="订单号">{{ detail.order_no }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.customer }}</el-descriptions-item>
          <el-descriptions-item label="商品">{{ detail.item_name }} × {{ detail.quantity }} {{ detail.unit }}</el-descriptions-item>
          <el-descriptions-item label="发货仓库">{{ detail.warehouse_name }}（{{ detail.warehouse_code }}）</el-descriptions-item>
          <el-descriptions-item label="配送员">{{ detail.courier_name }} {{ detail.courier_phone ? `(${detail.courier_phone})` : '' }}</el-descriptions-item>
          <el-descriptions-item label="客户联系方式">{{ detail.customer_phone || '-' }}</el-descriptions-item>
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
          <h4>路线规划</h4>
          <p>距离：{{ routeInfo.distanceText }} | 预计时长：{{ routeInfo.durationText }}</p>
          <ol class="step-list">
            <li v-for="(s, i) in routeInfo.steps" :key="i">{{ s }}</li>
          </ol>
        </div>
        <div v-else-if="detail.route_json" class="route-info">
          <h4>路线规划</h4>
          <pre>{{ detail.route_json }}</pre>
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
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { manage } from '@/api/manage';
import { deliveryList, deliveryDetail, deliveryCheckStock, deliveryCreate, deliveryConfirmOutbound, deliveryCancel } from '@/api/delivery';
import AmapMap from '@/components/AmapMap.vue';
import { loadAmap } from '@/utils/amap';

const loading = ref(false);
const saving = ref(false);
const rows = ref([]);
const statusFilter = ref('');
const createVisible = ref(false);
const detailVisible = ref(false);
const detail = ref(null);
const orders = ref([]);
const warehouses = ref([]);
const couriers = ref([]);

const form = ref({
  orderId: null, courierId: null, warehouseId: null,
  address: '', destination: '', customerPhone: '', deadline: '', remark: '',
  fromLng: null, fromLat: null, toLng: null, toLat: null
});

const mapVisible = ref(false);
const picking = ref(''); // 'from' | 'to'
const pickMapEl = ref(null);
let _pickMap = null;
let _pickMarker = null;

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
    rows.value = await deliveryList(statusFilter.value || undefined);
  } catch (e) {
    ElMessage.error(e.message);
  } finally { loading.value = false; }
}

async function loadRefs() {
  try {
    [orders.value, warehouses.value, couriers.value] = await Promise.all([
      manage('/api/manage/orders?status=待处理'),
      manage('/api/manage/warehouses'),
      manage('/api/manage/couriers')
    ]);
  } catch (e) { /* ignore */ }
}

function onOrderChange(orderId) {
  const o = orders.value.find(x => x.id === orderId);
  if (o) {
    form.value.destination = o.customer;
    form.value.address = o.delivery_address || '';
  }
}

function startPick(which) {
  picking.value = which;
  mapVisible.value = true;
  nextTick(() => initPickMap());
}

async function initPickMap() {
  if (_pickMap) return;
  try {
    const AMap = await loadAmap();
    _pickMap = new AMap.Map(pickMapEl.value, { zoom: 12, center: [116.4074, 39.9042], resizeEnable: true });
    _pickMap.on('click', (e) => {
      const lng = +e.lnglat.getLng().toFixed(6);
      const lat = +e.lnglat.getLat().toFixed(6);
      if (picking.value === 'from') {
        form.value.fromLng = lng;
        form.value.fromLat = lat;
      } else {
        form.value.toLng = lng;
        form.value.toLat = lat;
      }
      if (_pickMarker) _pickMap.remove(_pickMarker);
      _pickMarker = new AMap.Marker({ position: [lng, lat], map: _pickMap });
    });
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function saveCreate() {
  if (!form.value.orderId) { ElMessage.warning('请选择关联订单'); return; }
  if (!form.value.warehouseId) { ElMessage.warning('请选择发货仓库'); return; }
  if (!form.value.courierId) { ElMessage.warning('请选择配送员'); return; }

  // 检查库存
  const order = orders.value.find(o => o.id === form.value.orderId);
  if (order) {
    try {
      const res = await deliveryCheckStock(form.value.warehouseId, order.item_id, order.quantity);
      if (!res.enough) {
        ElMessage.warning('该仓库库存不足，无法创建配送单');
        return;
      }
    } catch (e) { /* ignore */ }
  }

  saving.value = true;
  try {
    const res = await deliveryCreate(form.value);
    ElMessage.success('配送单创建成功：' + res.deliveryNo);
    createVisible.value = false;
    loadList();
  } catch (e) {
    ElMessage.error(e.message);
  } finally { saving.value = false; }
}

async function confirmOutbound(row) {
  try {
    await ElMessageBox.confirm(`确认出库配送单「${row.delivery_no}」？将扣减库存并生成出库单。`, '确认出库', { type: 'warning' });
  } catch { return; }
  try {
    const res = await deliveryConfirmOutbound(row.delivery_no);
    ElMessage.success('出库成功，出库单号：' + res.outboundNo);
    loadList();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

async function cancelDelivery(row) {
  try {
    await ElMessageBox.confirm(`确认取消配送单「${row.delivery_no}」？`, '取消确认', { type: 'warning' });
  } catch { return; }
  try {
    await deliveryCancel(row.delivery_no);
    ElMessage.success('已取消');
    loadList();
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function viewRoute(row) {
  viewDetail(row);
}

async function viewDetail(row) {
  try {
    detail.value = await deliveryDetail(row.delivery_no);
    detailVisible.value = true;
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function openCreate() {
  form.value = {
    orderId: null, courierId: null, warehouseId: null,
    address: '', destination: '', customerPhone: '', deadline: '', remark: '',
    fromLng: null, fromLat: null, toLng: null, toLat: null
  };
  mapVisible.value = false;
  picking.value = '';
  _pickMap = null;
  _pickMarker = null;
  createVisible.value = true;
}

onMounted(() => { loadList(); loadRefs(); });
</script>

<style scoped>
.delivery-mgmt { padding: 20px; background: #f6f7f9; min-height: 100vh; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 16px; color: #1f2329; }
.header-actions { display: flex; gap: 8px; }
.filter-bar { margin-bottom: 16px; }
.map-pick-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.coord { font-size: 12px; color: #6b7280; font-family: monospace; }
.map-container { margin-top: 8px; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
.pick-map { position: relative; height: 280px; }
.pick-map-inner { width: 100%; height: 100%; }
.map-hint { position: absolute; bottom: 8px; left: 50%; transform: translateX(-50%); background: rgba(0,0,0,.6); color: #fff; font-size: 12px; padding: 4px 12px; border-radius: 4px; pointer-events: none; }
.detail-body { max-height: 60vh; overflow-y: auto; }
.route-info { margin-top: 16px; padding: 12px; background: #f6f7fb; border-radius: 8px; }
.route-info h4 { margin: 0 0 8px; font-size: 14px; color: #1f2329; }
.route-info p { margin: 0 0 8px; font-size: 13px; color: #6b7280; }
.route-info.empty p { color: #9ca3af; }
.step-list { margin: 0; padding-left: 20px; font-size: 13px; color: #1f2329; line-height: 1.8; }
.text-muted { color: #9ca3af; }
.form-tip { font-size: 12px; color: #f59e0b; margin-top: 4px; }
</style>
