// 业务数据页的表结构定义（管理端收敛了客户端原来的「管理模块」弹窗）
// 每个 tab：{ name, url, cols: [[字段, 列名, 是否换行, 是否用状态标签]], form: [字段定义] }
// form 字段：{ key, label, required, type, ref, options, onlyCreate, onlyEdit, value }

const routeSummaryOf = (row) => {
  if (!row.route_json) return '暂无路线规划数据';
  try {
    const r = JSON.parse(row.route_json);
    return `距离 ${r.distanceText || '-'}，预计时长 ${r.durationText || '-'}`;
  } catch {
    return '已有路线规划数据';
  }
};

export const REF_DEFS = {
  positions: { url: '/api/manage/positions', label: o => o.title },
  warehouses: { url: '/api/manage/warehouses', label: o => o.name },
  items: { url: '/api/manage/items', label: o => `${o.name}（${o.sku}）` },
  orders: { url: '/api/manage/orders?limit=500', label: o => `${o.order_no} / ${o.customer}` },
  couriers: { url: '/api/manage/couriers', label: o => `${o.display_name}（${o.username}）` },
  employees: { url: '/api/manage/employees?limit=500', label: o => `${o.name}（${o.phone || '无电话'}）` }
};

export const EMPLOYEE_TABS = [
  {
    name: '员工列表', url: '/api/manage/employees',
    cols: [
      ['id', 'ID'], ['name', '姓名'], ['gender', '性别'], ['position_title', '职位'], ['department', '部门'],
      ['phone', '手机号'], ['email', '邮箱', true], ['hire_date', '入职日期'], ['status', '状态', false, true], ['created_at', '创建时间']
    ],
    form: [
      { key: 'name', label: '姓名', required: true },
      { key: 'gender', label: '性别', type: 'select', options: ['男', '女'] },
      { key: 'positionId', label: '职位', type: 'select', ref: 'positions' },
      { key: 'phone', label: '手机号' },
      { key: 'email', label: '邮箱' },
      { key: 'hireDate', label: '入职日期', type: 'date' },
      { key: 'status', label: '状态', type: 'select', options: ['在职', '离职'] }
    ]
  },
  {
    name: '职位列表', url: '/api/manage/positions',
    cols: [
      ['id', 'ID'], ['title', '职位名称'], ['department', '部门'], ['salary_range', '薪资范围'],
      ['emp_count', '在职人数'], ['description', '描述', true], ['created_at', '创建时间']
    ],
    form: [
      { key: 'title', label: '职位名称', required: true },
      { key: 'department', label: '所属部门', required: true },
      { key: 'salaryRange', label: '薪资范围' },
      { key: 'description', label: '职位描述', type: 'textarea' }
    ]
  }
];

export const WMS_TABS = [
  {
    name: '仓库列表', url: '/api/manage/warehouses',
    cols: [
      ['id', 'ID'], ['code', '编码'], ['name', '名称'], ['address', '地址', true], ['status', '状态', false, true],
      ['sku_count', 'SKU 数'], ['total_qty', '库存总量'], ['created_at', '创建时间']
    ],
    form: [
      { key: 'code', label: '仓库编码', required: true },
      { key: 'name', label: '仓库名称', required: true },
      { key: 'address', label: '仓库地址' },
      { key: 'status', label: '状态', type: 'select', options: ['启用', '停用'] }
    ]
  },
  {
    name: '库存明细', url: '/api/manage/stock',
    cols: [
      ['warehouse_name', '仓库'], ['sku', 'SKU'], ['item_name', '商品'], ['category', '分类'],
      ['quantity', '库存'], ['safety_stock', '安全库存'], ['unit', '单位'], ['stock_status', '状态', false, true], ['updated_at', '更新时间']
    ],
    form: [
      { key: 'warehouseId', label: '仓库', type: 'select', ref: 'warehouses', required: true },
      { key: 'itemId', label: '商品', type: 'select', ref: 'items', required: true },
      { key: 'quantity', label: '库存数量', type: 'number' },
      { key: 'safetyStock', label: '安全库存', type: 'number' }
    ]
  }
];

export const ORDER_TABS = [
  {
    name: '订单列表', url: '/api/manage/orders',
    cols: [
      ['order_no', '订单号'], ['customer', '客户'], ['item_name', '商品'], ['quantity', '数量'],
      ['unit_price', '单价'], ['total_amount', '总金额'], ['status', '订单状态', false, true],
      ['delivery_no', '配送单号'], ['delivery_status', '配送状态', false, true], ['courier', '配送员'],
      ['delivery_address', '配送地址', true], ['created_at', '下单时间']
    ],
    form: [
      { key: 'customer', label: '客户', required: true },
      { key: 'itemId', label: '商品', type: 'select', ref: 'items', required: true },
      { key: 'quantity', label: '数量', type: 'number' },
      { key: 'unitPrice', label: '单价', type: 'number' },
      { key: 'status', label: '订单状态', type: 'select', options: ['待处理', '已发货', '已退货', '已取消'] },
      { key: 'remark', label: '备注', type: 'textarea' }
    ]
  },
  {
    name: '配送单', url: '/api/manage/deliveries', extra: 'cancelDelivery',
    cols: [
      ['delivery_no', '配送单号'], ['order_no', '订单号'], ['customer', '客户'], ['item_name', '商品'],
      ['quantity', '数量'], ['warehouse_name', '发货仓库'], ['courier_name', '配送员'],
      ['customer_phone', '客户联系方式'],
      ['outbound_no', '出库单号'], ['destination', '目的地'], ['deadline', '订单截止时间'],
      ['status', '状态', false, true],
      ['address', '地址', true], ['delivered_at', '送达时间'], ['created_at', '创建时间']
    ],
    form: [
      { key: 'orderId', label: '关联订单', type: 'select', ref: 'orders', required: true, onlyCreate: true },
      { key: 'warehouseId', label: '发货仓库', type: 'select', ref: 'warehouses' },
      // 配送员选员工（HR 主数据，一定存在）；该员工若绑定了 COURIER 账号，后端自动关联 courier_id
      { key: 'employeeId', label: '配送员', type: 'select', ref: 'employees' },
      { key: 'customerPhone', label: '客户联系方式' },
      { key: 'outboundNo', label: '出库单号', type: 'readonly', onlyEdit: true },
      { key: 'routeSummary', label: '路线规划', type: 'readonly', onlyEdit: true, value: routeSummaryOf },
      { key: 'destination', label: '目的地' },
      { key: 'deadline', label: '截止时间', type: 'datetime' },
      { key: 'address', label: '配送地址' },
      { key: 'status', label: '状态', type: 'select', options: ['待配送', '配送中', '已送达', '已取消'] }
    ]
  }
];

export const PURCHASE_TABS = [
  {
    name: '采购入库', url: '/api/manage/purchases',
    cols: [
      ['purchase_no', '采购单号'], ['item_name', '商品'], ['sku', 'SKU'], ['quantity', '数量'],
      ['warehouse_name', '入库仓库'], ['supplier', '供应商'], ['employee_name', '采购员'],
      ['order_no', '关联订单'], ['status', '状态', false, true], ['created_at', '创建时间']
    ],
    form: [
      { key: 'itemId', label: '商品', type: 'select', ref: 'items', required: true },
      { key: 'warehouseId', label: '入库仓库', type: 'select', ref: 'warehouses' },
      { key: 'quantity', label: '采购数量', type: 'number' },
      { key: 'supplier', label: '供应商' },
      { key: 'employeeName', label: '采购员' },
      { key: 'status', label: '状态', type: 'select', options: ['待采购', '已入库', '已取消'] }
    ]
  },
  {
    name: '退货入库', url: '/api/manage/returns',
    cols: [
      ['return_no', '退货单号'], ['order_no', '订单号'], ['customer', '客户'], ['item_name', '商品'],
      ['quantity', '数量'], ['warehouse_name', '入库仓库'], ['reason', '退货原因', true],
      ['status', '状态', false, true], ['created_at', '创建时间']
    ],
    form: [
      { key: 'orderId', label: '关联订单', type: 'select', ref: 'orders', required: true, onlyCreate: true },
      { key: 'warehouseId', label: '入库仓库', type: 'select', ref: 'warehouses' },
      { key: 'quantity', label: '退货数量', type: 'number' },
      { key: 'reason', label: '退货原因' },
      { key: 'status', label: '状态', type: 'select', options: ['待处理', '已入库', '已取消'] }
    ]
  }
];
