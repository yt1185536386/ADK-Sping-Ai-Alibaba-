-- 职位表
CREATE TABLE IF NOT EXISTS `position` (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(100) NOT NULL COMMENT '职位名称',
    department  VARCHAR(100) NOT NULL COMMENT '所属部门',
    salary_range VARCHAR(50) NULL COMMENT '薪资范围',
    description TEXT NULL COMMENT '职位描述',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='职位表';

-- 员工表
CREATE TABLE IF NOT EXISTS employee (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50) NOT NULL COMMENT '姓名',
    gender      VARCHAR(10) NULL COMMENT '性别',
    phone       VARCHAR(20) NULL COMMENT '手机号',
    email       VARCHAR(100) NULL COMMENT '邮箱',
    position_id BIGINT NULL COMMENT '职位ID',
    hire_date   DATE NULL COMMENT '入职日期',
    status      VARCHAR(10) NOT NULL DEFAULT '在职' COMMENT '在职/离职',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_employee_position FOREIGN KEY (position_id) REFERENCES `position`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工表';

-- API Key / 模型渠道配置表（各来源的密钥与模型参数统一存这里，项目代码/配置文件中不写明文 Key）
CREATE TABLE IF NOT EXISTS api_key (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider        VARCHAR(50)  NOT NULL COMMENT '来源标识，如 bailian/deepseek/company',
    name            VARCHAR(100) NULL COMMENT '渠道名称，如：百炼模型',
    protocol        VARCHAR(30)  NOT NULL DEFAULT 'openai' COMMENT '接口协议：openai(兼容模式) / dashscope',
    auth_type       VARCHAR(30)  NOT NULL DEFAULT 'bearer' COMMENT '鉴权方式：bearer / api-key',
    api_key         VARCHAR(512) NOT NULL DEFAULT '' COMMENT '密钥，仅在库中维护',
    base_url        VARCHAR(255) NULL COMMENT '接口地址',
    models          JSON         NULL COMMENT '该渠道可用模型列表（含向量模型）',
    default_model   VARCHAR(100) NULL COMMENT '该渠道默认的推理模型',
    embedding_model VARCHAR(100) NULL COMMENT '该渠道的向量模型（不参与前端推理模型展示）',
    vendor          VARCHAR(50)  NULL COMMENT '模型厂商/分组，如 qwen / deepseek / openai',
    weight          INT          NOT NULL DEFAULT 0 COMMENT '权重/优先级（原始数据第8位）',
    is_default      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认渠道（原始数据第4位）',
    ext_flag        VARCHAR(20)  NULL COMMENT '原始数据第5位，含义待确认',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用（原始数据第11位）',
    source          VARCHAR(255) NULL COMMENT '渠道来源说明',
    remark          VARCHAR(255) NULL COMMENT '备注',
    created_at      DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_provider (provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型渠道/API Key 配置表';

-- 预置各来源记录（api_key 留空；密钥只在使用者本地库中维护，不进代码库）
INSERT INTO api_key(provider, name, protocol, auth_type, default_model, embedding_model, vendor, is_default, source, remark)
SELECT 'bailian', '百炼模型', 'openai', 'bearer', 'deepseek-v4-flash-0731', 'qwen3.7-text-embedding', 'qwen', 1,
       '种子渠道(环境变量 CHANNEL_BAILIAN_*)', '阿里云百炼（OpenAI 兼容模式）'
WHERE NOT EXISTS (SELECT 1 FROM api_key WHERE provider = 'bailian');

INSERT INTO api_key(provider, name, protocol, auth_type, default_model, vendor, source, remark)
SELECT 'deepseek', 'DeepSeek Token 计费模型', 'openai', 'bearer', 'deepseek-v4-pro', 'deepseek', '',
       'DeepSeek 官方（OpenAI 兼容模式）'
WHERE NOT EXISTS (SELECT 1 FROM api_key WHERE provider = 'deepseek');

INSERT INTO api_key(provider, name, protocol, auth_type, default_model, vendor, source, remark)
SELECT 'company', '公司模型', 'openai', 'bearer', 'k3-256k', 'openai',
       '手工补种(原种子因 ps1 编码问题未写入)', '公司内部推理服务（OpenAI 兼容模式）'
WHERE NOT EXISTS (SELECT 1 FROM api_key WHERE provider = 'company');

-- 模型调用审计与 token 计量：由 model/runtime/ModelUsageRecorder 异步批量写入，
-- 用于成本归集、渠道健康度与首 token 延迟观测（每次模型调用一行）
CREATE TABLE IF NOT EXISTS llm_call_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    module_key        VARCHAR(50)  NULL COMMENT '发起调用的业务模块（orchestrator 表示统一调度分类）',
    provider          VARCHAR(50)  NULL COMMENT '渠道标识',
    model             VARCHAR(100) NULL COMMENT '实际使用的模型',
    protocol          VARCHAR(30)  NULL COMMENT '协议：openai / dashscope',
    prompt_tokens     INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens      INT NOT NULL DEFAULT 0,
    latency_ms        INT NOT NULL DEFAULT 0 COMMENT '整次调用耗时',
    first_token_ms    INT NOT NULL DEFAULT 0 COMMENT '首 token 延迟（流式）',
    success           TINYINT NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
    error_msg         VARCHAR(512) NULL COMMENT '失败原因摘要',
    username          VARCHAR(50)  NULL COMMENT '发起调用的登录用户（管理端按用户维度统计）',
    thread_id         VARCHAR(64)  NULL COMMENT '会话标识（管理端按会话/用户聚合）',
    created_at        DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_llm_created (created_at),
    KEY idx_llm_provider_model (provider, model),
    KEY idx_llm_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型调用审计与 token 计量';

-- 会话消息表：Agent 的多轮上下文由 MysqlSaver 负责持久化（框架自建表），
-- 这张表用于「会话列表 / 历史记录查询 / 操作审计」，由应用显式写入。
CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id  VARCHAR(64)  NOT NULL COMMENT '会话标识',
    role       VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    provider   VARCHAR(50)  NULL COMMENT '本条使用的渠道',
    model      VARCHAR(100) NULL COMMENT '本条使用的模型',
    username   VARCHAR(50)  NULL COMMENT '消息归属的登录用户（会话隔离；NULL 为归属改造前的历史数据）',
    content    MEDIUMTEXT   NOT NULL COMMENT '消息内容',
    created_at DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_thread (thread_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息（历史查询/审计）';

-- ==================== 仓库管理（WMS）模块 ====================

-- 仓库
CREATE TABLE IF NOT EXISTS wms_warehouse (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(50)  NOT NULL COMMENT '仓库编码',
    name       VARCHAR(100) NOT NULL COMMENT '仓库名称',
    address    VARCHAR(255) NULL,
    status     VARCHAR(10)  NOT NULL DEFAULT '启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_warehouse_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库';

-- 商品 / SKU
CREATE TABLE IF NOT EXISTS wms_item (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku        VARCHAR(64)  NOT NULL COMMENT 'SKU 编码',
    name       VARCHAR(100) NOT NULL COMMENT '商品名称',
    category   VARCHAR(50)  NULL COMMENT '分类',
    unit       VARCHAR(20)  NULL COMMENT '单位',
    spec       VARCHAR(100) NULL COMMENT '规格',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_item_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品(SKU)';

-- 库存（按 仓库 × 商品）
CREATE TABLE IF NOT EXISTS wms_stock (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    item_id      BIGINT NOT NULL,
    quantity     INT NOT NULL DEFAULT 0 COMMENT '当前库存',
    safety_stock INT NOT NULL DEFAULT 0 COMMENT '安全库存（低于则预警）',
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wh_item (warehouse_id, item_id),
    CONSTRAINT fk_stock_wh FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse(id),
    CONSTRAINT fk_stock_item FOREIGN KEY (item_id) REFERENCES wms_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存';

-- 出入库单（order_type 区分入库/出库）
CREATE TABLE IF NOT EXISTS wms_order (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no     VARCHAR(40) NOT NULL COMMENT '单号',
    order_type   VARCHAR(10) NOT NULL COMMENT '入库/出库',
    warehouse_id BIGINT NOT NULL,
    item_id      BIGINT NOT NULL,
    quantity     INT NOT NULL,
    party        VARCHAR(100) NULL COMMENT '供应商/收货方',
    operator     VARCHAR(50)  NULL COMMENT '经办人',
    status       VARCHAR(20)  NOT NULL DEFAULT '已完成',
    remark       VARCHAR(255) NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_type_time (order_type, created_at),
    CONSTRAINT fk_order_wh FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse(id),
    CONSTRAINT fk_order_item FOREIGN KEY (item_id) REFERENCES wms_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出入库单';

-- 库存流水
CREATE TABLE IF NOT EXISTS wms_stock_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    item_id      BIGINT NOT NULL,
    change_type  VARCHAR(10) NOT NULL COMMENT '入库/出库',
    quantity     INT NOT NULL,
    before_qty   INT NOT NULL,
    after_qty    INT NOT NULL,
    ref_no       VARCHAR(40) NULL COMMENT '关联单号',
    operator     VARCHAR(50) NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_log_item_time (item_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水';

-- HITL 人工审批单：写操作触发中断后落到这里，等待前端确认
CREATE TABLE IF NOT EXISTS hitl_approval (
    id               VARCHAR(64)  NOT NULL COMMENT '审批单号',
    batch_id         VARCHAR(64)  NOT NULL COMMENT '批次号（同一次中断产生的多张单属于同批）',
    thread_id        VARCHAR(128) NOT NULL COMMENT '会话 ID',
    module_key       VARCHAR(32)  NULL COMMENT 'hr / wms',
    provider         VARCHAR(64)  NULL COMMENT '渠道',
    model            VARCHAR(128) NULL COMMENT '模型',
    tool_name        VARCHAR(64)  NOT NULL COMMENT '待审批的工具名',
    tool_call_id     VARCHAR(128) NOT NULL COMMENT '模型生成的 toolCall id（恢复时靠它匹配，不可省）',
    arguments        TEXT         NULL COMMENT '原始调用参数(JSON)',
    description      TEXT         NULL COMMENT '审批说明',
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/EDITED',
    feedback         TEXT         NULL COMMENT '用户反馈（拒绝原因等）',
    edited_arguments TEXT         NULL COMMENT '用户编辑后的参数(JSON)',
    result           MEDIUMTEXT   NULL COMMENT '恢复执行后的回复',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_hitl_thread (thread_id),
    KEY idx_hitl_batch (batch_id),
    KEY idx_hitl_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HITL 人工审批单';

-- ==================== 订单管理（销售订单/配送/采购入库/退货入库） ====================

-- 销售订单主表
CREATE TABLE IF NOT EXISTS biz_order (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no     VARCHAR(40) NOT NULL COMMENT '订单号',
    customer     VARCHAR(100) NOT NULL COMMENT '客户名称',
    item_id      BIGINT NOT NULL COMMENT '商品ID',
    quantity     INT NOT NULL COMMENT '数量',
    unit_price   DECIMAL(10,2) NULL COMMENT '单价',
    total_amount DECIMAL(12,2) NULL COMMENT '总金额',
    status       VARCHAR(20) NOT NULL DEFAULT '待处理' COMMENT '待处理/已发货/已退货/已取消',
    remark       VARCHAR(255) NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_status (status),
    KEY idx_order_customer (customer),
    CONSTRAINT fk_biz_order_item FOREIGN KEY (item_id) REFERENCES wms_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售订单';

-- 配送单
CREATE TABLE IF NOT EXISTS biz_delivery (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    delivery_no    VARCHAR(40) NOT NULL COMMENT '配送单号',
    order_id       BIGINT NOT NULL COMMENT '关联订单ID',
    employee_id    BIGINT NULL COMMENT '配送员工ID',
    employee_name  VARCHAR(50) NULL COMMENT '配送员工姓名（冗余，便于展示）',
    status         VARCHAR(20) NOT NULL DEFAULT '待配送' COMMENT '待配送/配送中/已送达/已取消',
    address        VARCHAR(255) NULL COMMENT '配送地址',
    destination    VARCHAR(255) NULL COMMENT '目的地',
    deadline       DATETIME NULL COMMENT '订单截止时间',
    customer_phone VARCHAR(50)  NULL COMMENT '客户联系方式',
    remark         VARCHAR(255) NULL,
    delivered_at   DATETIME NULL COMMENT '送达时间',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_delivery_no (delivery_no),
    KEY idx_delivery_order (order_id),
    KEY idx_delivery_status (status),
    CONSTRAINT fk_delivery_order FOREIGN KEY (order_id) REFERENCES biz_order(id),
    CONSTRAINT fk_delivery_emp FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送单';

-- 采购入库单（与订单关联的采购，区别于普通入库）
CREATE TABLE IF NOT EXISTS biz_purchase (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_no  VARCHAR(40) NOT NULL COMMENT '采购单号',
    order_id     BIGINT NULL COMMENT '关联订单ID（可为空，表示独立采购）',
    item_id      BIGINT NOT NULL COMMENT '商品ID',
    quantity     INT NOT NULL COMMENT '采购数量',
    warehouse_id BIGINT NULL COMMENT '入库仓库ID',
    employee_id  BIGINT NULL COMMENT '采购员工ID',
    employee_name VARCHAR(50) NULL COMMENT '采购员工姓名（冗余）',
    supplier     VARCHAR(100) NULL COMMENT '供应商',
    status       VARCHAR(20) NOT NULL DEFAULT '待采购' COMMENT '待采购/已入库/已取消',
    remark       VARCHAR(255) NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_purchase_no (purchase_no),
    KEY idx_purchase_order (order_id),
    KEY idx_purchase_status (status),
    CONSTRAINT fk_purchase_order FOREIGN KEY (order_id) REFERENCES biz_order(id),
    CONSTRAINT fk_purchase_item FOREIGN KEY (item_id) REFERENCES wms_item(id),
    CONSTRAINT fk_purchase_wh FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse(id),
    CONSTRAINT fk_purchase_emp FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购入库单';

-- 退货入库单
CREATE TABLE IF NOT EXISTS biz_return (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_no    VARCHAR(40) NOT NULL COMMENT '退货单号',
    order_id     BIGINT NOT NULL COMMENT '关联订单ID',
    item_id      BIGINT NOT NULL COMMENT '商品ID',
    quantity     INT NOT NULL COMMENT '退货数量',
    warehouse_id BIGINT NULL COMMENT '入库仓库ID',
    reason       VARCHAR(255) NULL COMMENT '退货原因',
    status       VARCHAR(20) NOT NULL DEFAULT '待处理' COMMENT '待处理/已入库/已取消',
    remark       VARCHAR(255) NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_return_no (return_no),
    KEY idx_return_order (order_id),
    KEY idx_return_status (status),
    CONSTRAINT fk_return_order FOREIGN KEY (order_id) REFERENCES biz_order(id),
    CONSTRAINT fk_return_item FOREIGN KEY (item_id) REFERENCES wms_item(id),
    CONSTRAINT fk_return_wh FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货入库单';

-- WMS 示例数据（仅首次插入）
INSERT INTO wms_warehouse(code, name, address)
SELECT 'WH01', '主仓', '公司总部 1 号库' WHERE NOT EXISTS (SELECT 1 FROM wms_warehouse);
INSERT INTO wms_warehouse(code, name, address)
SELECT 'WH02', '备件仓', '厂区东侧' WHERE NOT EXISTS (SELECT 1 FROM wms_warehouse WHERE code = 'WH02');

INSERT INTO wms_item(sku, name, category, unit, spec)
SELECT 'SKU-1001', 'A4 复印纸', '办公耗材', '包', '500张/包' WHERE NOT EXISTS (SELECT 1 FROM wms_item);
INSERT INTO wms_item(sku, name, category, unit, spec)
SELECT 'SKU-1002', '无线鼠标', '电子设备', '个', '2.4G' WHERE NOT EXISTS (SELECT 1 FROM wms_item WHERE sku = 'SKU-1002');

INSERT INTO wms_stock(warehouse_id, item_id, quantity, safety_stock)
SELECT w.id, i.id, 100, 20 FROM wms_warehouse w, wms_item i
WHERE w.code = 'WH01' AND i.sku = 'SKU-1001'
  AND NOT EXISTS (SELECT 1 FROM wms_stock s WHERE s.warehouse_id = w.id AND s.item_id = i.id);
INSERT INTO wms_stock(warehouse_id, item_id, quantity, safety_stock)
SELECT w.id, i.id, 15, 10 FROM wms_warehouse w, wms_item i
WHERE w.code = 'WH01' AND i.sku = 'SKU-1002'
  AND NOT EXISTS (SELECT 1 FROM wms_stock s WHERE s.warehouse_id = w.id AND s.item_id = i.id);

-- 预置几个示例职位（仅当表为空时插入）
INSERT INTO `position`(title, department, salary_range, description)
SELECT 'Java开发工程师', '技术部', '15k-25k', '负责后端服务开发与维护'
WHERE NOT EXISTS (SELECT 1 FROM `position`);

INSERT INTO `position`(title, department, salary_range, description)
SELECT '人事专员', '人力资源部', '8k-12k', '负责招聘与员工关系'
WHERE NOT EXISTS (SELECT 1 FROM `position` WHERE title = '人事专员');

INSERT INTO `position`(title, department, salary_range, description)
SELECT '产品经理', '产品部', '18k-30k', '负责产品规划与需求管理'
WHERE NOT EXISTS (SELECT 1 FROM `position` WHERE title = '产品经理');

-- ==================== 资产台账（一物一码，可调拨给员工使用） ====================
--
-- 设计要点：
-- 1) 与 wms_stock（按 仓库×商品 记数量）是**两套不同的账**：
--    wms_stock  = 未登记为资产的普通散装库存（按数量）
--    wms_asset  = 已登记为资产的实物，一件一行、一物一码，可单独调拨/退回/销毁
--    「登记资产」这一步是把数量从 wms_stock 划出来变成资产实例，两边合计守恒，不会重复计算。
-- 2) 「同一个物品只能调拨给一个用户」由表结构天然保证：一件资产只有一行、只有一个 holder 字段；
--    并发下再配合 UPDATE ... WHERE status='在库' 的乐观更新（影响行数=0 即已被别人调拨走）。
-- 3) 「只有销毁才从全部物品清单划掉」：status='已销毁' 的资产不再出现在全部清单里，但流水仍保留可追溯。

-- 资产实例（一物一码）
CREATE TABLE IF NOT EXISTS wms_asset (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_no           VARCHAR(64)  NOT NULL COMMENT '资产编号（一物一码，如 AST-20260930-0001）',
    item_id            BIGINT       NOT NULL COMMENT '商品(SKU)ID',
    warehouse_id       BIGINT       NOT NULL COMMENT '所属仓库ID',
    status             VARCHAR(16)  NOT NULL DEFAULT '在库' COMMENT '在库/已调拨/已出库/已销毁',
    holder_employee_id BIGINT       NULL COMMENT '当前使用人（仅「已调拨」时非空）',
    allocated_at       DATETIME     NULL COMMENT '最近一次调拨时间',
    scrapped_at        DATETIME     NULL COMMENT '销毁时间',
    scrap_reason       VARCHAR(255) NULL COMMENT '销毁原因',
    remark             VARCHAR(255) NULL COMMENT '备注',
    created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '登记为资产的时间',
    UNIQUE KEY uk_asset_no (asset_no),
    KEY idx_asset_item (item_id, status),
    KEY idx_asset_holder (holder_employee_id, status),
    CONSTRAINT fk_asset_item FOREIGN KEY (item_id) REFERENCES wms_item(id),
    CONSTRAINT fk_asset_wh FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse(id),
    CONSTRAINT fk_asset_holder FOREIGN KEY (holder_employee_id) REFERENCES employee(id),
    -- 状态与使用人必须一致：已调拨必有使用人，在库/已销毁必无使用人。
    -- 这条约束把「调拨出去却忘了记使用人」「退回了却还挂着人」这类脏数据堵在数据库层。
    CONSTRAINT ck_asset_holder CHECK (
        (status = '已调拨' AND holder_employee_id IS NOT NULL)
        OR (status <> '已调拨' AND holder_employee_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库资产（一物一码，单品级）';

-- 资产变动流水：入库 / 登记 / 调拨 / 退回 / 仓间调拨 / 出库 / 销毁，每条变动留痕，便于追责与对账
CREATE TABLE IF NOT EXISTS wms_asset_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_no      VARCHAR(64)  NOT NULL COMMENT '资产编号',
    action        VARCHAR(16)  NOT NULL COMMENT '入库/登记/调拨/退回/仓间调拨/出库/销毁',
    from_status   VARCHAR(16)  NULL COMMENT '变动前状态',
    to_status     VARCHAR(16)  NULL COMMENT '变动后状态',
    employee_id   BIGINT       NULL COMMENT '涉及的员工ID',
    employee_name VARCHAR(50)  NULL COMMENT '员工姓名快照（员工改名后仍能还原当时是谁在用）',
    warehouse_id  BIGINT       NULL COMMENT '涉及的仓库ID',
    operator      VARCHAR(50)  NULL COMMENT '经办人',
    ref_no        VARCHAR(40)  NULL COMMENT '关联单号',
    remark        VARCHAR(255) NULL COMMENT '备注/原因',
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_asset_log_no (asset_no, created_at),
    KEY idx_asset_log_emp (employee_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产变动流水（登记/调拨/退回/销毁）';

-- SKU 自增序列：SKU 改为系统自动生成后，靠这张表保证「同一秒内新建的多个商品」不撞号。
-- 形如 SKU-20260930150301-0001：时间戳到秒 + 同一时间戳内的自增序号。
-- 用 INSERT ... ON DUPLICATE KEY UPDATE next_val = next_val + 1 取号，在事务里靠行锁保证原子，
-- 不需要额外的锁表或全表扫描 MAX(sku)（后者并发下会发出重复 SKU）。
CREATE TABLE IF NOT EXISTS wms_sku_seq (
    ts_key     VARCHAR(20) NOT NULL COMMENT '时间戳分段 yyyyMMddHHmmss',
    next_val   INT         NOT NULL DEFAULT 0 COMMENT '该时间戳下已发出的最大序号',
    updated_at DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ts_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU 自增序列（系统自动生成 SKU 用）';

-- ==================== 外部工具配置（统一工具接入层） ====================
--
-- 把外部 HTTP 工具的 endpoint、鉴权信息等统一收口到这张表，
-- 新增外部工具时只改这张表 + 对应 Tool 类，不改 application.yml / 代码里的硬编码 URL。
-- api_key 加密存储（复用 ApiKeyEncryptionService 的 AES-256-GCM），
-- 与 api_key 表（模型渠道）分开：那张表管「调模型」，这张表管「调外部业务 API」。
CREATE TABLE IF NOT EXISTS external_tool (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_key    VARCHAR(50)  NOT NULL COMMENT '工具唯一标识，如 weather / amap',
    name        VARCHAR(100) NOT NULL COMMENT '工具显示名，如 天气查询 / 高德地图',
    base_url    VARCHAR(255) NOT NULL COMMENT '接口基础地址',
    auth_type   VARCHAR(30)  NOT NULL DEFAULT 'none' COMMENT '鉴权方式：none / query-key / bearer / header-key',
    auth_key_name VARCHAR(50) NULL COMMENT '鉴权参数名（query/header 名），如 key、Authorization',
    api_key     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '密钥（AES-256-GCM 加密存储），免鉴权工具留空',
    timeout_ms  INT          NOT NULL DEFAULT 10000 COMMENT '调用超时（毫秒）',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    remark      VARCHAR(255) NULL COMMENT '备注（申请地址、使用说明等）',
    created_at  DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_tool_key (tool_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部工具配置表（URL/Key 统一存储）';

-- 预置天气查询（wttr.in，免 key）
INSERT INTO external_tool(tool_key, name, base_url, auth_type, timeout_ms, remark)
SELECT 'weather', '天气查询', 'https://wttr.in', 'none', 10000,
       'wttr.in 免费天气服务，无需申请 key'
WHERE NOT EXISTS (SELECT 1 FROM external_tool WHERE tool_key = 'weather');

-- 预置高德地图（需自行申请 Web 服务 key，api_key 在库中加密维护）
INSERT INTO external_tool(tool_key, name, base_url, auth_type, auth_key_name, timeout_ms, remark)
SELECT 'amap', '高德地图', 'https://restapi.amap.com', 'query-key', 'key', 10000,
       '高德开放平台(lbs.amap.com)申请 Web 服务 key 后，用 ExternalToolService.saveApiKey("amap", key) 写入'
WHERE NOT EXISTS (SELECT 1 FROM external_tool WHERE tool_key = 'amap');

-- 预置高德前端 JS API（前端渲染交互式地图用）
-- api_key 存 JS API key（加密），auth_key_name 借用存安全密钥 securityJsCode（明文，本就需下发浏览器）
INSERT INTO external_tool(tool_key, name, base_url, auth_type, auth_key_name, timeout_ms, remark)
SELECT 'amap_js', '高德地图-前端JS', 'https://webapi.amap.com', 'js-key', '', 10000,
       '高德控制台创建「Web端(JS API)」应用，api_key=JS key(用 saveApiKey 写入)，auth_key_name=安全密钥 jscode'
WHERE NOT EXISTS (SELECT 1 FROM external_tool WHERE tool_key = 'amap_js');

-- ============================================================
-- 配送模块升级（V2）：用户体系 + 配送单扩展字段
-- ============================================================

-- 系统用户表：与 employee 关联，支持配送员登录
CREATE TABLE IF NOT EXISTS sys_user (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(50)  NOT NULL COMMENT '登录账号',
    password     VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
    display_name VARCHAR(50)  NOT NULL COMMENT '显示名',
    role         VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/APPROVER/USER/COURIER',
    employee_id  BIGINT NULL COMMENT '关联员工ID（配送员必须关联）',
    enabled      TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    KEY idx_employee (employee_id),
    CONSTRAINT fk_user_emp FOREIGN KEY (employee_id) REFERENCES employee(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- biz_delivery 扩展：经纬度 + 路线规划结果等列由 DeliveryV2ColumnMigrator 在启动后幂等补齐。
-- （MySQL 不支持 ADD COLUMN IF NOT EXISTS；spring.sql.init 不认识 DELIMITER，
--   存储过程写法会让应用启动失败，故列迁移放在 Java 侧做。）

-- 首次启动的管理员账号不在此预置——弱口令写进代码库等于公开后门。
-- 由 config/AdminBootstrap 在启动时创建：sys_user 为空时插入 admin，
-- 密码取环境变量 ADMIN_INITIAL_PASSWORD；未配置则随机生成并只在启动日志里打印一次。

-- 配送员账号：需关联在职员工，由管理端「用户管理」创建（角色 COURIER）。

-- ==================== 模型治理（熔断降级） ====================

-- 渠道熔断策略：每个渠道一条（未预置的渠道走代码内默认策略）
-- 状态机：CLOSED（正常）→ 统计窗口内连续失败达阈值 → OPEN（熔断，停止路由）
--         → 冷却期 open_seconds 到期 → HALF_OPEN（放行少量探测）→ 成功则回到 CLOSED，失败则重新 OPEN
CREATE TABLE IF NOT EXISTS model_circuit_breaker (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider           VARCHAR(50)  NOT NULL COMMENT '渠道标识（对应 api_key.provider）',
    enabled            TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用熔断 0关闭熔断（关闭后该渠道永不因失败被摘除）',
    failure_threshold  INT          NOT NULL DEFAULT 3 COMMENT '统计窗口内失败次数达到该值即熔断',
    window_seconds     INT          NOT NULL DEFAULT 60 COMMENT '失败统计窗口（秒）',
    open_seconds       INT          NOT NULL DEFAULT 120 COMMENT '熔断持续时间（秒），到期转半开',
    half_open_max      INT          NOT NULL DEFAULT 1 COMMENT '半开状态下允许的探测调用数',
    state              VARCHAR(16)  NOT NULL DEFAULT 'CLOSED' COMMENT '运行态：CLOSED/OPEN/HALF_OPEN',
    fail_count         INT          NOT NULL DEFAULT 0 COMMENT '当前统计窗口内失败次数',
    open_until         DATETIME(3)  NULL COMMENT '熔断到期时间（到期转半开）',
    last_error         VARCHAR(512) NULL COMMENT '最近一次失败原因摘要',
    last_transition_at DATETIME(3)  NULL COMMENT '最近一次状态变更时间',
    created_at         DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_cb_provider (provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型渠道熔断策略与运行态';

-- 降级链规则：主渠道熔断/失败时按 priority 顺序降级到备用渠道
-- 未配置规则的渠道沿用「按权重自动挑选同模型/默认模型」的内置候选链
CREATE TABLE IF NOT EXISTS model_fallback_rule (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider        VARCHAR(50)  NOT NULL COMMENT '主渠道（api_key.provider）',
    target_provider VARCHAR(50)  NOT NULL COMMENT '降级目标渠道',
    target_model    VARCHAR(100) NULL COMMENT '降级目标模型，为空则取目标渠道默认模型',
    priority        INT          NOT NULL DEFAULT 0 COMMENT '优先级，数值小的先降级',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    remark          VARCHAR(255) NULL COMMENT '备注',
    created_at      DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_fallback_pair (provider, target_provider),
    KEY idx_fallback_provider (provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型渠道降级链规则';

-- 熔断 / 降级事件流水：每次熔断触发、调用命中熔断被跳过、发生降级时各记一行
-- 用于管理端观察「何时熔断、本次调用重试了几次、降级是否成功、降级选中了哪个模型」
CREATE TABLE IF NOT EXISTS model_failover_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type    VARCHAR(20)  NOT NULL COMMENT 'breaker_trip 熔断触发 / failover 降级（含命中熔断跳过）',
    module_key    VARCHAR(50)  NULL COMMENT '发起调用的业务模块',
    username      VARCHAR(50)  NULL COMMENT '发起调用的登录用户',
    thread_id     VARCHAR(64)  NULL COMMENT '会话标识',
    from_provider VARCHAR(50)  NULL COMMENT '原渠道（被熔断/失败的渠道）',
    from_model    VARCHAR(100) NULL COMMENT '原模型',
    to_provider   VARCHAR(50)  NULL COMMENT '降级选中的渠道',
    to_model      VARCHAR(100) NULL COMMENT '降级选中的模型',
    attempts      INT          NOT NULL DEFAULT 1 COMMENT '本次调用实际尝试次数（不含被熔断跳过的）',
    skipped       VARCHAR(255) NULL COMMENT '本次调用因熔断被跳过的渠道',
    success       TINYINT      NULL COMMENT '降级是否成功：1成功 0失败（breaker_trip 事件为 NULL）',
    error_msg     VARCHAR(512) NULL COMMENT '触发原因摘要',
    created_at    DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_failover_created (created_at),
    KEY idx_failover_provider (from_provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='熔断与降级事件记录';
