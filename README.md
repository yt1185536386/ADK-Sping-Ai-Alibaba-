# HR / WMS / 订单 多模块智能体（ADK）

基于 **Spring AI Alibaba（Agent Framework）+ 多渠道模型 + 本地 MySQL** 的 Java Agent 系统。
按业务域拆分模块 Agent（人力资源 / 仓库管理 / 订单管理 / 通用），由统一调度入口先按意图分类到模块，再走各模块自己的执行链路。

核心特性：

- **两个进程**：主应用 `hr-agent`（:20810，Web + Agent 引擎 + 前端托管）与独立 MCP Server（:20811，全部外部第三方工具）。
- **渠道参数入库**：密钥 / 地址 / 模型 / 协议全部存于 MySQL `api_key` 表，代码库中不含任何明文 Key；前端可按「来源 → 模型」选择推理模型。
- **所有写操作经人工确认（HITL）**：前端弹确认框，可批准 / 拒绝 / 改参数后再执行；断点续跑靠 checkpoint，重启后可继续审批。
- **登录认证**：Spring Security + HTTP Basic（无状态），用户主存 `sys_user` 表、内存账号兜底（见「登录与认证」）。

## 系统架构

```
                          ┌─────────────────────────────────────────────┐
浏览器 ──► :20810 主应用 hr-agent（Spring Boot，托管前端静态产物）         │
            │                                                             │
            ├─ controller/   Web 端点（chat / approvals / manage /        │
            │                  delivery / form / amap / auth）            │
            ├─ config/AgentFactory                                        │
            │     classify()  ──一次模型调用分类──► ModuleAgent           │
            │     chatModule() ──ReactAgent + HITL + checkpoint           │
            ├─ agents/        ModuleAgent 契约 + 4 个模块实现             │
            ├─ tools/         @Tool（本地业务工具：Hr / Wms / Asset / Order）│
            ├─ mcp/           McpToolGateway：本地 @Tool 与远程 MCP 工具   │
            │                  统一成 ToolCallback                        │
            ├─ hooks/         MessageGuardHook（BEFORE_MODEL 消息守卫）   │
            ├─ service/       业务服务（Asset / BatchInbound / Delivery…）│
            ├─ spi/           审批扩展点（候选清单 / 明细预览）            │
            ├─ store/         会话与 HITL 审批单持久化                     │
            ├─ event/         离职事件 → 资产自动回收（异步）              │
            └─ cli/           运维命令行（外部工具密钥加密迁移）           │
                  │                          │                  │
                  ▼                          ▼                  ▼
            MySQL 9.x              MCP Server :20811      多渠道 LLM
       （业务数据 / checkpoint /    （com.hr:adk-mcp-server，  （bailian / deepseek /
        审批单 / 渠道密钥 / 会话）    外部工具：天气/公交/定位/     company，OpenAI 兼容
                                    地点搜索/路线规划）          或 DashScope 协议）
```

### 请求链路（一次对话）

1. `POST /api/chat {message, threadId, provider, model, module?}`。
2. `module` 为空时先走调度入口：意图明确（唯一模块关键词命中）时**规则直达、零模型调用**；
   否则 `AgentFactory#classify` 用一次模型调用把请求分类到某个模块；
   显式指定模块时跳过分类 —— 两条入口之后走**完全相同**的执行链路。
3. `AgentFactory#chatModule` 按「模块::渠道::模型」懒加载并缓存 `ReactAgent`，
   挂上 `HumanInTheLoopHook`（写操作拦截）与 `MessageGuardHook`（消息守卫），
   checkpoint 线程名按模块加命名空间（`hr@<threadId>` / `wms@<threadId>` …）。
4. 模型提出写工具调用 → HITL 中断 → 返回 `status=pending_approval` + `batchId` →
   前端确认框 → `POST /api/approvals` → 带 `InterruptionMetadata` 从中断点继续执行。

### 为什么不用框架的 `LlmRoutingAgent`（重要）

最初用 `LlmRoutingAgent`（把各模块 `ReactAgent` 作为节点挂进一张嵌套图）做统一调度，实测发现一个会**丢数据**的问题：
子图里的写操作中断（HITL）不会被父图正确转换成「待人工审批」的结果。在自动调度路径下发
「把 50 包 A4 复印纸入库到 WH01 仓库」，接口返回的竟是模型「我这就去入库」的**前置话术**、
`status=done` —— 中断被静默吞掉：写操作没执行、前端也没弹确认框。

现在改成「先分类、再委派」：`classify()` 用一次模型调用选出模块，然后调用与该模块**同一个** `chatModule()`。
两条入口（自动调度 / 手动指定模块）共用完全相同的 HITL + checkpoint + 恢复执行流程，行为可预期。
分类失败会自动回退到第一个模块，不会让请求失败。

## 快速开始

```powershell
mvn -B -DskipTests package                            # 首次或代码变更后：打出后端 jar（含前端页面）
cd mcp-server && mvn -B -DskipTests package && cd ..  # 首次或 mcp-server 变更后：打出 MCP server jar
.\start.ps1                  # 启动三个进程（顺序：mcp-server → 后端 → 前端），带健康检查门
.\stop.ps1                   # 停止（结束占用 20811 / 20810 / 5173 的进程）
```

- **PowerShell 不可用/卡死时用纯 cmd 版本**：`start.cmd` / `stop.cmd` 功能完全等同，
  不依赖 powershell.exe（2026-10-26 曾出现 powershell.exe 进程创建被系统拖慢导致 start.ps1 假死）。
  平时两者皆可；`.ps1` 与 `.cmd` 都只接受全停或全启。
- **启动顺序是承重墙**：MCP Server 必须先于后端启动。模块 Agent 按 channel+model 缓存且
  首次聊天时才懒构建 —— MCP 后启动会让 common 模块缓存到 0 个工具，必须重启后端才能恢复。
  `start.ps1` 已内置 20811 健康门；`McpStartupCheck` 与 `AgentFactory` 在未接入时打 ERROR 日志。
- 启动脚本用 `netstat` 做端口检查（`Get-NetTCPConnection` 在本机实测单次 8 秒以上，已弃用）。
- ⚠️ **改代码后必须重新 `mvn package`**；也**不要在应用运行中打包** ——
  Windows 会锁住 jar 导致 repackage 失败（先 `.\stop.ps1`）。
- 后端开发模式经 `mvn spring-boot:run` 启动（约 20-40 秒，会先编译）；生产可用
  `java -jar target/hr-agent-1.0.0.jar`。
- 前端页面：开发 http://localhost:5173/ ，生产 http://localhost:20810/ （首次访问进入登录页）。
- 首次启动自动执行 `schema.sql` 建表预置数据；框架 checkpoint 的两张表（`graph_checkpoint`、`graph_thread`）由框架自建；
  `biz_delivery` 的 V2 扩展列由 `DeliveryV2ColumnMigrator` 启动后幂等补齐。

### 本机启动注意事项

- `start.ps1` 在 `Start-Process` 前会先把进程环境变量里**大小写重名的键**（`Path`/`PATH`、`https_proxy`/`HTTPS_PROXY` …）逐个删掉重设，否则 PowerShell 5.1 会抛「已添加项。字典中的关键字…」。
- **`start.ps1` / `stop.ps1` 必须保持纯 ASCII。** PS 5.1 按 ANSI(GBK) 读取无 BOM 的 UTF-8 脚本，
  中文注释里的 `…`（U+2026）等字符会和后面的引号拼成非法字节，直接报「字符串缺少终止符」导致脚本解析失败。
  两个脚本现在都只用英文注释，不再依赖 BOM。
- 本机 `javapath\java.exe` 是 Oracle 的跳板 shim，会再拉起真正的 JDK 进程 ——
  所以每启动一次应用会看到**两个** java 进程（shim + 真 JVM），属正常现象。

## 技术栈

| 组件 | 版本 |
|---|---|
| JDK | 17+（本机 21） |
| Spring Boot | 3.5.6（web / jdbc / security） |
| Spring AI Alibaba | 1.1.2.0（agent-framework + dashscope starter） |
| Spring AI OpenAI | 1.1.2（OpenAI 兼容协议渠道） |
| 前端 | Vue 3.5 + Element Plus 2.8 + Vite 5（`frontend/` 工程，构建进 `static/`） |
| 数据库 | 本地 MySQL 9.x，库 `hr_spring_ai_adk_agent` |

### 已配置渠道

| provider | 名称 | 地址 | 推理模型 | 向量模型 | 权重 |
|---|---|---|---|---|---|
| `bailian` ★默认 | 百炼模型 | `.../compatible-mode/v1` | deepseek-v4-flash-0731 | **qwen3.7-text-embedding** | 2 |
| `deepseek` | DeepSeek Token 计费模型 | `https://api.deepseek.com` | deepseek-flash、deepseek-v4-pro | — | 10 |
| `company` | 公司模型 | `http://172.18.200.114:3000/v1` | k3-256k、kimi-2.6、MiniMax-M2.5 | — | 1 |

三个渠道均已实测连通（含公司内网模型 k3-256k）。

### 渠道表 `api_key`

| 字段 | 说明 |
|---|---|
| `provider` | 来源标识（唯一），前端「来源」下拉就是它 |
| `name` | 渠道名称 |
| `protocol` | 接口协议：`openai`（OpenAI 兼容模式） / `dashscope`（DashScope 原生） |
| `auth_type` | 鉴权方式（`bearer` 等） |
| `api_key` | 密钥（仅存库中，不入代码库） |
| `base_url` | 接口地址；以 `/v1` 结尾时自动用 `/chat/completions`、`/embeddings` 拼接，否则补 `/v1/...` |
| `models` | 该渠道全部模型（JSON 数组，含向量模型） |
| `default_model` | 该渠道默认推理模型 |
| `embedding_model` | 该渠道向量模型（**不参与前端推理模型展示**） |
| `vendor` | 厂商/分组：qwen / deepseek / openai |
| `weight` / `is_default` / `ext_flag` / `enabled` | 权重、是否默认渠道、原始数据第5位（含义待确认）、启用开关 |
| `source` / `remark` / `created_at` / `updated_at` | 来源说明、备注、时间 |

> 模型名含 `embedding` 的会被自动识别为向量模型，从推理模型列表中过滤掉。

### 向量（Embedding）模型

- 当前默认：**渠道 `bailian` 的 `qwen3.7-text-embedding`**（实测 1024 维）。
- 额度用完后切换：改 `application.yml` 的 `hr.agent.embedding-provider`，或改对应渠道的 `embedding_model` 字段。
- 向量模型只出现在 `/api/embedding` 接口，**不作为推理模型展示**。
- 连通性自测：`POST /api/embedding/test {"text":"..."}`。

## 模块与 Agent

| 分层 | Java | 说明 |
|---|---|---|
| 调度入口 | `config/AgentFactory#classify` | 一次分类调用选出模块，之后与「显式指定模块」走完全相同的执行链路 |
| 模块契约 | `agents/ModuleAgent` | `key / displayName / agentName / description / systemPrompt / toolBean / toolBindings / approvalTools` |
| 通用模块 | `agents/CommonAgent` | 兜底问答（闲聊、跨模块综合问题），工具全部来自远程 MCP |
| 人力模块 | `agents/HrAgent` + `tools/HrTools` | 职位与员工管理 |
| 仓库模块 | `agents/WmsAgent` + `tools/WmsTools` + `tools/AssetTools` | 仓/品/存 + 出入库 + 一物一码资产台账（两个 toolBean，经 `toolBindings()` 绑定为 `local:wms` / `local:asset`） |
| 订单模块 | `agents/OrderAgent` + `tools/OrderTools` | 销售订单 + 配送 / 采购入库 / 退货入库 |
| 人工审批 | `config/AgentFactory#humanApprovalHook` | 把 `ModuleAgent#approvalTools()` 挂到框架 `HumanInTheLoopHook` |
| 消息守卫 | `hooks/MessageGuardHook` | 见下文 HITL 一节 |

`ModuleAgent#toolBindings()` 是工具装配的正典入口：默认把 `toolBeans()` 包成本地绑定（`LocalToolBinding`），
模块也可以返回 `McpToolBinding` 把工具来源指向远程 MCP Server（CommonAgent 就是这么做的），
**调度代码不关心工具在本地还是远程**。

新增一个模块 = 实现 `ModuleAgent`（并用 `approvalTools()` 声明哪些工具需要人工审批）+ 写一个 `@Tool` 类，
Spring 自动注册，**无需改调度代码**。

### 各模块能力（工具清单）

**人力资源模块（hr_agent）** —— 审批工具：`add_position` / `delete_position` / `add_employee` / `transfer_position` / `update_employee_contact` / `dismiss_employee`

- 职位：`add_position` / `list_positions` / `delete_position`（有在职员工时禁止删除）
- 员工：`add_employee`（入职）/ `list_employees` / `search_employee`
- 变更：`transfer_position`（调岗）/ `update_employee_contact` / `dismiss_employee`（离职，保留记录）
- **个人物品栏**：`list_employee_belongings`（某人手上领用中的公物）
- `dismiss_employee` 提交离职事务后发布 `EmployeeResignedEvent`，由监听器**异步**把该员工名下物品全部退回仓库（见「资产台账·离职联动」）

**仓库管理模块（wms_agent）** —— 审批工具：`add_warehouse` / `add_item` / `set_safety_stock` / `batch_inbound` / `inbound` / `outbound` / `register_assets` / `allocate_asset` / `return_asset` / `transfer_asset` / `scrap_asset`

- 基础资料：`add_warehouse` / `list_warehouses` / `add_item` / `list_items`
  （`add_item` **不传 SKU**，编码由系统自动生成；名称+规格相同即复用原 SKU，不重复建档）
- 库存：`query_stock` / `list_stock_alerts` / `set_safety_stock`
- 出入库：`batch_inbound`（**批量入库，首选**）/ `inbound`（单品）/ `outbound` / `list_orders`
- 资产台账（`AssetTools`）：`register_assets`（登记）/ `allocate_asset`（调拨给员工）/ `transfer_asset`（仓间调拨）
  / `return_asset`（退回）/ `scrap_asset`（销毁）/ `list_assets`（清单）/ `stocktake`（盘点）/ `asset_history`（变动流水）
- 智能分配：入/出库未指定仓库时，入库选「该 SKU 已有库存且最少」的仓库，出库选「库存足够且最多」的仓库；库存不足时列出各仓库可出数量
- 规范：出库前先校验库存；低于安全库存主动提示补货

**订单管理模块（order_agent）** —— 审批工具：`create_order` / `create_delivery` / `create_purchase` / `create_return`

- 订单：`create_order`（创建，不动库存）/ `list_orders`（含配送状态，可按状态/客户过滤）
- 配送：`create_delivery`（指定员工配送，自动拣货出库、订单变已发货）/ `list_deliveries`
- 采购入库：`create_purchase`（可关联订单为其补货，也可独立采购）
- 退货入库：`create_return`（仅「已发货」订单可退；全退订单变已退货，部分退保持已发货）
- 库存不足时如实说明各仓库可用数量并建议先采购，不执行部分出库

**通用模块（common_agent）**：兜底问答，无写操作、无审批。工具全部来自 MCP Server
（`McpToolBinding.all(gateway, "adk-tools")`）：天气 / 公交 / IP 定位 / 地点搜索 / 路线规划。

**写工具返回值约定**：所有会改动数据的工具都要回显「数据库中实际写入的最终值」并附 `GROUND_TRUTH_HINT`
（见 `HrTools` / `WmsTools` 类注释）。原因见下文「模型不知道人工改过参数」。

## 人工确认（HITL）

- **哪些操作要确认**：由各模块的 `ModuleAgent#approvalTools()` 声明，只放会改动数据的写操作。查询一律不拦。
- **流程**：模型提出写工具调用 → 框架 `HumanInTheLoopHook`（`AFTER_MODEL`）中断 → 接口返回 `status=pending_approval`
  + `batchId` + 待确认清单 → 前端渲染确认框（参数可编辑、可填备注/拒绝理由）→
  `POST /api/approvals` 提交决策 → 带 `InterruptionMetadata` 作为 human feedback **从中断点继续跑**。
- **三种决策**：`APPROVE`（批准）/ `REJECT`（拒绝，理由会作为 `ToolResponseMessage` 注入给模型，让它知道操作没执行）/
  `EDIT`（改参数后批准，框架会把改后的参数替换进 tool call 真正执行）。
- **权限**：`POST /api/approvals` 需要 **ADMIN 或 APPROVER** 角色（`config/SecurityConfig.java`）。
- **断点续跑靠 checkpoint**：`MysqlSaver` 持久化，审批单也落库（`hitl_approval`），所以**重启后依然能完成审批**。

### 两个必须知道的坑（都已在代码里处理）

1. **`RunnableConfig.Builder.resume()` 会冲掉 human feedback。**
   框架源码里 `resume()` = `addMetadata(HUMAN_FEEDBACK_METADATA_KEY, "placeholder")`。
   如果写成 `.addHumanFeedback(feedback).resume()`，真实 feedback 会被字符串 `"placeholder"` 覆盖，
   恢复时报 `Human feedback metadata must be of type InterruptionMetadata`。**不要调用 `.resume()`**。

2. **模型不知道「人工改过参数」，会把数据改回去。**
   框架只在 `REJECTED` 时注入一条 `ToolResponseMessage`；`EDITED` 只是**悄悄**把参数替换进 tool call 执行，
   不告诉模型。实测（deepseek-v4-pro）：人工把手机号改成 `13511112222` 并批准后，模型看到落库值和用户原话对不上，
   自己判断成「我填错了」，**紧接着又调 `update_employee_contact` 把手机号改回 `13500135000`**。
   现在用三重措施堵住：
   - 写工具返回值**回显数据库实际写入的最终值**（含系统生成的主键/单号），并在末尾附一句「以此为准」；
   - 提示词明确「工具返回即权威，不要调用写工具把值改回去」；
   - `AgentFactory#resumeWithFeedback` 把人工改动写进 `RunnableConfig` metadata，
     由 `MessageGuardHook`（`BEFORE_MODEL`）作为一条 `UserMessage` 注入给模型 —— **这是真正生效的一环**。

### 消息守卫 `MessageGuardHook`

OpenAI 兼容接口要求 `assistant(tool_calls)` 后面**直接**跟着覆盖每个 `tool_call_id` 的 tool 消息。
用户在确认框弹出后不点确认、直接输入新问题的话，新消息会接在那条「悬空的 tool 调用」后面，
下一次模型调用就会被接口 400 拒绝：
`An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'`。
`MessageGuardHook` 在每次模型调用前检查并补一条「该操作已作废」的 tool 响应闭合历史（一次遍历，
顺带完成上面第 2 条的人工改动注入，最后统一 `UpdatePolicy.REPLACE` 写回）。
它还承担**上下文滑动窗口**：发给模型的历史最多保留 `hr.agent.context-max-messages` 条（默认 40，
`application.yml` 可调，`<=0` 不截断），切点只选 `UserMessage` 边界，保证 tool 调用配对完整——
长会话的 prompt token 不再随轮次无界增长（checkpoint 里的全量历史不受影响，仅裁剪发给模型的部分）。

同时接口侧也有配合：用户发新消息时 `hitlService.expirePending(threadId)` 会把上一轮未处理的审批单作废，
前端则把那张确认卡片置灰（提示「已作废：你开始了新的对话」）。

### 确认框的操作方式

- 卡片里可以直接改参数（JSON 文本框）→ 点「批准执行」即按**改后**的参数执行（前端自动判定为 EDIT）。
  「备注 / 拒绝理由」填了内容时，会作为拒绝原因或修改说明一并回给模型。
- **需要挑物品的操作会列出每一件让你勾选**（调拨 / 退回 / 销毁资产）。
  只给一个编号文本框是不够用的：用户既认不出 `AST-20260930-0002` 到底是哪件东西，
  也不知道除了 AI 选的那几件还有哪些可选，想换一件就得手写 JSON。
  所以这类工具的审批项会附带一份候选清单，逐条显示
  `编号 名称（规格）[SKU] | 仓库 | 登记日期 或 使用人`，默认勾选 AI 选中的那几件，
  底部实时显示「已选 N 件」。勾完直接点「批准执行」。
  原始 JSON 默认收起（点「展开原始参数」可改其它字段）。
  - 候选范围按工具区分：**调拨**只列该商品「在库」的、**退回**只列「在员工手上」的、**销毁**列「未销毁」的；
    并且会收敛到同一个商品 —— 否则"调拨无线鼠标"会把仓库里所有 SKU 一并列出来，反而更难选。
- **批量写入的操作会先给一张明细预览表**（如一次入库 100 个商品）。
  原因很直接：这样的清单如果按商品逐个弹确认框，用户要点 100 次；
  但如果只丢一个参数 JSON 过来，用户又是在盲签。所以批量工具一次调用只产生**一个**确认框，
  框里渲染一张只读表格（逐行显示商品、规格、类别、单位、数量、库存变化、是否新建商品、SKU），
  表格上方是操作说明、下方是汇总（`共 N 个商品（新建 a / 已有 b），合计入库 M 件 → 仓库：xxx`）。
  核对完点一次「批准执行」即整批写入。明细超过一屏时表格内部滚动，按钮始终可见。
  原始 JSON 默认收起（点「展开原始参数」可改其它字段）。
- **也可以直接打字「确认」**（支持 确认/确定/同意/批准/可以/行/好/好的/是/yes/ok），等价于点「批准执行」。
  这条是为了防止一个死循环：用户以为要说一句"确认"，但新消息会把这批审批单作废，
  模型于是重新发起同样的写操作又弹一个框 —— 看起来就像"卡住了"。
- 想放弃这批操作，直接发起新对话即可；卡片会置灰成「已作废」，数据不会被写入。

### 审批扩展点（`spi` 包）

两个扩展点都由后端提供全部数据、前端只负责渲染，**新增同类工具不用动前端**：

| 接口 | 场景 | 实现 |
|---|---|---|
| `spi/ApprovalOptionsProvider` | 从一批已有对象里挑几个（可勾选清单） | `AssetService`（调拨/退回/销毁资产） |
| `spi/ApprovalPreviewProvider` | 一屏看清这次要批量写入什么（只读明细表） | `BatchInboundService`（批量入库） |

- 勾选结果写回参数里的 `choiceField` 字段，因此仍沿用「参数被改过 → EDIT」这条老路，
  后端执行链路一行都没改（`ChatController.attachChoices()`）。
- 预览表走 `ChatController.attachPreview` → 前端 `buildPreview`，列与行全部由后端给。
- **实现方在预览/候选阶段只能读库、不能写**：用户可能拒绝或改参数，预览时就占号/发单号会留下脏数据
  （例如白白烧掉一批 SKU 序号）。

## 登录与认证

- **认证方式**：Spring Security + HTTP Basic（无状态，不建 Session；生产环境建议换 JWT/OAuth2）。
- **用户来源**（`config/UserConfig.java`，容器里刻意只有**一个** `UserDetailsService` Bean）：
  1. **主：数据库 `sys_user` 表**（`service/DbUserDetailsService`），支持 ADMIN / APPROVER / USER / COURIER 四种角色。
     **COURIER（配送员）必须关联 `employee_id` 且员工在职**，否则拒绝登录。
  2. **初始管理员**：首次启动时 `config/AdminBootstrap` 检测 `sys_user` 为空则创建管理员
     （用户名取 `ADMIN_INITIAL_USERNAME`，默认 `admin`）：
     密码取环境变量 `ADMIN_INITIAL_PASSWORD`；未配置则**随机生成 16 位密码，只在启动日志打印一次**，
     请立即登录后在「用户管理」中修改。库里已有用户时该机制完全不介入。
  3. **内存应急账号**：仅数据库**不可用**（表未建/连接失败）时启用，账号 `admin`、
     密码同样取 `ADMIN_INITIAL_PASSWORD`；**未配置则应急通道关闭**。
     代码中不再存在任何硬编码的默认口令。
  - ⚠️ `DbUserDetailsService` 刻意**不实现 `UserDetailsService` 接口**：Spring 的
    `InitializeUserDetailsManagerConfigurer` 发现多个该类型 Bean 时会全部弃用，导致所有 Basic 认证都 401
    （`@Primary` 无效）。它只做普通 `@Service`，由 `UserConfig` 里唯一的 Bean 调用。
  - ⚠️ **旧部署注意**：历史版本 `schema.sql` 预置过弱口令账号。若你的库是旧版本初始化的，
    请立即在「用户管理」中修改或删除这些账号。
- **权限规则**（`config/SecurityConfig.java`）：
  - 匿名可访问：`/api/health` `/api/channels`（已脱敏，不下发 baseUrl）`/api/modules` `/api/embedding`
    `/api/manage/amapJsKey`（前端初始化与地图加载需要）。
  - 需认证：`POST /api/chat`（含 `/chat/stream`）`/api/threads/**` `GET /api/approvals` `/api/embedding/test` 等。
  - `POST /api/approvals`（审批决策）：需 **ADMIN 或 APPROVER** 角色（Security 层强制）。
- **会话归属隔离**：`chat_message.username` 记录会话归属，普通用户只能查看/续写/删除/停止**自己的**会话，
  ADMIN 可访问全部；归属改造前的历史会话（username 为 NULL）对所有登录用户开放，保持兼容。
  老库启动时由 `AdminSchemaMigrator` 幂等补列。
- **跨人审批**：审批决策（`POST /api/approvals`）刻意**不做**会话归属校验——专职审批员（APPROVER/ADMIN）
  可以审批他人发起的写操作。审批员经 `GET /api/approvals/pending-all`（仅 ADMIN/APPROVER）
  查看跨会话的全部待审批项，拿到 batchId 后照常提交决策；
  `GET /api/approvals?threadId=` 对 ADMIN/APPROVER 也不限会话归属。
  - `/api/delivery/my/**`：仅配送员本人或 ADMIN 可访问（权限隔离在 `DeliveryController` 内判断）。
- **登录页**：未登录访问 `http://localhost:20810/` 进入登录页（与主界面同一套设计语言），
  验证通过后凭据存入 `localStorage`（键 `hr_agent_auth`），之后每个请求自动带 `Authorization: Basic ...` 头。
- **登录态恢复**：刷新页面后本地凭据直接生效，并后台向 `GET /api/auth/check` 复核；
  任何接口返回 401（凭据失效/被改）都会清掉本地凭据并跳回登录页。

## 统一工具接入层（MCP）

**所有外部第三方 API 调用统一走 MCP 协议**（2026-10 起启用，不再是脚手架）：

- `mcp-server/`：独立的 MCP Server 进程（Spring Boot，`com.hr:adk-mcp-server`，端口 **20811**，
  STREAMABLE 协议单端点 `/mcp`，server 名 `adk-tools`）。承载全部外部工具：
  `queryWeather`（天气）、`queryBusRoute`（公交）、`getCurrentLocation`（IP 定位）、
  `searchPlace`（地点搜索）、`planRoute`（路线规划）、`driveRouteJson`（配送驾车路线，后端内部消费）。
  工具密钥仍从同一个 `external_tool` 表读取（独立进程内复制了只读版配置/解密服务，两处注释互相标注同步义务）。
- 主应用（:20810）作为 MCP Client：`CommonAgent` 经 `McpToolBinding.all(gateway, "adk-tools")`
  装配这 6 个工具；`AmapController`（历史地图刷新）与 `AmapRouteService`（配送路线）也经
  `McpToolGateway` 远程调用。进程内不再直连第三方 HTTP API（原 `CommonTools` 已删除）。
- 内部业务工具（Hr/Wms/Asset/Order 四个模块的 `@Tool`）仍是本地 `LocalToolBinding` ——
  它们操作的是本库业务数据，不是外部调用，不属于 MCP 收敛范围。
- **启动顺序是承重墙**（见「快速开始」）。
- **安全**：`/mcp` 端点无鉴权，仅限本机回环，不得对外暴露（经它可使用高德 key）。
- 构建：`cd mcp-server && mvn -B -DskipTests package`（产出 `target/adk-mcp-server-1.0.0.jar`，
  `start.ps1` 启动前会检查该 jar 存在）。
- 外部工具的密钥迁移入库用 `cli/ExternalToolKeyMigrator`（`mvn compile exec:java ...`，
  AES-256-GCM 加密写入 `external_tool` 表，不明文落库）。

## 接口

认证：除标注「匿名」的外都需要 HTTP Basic 凭据。

| 接口 | 说明 |
|---|---|
| `GET /api/auth/check` | 校验凭据并返回 `{username, roles}`（登录页与登录态恢复用） |
| `GET /api/channels`（匿名） | 渠道列表（含各自推理模型、向量模型、密钥状态） |
| `GET /api/modules`（匿名） | 已注册业务模块（key / 名称 / agentName / 职责 / **需人工审批的工具清单**） |
| `POST /api/chat` | `{message, threadId, provider, model, module?}`；`module` 为空走 orchestrator 自动调度 |
| `POST /api/chat/stream` | 同上参数，SSE 流式返回模型输出 |
| `GET /api/approvals?threadId=`（匿名） | 某会话下仍待确认的审批项（刷新页面后恢复确认框用） |
| `POST /api/approvals`（ADMIN/APPROVER） | `{batchId, decisions:[{id, action, arguments?, reason?}]}`，`action` = `APPROVE` / `REJECT` / `EDIT` |
| `GET /api/threads?limit=50`（匿名） | 会话列表（最后活跃时间倒序，含消息数、首个问题） |
| `GET /api/threads/{threadId}`（匿名） | 某会话的历史消息（含每条用的渠道/模型） |
| `DELETE /api/threads/{threadId}`（匿名） | 删除某会话的历史记录 |
| `DELETE /api/messages/{id}` | 删除单条消息 |
| `GET /api/embedding`（匿名） | 当前向量渠道与模型 |
| `POST /api/embedding/test` | `{text, provider?, model?}` 向量连通性测试 |
| `GET /api/health`（匿名） | 汇总状态（渠道数、模块列表、路由入口、HITL、向量配置） |
| `GET/POST/PUT/DELETE /api/manage/**` | 管理模块 CRUD：positions / employees / warehouses / stock / items / orders / deliveries / purchases / returns / couriers；`GET /api/manage/amapJsKey` 匿名 |
| `POST /api/amap/refresh` | 重新调用高德工具生成最新地图数据（历史会话「打开地图」用） |
| `/api/delivery/**` | 配送单 V2（`DeliveryController`），见下表 |
| `/api/form/outbound/**` | 表单出库（`OutboundFormController`，按设计绕过 HITL） |

**配送单 V2（`/api/delivery`，两阶段流程）**：创建配送单（不动库存）→ 确认出库（扣库存 + 写 `wms_order`
出库单 + 写流水 + 标记资产已出库）→ 配送中（路线规划已生成）→ 配送员确认送达（记录 `delivered_at`）。

| 接口 | 角色 | 说明 |
|---|---|---|
| `POST /api/delivery` | 管理员 | 创建配送单（关联订单，不动库存） |
| `GET /api/delivery` / `GET /api/delivery/{deliveryNo}` | 管理员 | 查询全部 / 详情 |
| `POST /api/delivery/{deliveryNo}/confirm-outbound` | 管理员 | 确认出库（此时才扣库存） |
| `POST /api/delivery/{deliveryNo}/cancel` | 管理员 | 取消配送单 |
| `GET /api/delivery/check-stock` | 管理员 | 校验发货仓库库存 |
| `GET /api/delivery/my/list` / `GET /api/delivery/my/{deliveryNo}` | 配送员本人 / 管理员 | 我的配送单 / 详情 |
| `POST /api/delivery/my/{deliveryNo}/delivered` | 配送员本人 | 确认送达 |

`/api/chat` 返回值：

```jsonc
// 正常完成
{ "status": "done", "reply": "...", "module": "hr_agent", "routed": true, "threadId": "..." }
// 需要人工确认
{ "status": "pending_approval", "batchId": "BATCH-...", "moduleKey": "hr", "moduleName": "人力资源",
  "approvals": [ { "id": "HITL-...", "tool": "add_employee", "toolLabel": "办理入职：…",
                   "arguments": "{\"name\":\"张三\", …}" } ] }
```

`threadId` 相同即延续多轮上下文（跨重启、跨模型）。

## 前端

前端是独立的 Vite 工程（`frontend/`），Vue 3 + Element Plus + Vue Router，构建产物直接输出到
Spring 静态目录，由后端统一托管，无需单独部署：

```powershell
cd frontend
npm install        # 首次
npm run dev        # 开发调试（5173 端口，/api 代理到 20810）
npm run build      # 产出到 ../src/main/resources/static
```

**改前端后必须重新 `mvn -B -DskipTests package` 再启动**，jar 里才会带上新页面。

源码结构：`views/`（LoginView / ChatView 页面骨架）+ `features/`（`chat` 对话、`delivery` 配送两个业务域）
+ `admin/`（**管理端**：布局、各业务视图、CRUD 组件、ECharts 封装）
+ `router/`（路由：`/login`、`/`、`/my-deliveries`、`/admin`）+ `stores/` + `components/`（AmapMap）。

### 界面布局

```
┌────────────── 顶栏：logo / 标题 / 用户卡片（头像 · 用户名 · 退出） ──────────────┐
│ 左栏 300px               ‹         对话区         ›              右栏 300px │
│  · 新会话                                                     · 工作台    │
│  · 控制台（模块）                                               · 快捷指令  │
│  · 历史会话（支持多选删除）                                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

- **左栏**：「新会话」按钮 + 控制台（模块下拉，默认「自动调度 ★」；来源/推理模型选择**仅管理员可见**，
  普通用户的模型由渠道治理统一调度）+ 历史会话列表（悬停单行删除；「多选」模式可批量删除）。
- **右栏**：「工作台」（进入管理端 / 我的配送单 / 返回聊天）+「快捷指令」（点击直接发送）。
  原来的「管理模块」CRUD 弹窗（员工/职位/仓库/订单）**已收敛到管理端**，客户端不再提供。
- 顶栏右上角有「管理端」按钮，**仅系统管理员（ADMIN）可见**。
- 配送员登录后进入「我的配送」页（`/my-deliveries`），可查看名下配送单并确认送达。
- 两侧栏都能点**把手**（`‹` / `›`）收起 / 展开：把手骑在侧栏内边缘，收起后留在屏幕边缘，动画为纯 CSS transition。
  展开状态记在 `localStorage`（键 `adk_panels`），刷新后保持。
- **向量模型面板已从界面移除**（不用于推理，日常用不到）。
  后端 `/api/embedding` 与 `/api/embedding/test` 仍保留，排查向量渠道时直接调接口。

### 前端页面缓存与版本（避免「看到旧界面」）

标签页打开期间**不会自动重载**页面代码。如果后端已更新而浏览器还跑着旧版页面，
表现会非常迷惑：旧页面没有确认框逻辑，只会把 `pending_approval` 的提示语当普通回复显示，
**看不到任何可点的卡片，用户以为流程卡死**（本机真实踩过）。

两道防线：

1. 静态资源禁用缓存：`spring.web.resources.cache.cachecontrol.no-cache: true`，刷新时必定重新校验 `index.html`。
2. **版本检测**：页面每 60 秒（以及切回标签页时）用 `cache: 'no-store'` 拉回线上 `index.html`，
   比对其中 `<script src="/assets/index-xxx.js">` 的**构建哈希**与当前页面加载的哈希；
   不一致就顶部弹红色横幅「页面代码已更新…立即刷新」。
   Vite 每次构建都会生成新哈希，因此**无需手工维护任何版本号**。

## 管理端（Admin Console）

面向**系统管理员（ROLE_ADMIN）**的独立控制台，与客户端（聊天 + 我的配送单）分离，
按企业级信息架构划分为 概览 → 模型管理 → 业务数据 → 权限管理 → 系统配置。

### 入口与权限

- 入口：客户端顶栏右上角「管理端」按钮，**仅 ADMIN 可见**（前端 `auth.roles` 判定）。
- 路由：`/admin`，`router/index.js` 中 `meta: { roles: ['ADMIN'] }`；守卫在跳转前
  `await auth.ensureRoles()` 补拉角色，避免刷新后角色未加载导致的误判。
- 后端：所有管理接口在 `/api/admin/**` 下，`SecurityConfig` 统一 `hasRole("ADMIN")`；
  原客户端的通用 CRUD 接口 `/api/manage/**` 也一并收敛为管理员专属
  （`/api/manage/amapJsKey` 保留匿名放行，配送地图仍可用）。
- 客户端只保留「聊天」与「我的配送单」；主数据（员工/职位/仓库/订单/采购/退货/配送作业）
  全部迁到管理端，避免两处入口、数据双写。

### 管理端布局（Element Plus 上下左右三块）

```
┌──────────────── 顶栏：系统名称 · 返回客户端 · 用户信息/退出 ────────────────┐
│ 左侧菜单          │              右侧：实际业务界面                        │
│  概览             │  （路由视图，按左侧菜单切换）                        │
│  模型管理         │                                                       │
│   模型来源管理    │                                                       │
│   熔断降级管理    │                                                       │
│   调用审计        │                                                       │
│  业务数据         │                                                       │
│   员工与职位      │                                                       │
│   仓储与库存      │                                                       │
│   订单与配送      │                                                       │
│   采购与退货      │                                                       │
│  权限管理         │                                                       │
│   用户与角色      │                                                       │
│  系统配置         │                                                       │
│   外部工具配置    │                                                       │
│   模块与 Agent    │                                                       │
│   审批与会话审计  │                                                       │
└───────────────────┴───────────────────────────────────────────────────────┘
```

### 概览（默认首页，ECharts 统计）

数据源是 `llm_call_log`（模型调用审计表），支持近 1 / 7 / 30 天切换：

- 指标卡：模型调用量、Token 总量、调用成功率、平均响应耗时、在用模型数、异常提示数。
- 图表：调用趋势（柱=调用量 + 两条折线=Token/成功率，双 Y 轴）、渠道分布（饼）、
  用户调用量 Top10（横向条）、模型调用量 Top10（横向条）、业务模块分布（饼）、
  24 小时调用热力（折线）、Top 失败原因（表）。

### 模型来源管理

对 `api_key` 表（= 模型渠道）做增删改查 + 连通性测试：渠道标识、名称、厂商、协议、
接口地址、模型清单、默认模型、向量模型、权重、是否默认、是否启用、备注、密钥。

- 密钥**只写不读**：列表只返回 `keyConfigured` 布尔位，编辑时留空表示沿用旧密钥。
- 改完渠道配置立即 `modelGateway.invalidate()`，不必等探测周期。

### 模型熔断降级管理

- **熔断策略**（`model_circuit_breaker` 表）：每个渠道一份策略 —— 启用开关、失败阈值、
  统计窗口（秒）、冷却时长（秒）、半开探测数；并实时展示状态机、当前失败数、剩余冷却、
  最后错误。支持「手动熔断」「手动恢复」。
- 状态机：`CLOSED →（统计窗口内失败数达阈值）→ OPEN →（冷却到期）→ HALF_OPEN →（探测成功）→ CLOSED`。
  失败计数是**窗口内累计**语义（不是连续失败），窗口过期自动重新计数。
- **降级链**（`model_fallback_rule` 表）：显式配置「渠道 A 出问题时降到渠道 B 的哪个模型」，
  按 priority 排序。**显式规则优先**——配了就以它为准，不再追加自动候选；
  没配的渠道仍走内置候选链（同模型的其他渠道 → 其他渠道的默认模型）。
- 熔断运行态在内存里，同时镜像回数据库，管理端直接看到当前状态；策略变更 10 秒内生效，
  降级规则变更立即失效缓存。

### 新增接口

| 前缀 | 说明 |
|---|---|
| `/api/admin/stats/*` | 概览 / 趋势 / 渠道 / 模型 / 用户 / 模块 / 失败原因 / 明细 |
| `/api/admin/channels` | 模型来源管理：列表 / 新增 / 修改 / 删除 / 连通性测试 |
| `/api/admin/circuit-breakers` | 熔断策略列表 / 修改 / 手动熔断 / 手动恢复 |
| `/api/admin/fallback-rules` | 降级链规则的增删查 |
| `/api/admin/users` | 用户与角色：列表 / 新增 / 修改 / 删除 / 重置密码 |
| `/api/admin/tools` | 外部工具配置（密钥只写不读） |
| `/api/admin/modules` | 已注册业务模块与 Agent |
| `/api/admin/approvals`、`/api/admin/threads` | 审批记录与会话审计 |

### 新增数据表

| 表 | 说明 |
|---|---|
| `model_circuit_breaker` | 各渠道熔断策略 + 运行态（状态、失败数、冷却截止、最后错误） |
| `model_fallback_rule` | 显式降级链规则（来源渠道 → 目标渠道/模型，唯一键防重复） |
| `llm_call_log` 新增列 | `username`（发起调用的登录用户）、`thread_id`（会话标识），用于按用户维度统计 |

> 老库升级：`schema.sql` 用的是 `CREATE TABLE IF NOT EXISTS`，不会给已存在的表补列。
> `llm_call_log` 的两个新列由 `config/AdminSchemaMigrator` 在启动时查 `INFORMATION_SCHEMA`
> 幂等补齐（MySQL 不支持 `ADD COLUMN IF NOT EXISTS`）。

### 前端依赖提醒

管理端图表用的是 **ECharts 5**，已在 `frontend/package.json` 声明 `echarts: ^5.5.1`。
首次拉代码后需要：

```powershell
cd frontend
npm install echarts     # 若网络受限，可用仓库内附带的离线包
npm run build           # 产出到 ../src/main/resources/static
```

## 数据存储

| 数据 | 存储位置 | 说明 |
|---|---|---|
| **Agent 多轮上下文** | MySQL `graph_checkpoint` / `graph_thread` | 由框架 `MysqlSaver` 持久化，**重启不丢** |
| **人工审批单** | MySQL `hitl_approval` | 待确认清单 + 决策 + 恢复执行结果（重启后可继续审批） |
| **会话历史/审计** | MySQL `chat_message` | 应用显式写入（threadId / 角色 / 渠道 / 模型 / 内容 / 时间） |
| 渠道配置与密钥 | MySQL `api_key` | 见上文 |
| 登录用户 | MySQL `sys_user`（主）+ 内存（兜底） | 见「登录与认证」 |
| 业务数据（HR） | MySQL `position` / `employee` | 员工职位 |
| 业务数据（WMS） | MySQL `wms_warehouse` / `wms_item` / `wms_stock` / `wms_order` / `wms_stock_log` / `wms_asset` / `wms_asset_log` | 仓库商品库存、单据、一物一码资产 |
| 业务数据（订单/配送） | MySQL `biz_order` / `biz_delivery` / `biz_purchase` / `biz_return` | 销售订单、配送、采购入库、退货入库 |
| 外部工具配置 | MySQL `external_tool` | 天气/高德等外部 HTTP 工具的 URL 与加密 Key |
| **SKU 取号序列** | MySQL `wms_sku_seq` | 按「时间戳到秒」分段自增，保证同秒内生成的 SKU 不撞号（见「批量入库与 SKU 自动生成」） |
| ChatModel / ReactAgent 实例 | JVM 内存 | `AgentFactory` 按「provider::model」「模块::provider::model」缓存（可重建） |

要点：

- **checkpoint 线程名按模块加命名空间**（`hr@<threadId>` / `wms@<threadId>` 等）。
  `graph_thread` 表上 `(thread_name, is_released)` 是**唯一索引**，thread_name 全局唯一；checkpoint 又是按 thread_id 取最新一条恢复的。
  若各模块共用同一个 threadId，同一个会话里切换模块会**串台**，让 B 模块拿着 A 模块的对话历史开跑。
  加命名空间后每个模块有独立上下文；而会话维度（`chat_message` / `hitl_approval`）仍统一用前端传来的 `threadId`。
- 全局共享同一个 `MysqlSaver`，因此**同一 `threadId` 中途换模型也能延续上下文**（实测：百炼记忆 → 重启 → DeepSeek 提问仍答对）。
- 若长期运行导致上下文过长，可加裁剪/摘要 Hook（框架提供 `SummarizationHook` 等）。
- **`biz_delivery` 的 V2 扩展列**（经纬度 / 路线规划 / 配送员 / 发货仓库等 9 列）由
  `DeliveryV2ColumnMigrator` 在应用启动后幂等补齐（查 `INFORMATION_SCHEMA`，缺哪列加哪列）。
  不用存储过程的原因：MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，而 `spring.sql.init` 的脚本执行器
  不认识 `DELIMITER`（存储过程写法会让应用启动失败）。

## 批量入库（一次清单、一次确认）与 SKU 自动生成

### 问题：按商品逐个入库 = 按商品逐个确认

原来的 `inbound` 只处理**一个** SKU。一张 100 行的入库清单会被模型拆成 100 次工具调用，
而 HITL 是**按工具调用**弹确认框的 —— 用户要点 100 次「批准」。
这不是模型偷懒，是**工具粒度**的问题，所以在工具层解决。

### 方案：`batch_inbound` + 明细预览表

```jsonc
// 一次调用带整张清单（items 是 JSON 数组字符串）
batch_inbound({
  "items": "[{\"name\":\"防化服\",\"unit\":\"件\",\"quantity\":30},
             {\"name\":\"投影仪\",\"unit\":\"台\",\"quantity\":12}]",
  "warehouseCode": "WH01", "supplier": "", "operator": ""
})
```

- 整批 200 行以内（合并重复后），**一个工具调用 → 一个审批项 → 一次确认**。
- 审批项上挂一张只读预览表，用户逐行核对（见「确认框的操作方式」）。
- 商品不存在则**自动建档**，不需要先挨个 `add_item`。
- **整批一个事务**：要么全部写入，要么一行都不动，不会留下"商品建了一半、库存没加"的烂账。
- 单据与流水：生成一个批次号 `IN-<时间戳>-<随机>`，各行单号 = `批次号-001`、`-002`…，
  `wms_order.remark` 记「批量入库 <批次号>」，`wms_stock_log.ref_no` 记行单号。
  （`wms_order.order_no` 有唯一索引，所以不能 100 行共用一个单号，用"批次前缀 + 行号"既能唯一定位又看得出同批。）
- 合并规则：**名称 + 规格相同**视为同一物品，数量相加（分供应商/分批到货的重复行不该变成两个商品）。
- 清单与库内对同一商品的描述不一致（单位/类别）时，**以库内档案为准**并在结果里列出提醒，
  不会悄悄改掉商品主数据。

### SKU 由系统自动生成

格式：`SKU-<yyyyMMddHHmmss>-<四位数序号>`，例如 `SKU-20260930151508-0001`。

- **用户和模型都不再指定 SKU**（`add_item` 的 `sku` 参数已移除）。
- 「同一物品」的判定口径是 **名称 + 规格**：都相同就复用已有 SKU，不重复建档；
  名称相同、规格不同算两个商品。
- 唯一性靠 `wms_sku_seq(ts_key, next_val)` 表：
  `INSERT ... ON DUPLICATE KEY UPDATE next_val = next_val + 1` 取号，
  在事务里靠主键行锁保证原子。**不要**用 `MAX(sku)+1` 或"先查后写" —— 并发下会发出重复 SKU。
- 取号与插入必须**在同一个事务**里（`SkuGenerator` 的类注释里写了原因：
  自动提交模式下两次取号之间可能被别的线程插进来，读回的序号会偏大）。

### 验证方式（不用模型也能测）

`BatchInboundService` 刻意拆成三段，预览和执行**共用同一个 `Plan`**，
否则会出现"预览说新建 100 个、执行却只建了 98 个"这种对不上账的情况：

1. `parse(itemsJson)` —— 纯解析 + 校验 + 合并，**不碰数据库**；
2. `preview(plan, warehouseCode)` —— **只读**查库，标出哪些是新建/已有、会生成什么 SKU；
3. `execute(plan, warehouseCode, supplier, operator)` —— 一个事务完成建档、加库存、开单、记流水。

因为前两段不写库，可以用一个 `main()` 直接驱动它们做回归（不消耗模型额度、不产生脏数据）。
渲染验证则可以给页面的 `addApproval()` 注入假数据来跑。

## 资产台账（一物一码，可调拨给员工）

在职员工有「个人物品栏」，物品可从仓库调拨使用、可退回；员工离职时名下物品**全部回归仓库**；
仓库要能出「全部物品清单」与「当前在库清单」；盘点时未调拨的显示在库、已调拨的显示使用人；
**除非销毁，否则不从全部清单划掉**；同一件物品只能调拨给一个用户。

### 两本账，别搞混

|  | 表 | 粒度 | 用途 |
|---|---|---|---|
| 库存账 | `wms_stock` | 仓库 × 商品 → 数量 | 常规出入库（`inbound` / `outbound`） |
| 资产账 | `wms_asset` | 一件一行、一物一码 | 登记后调拨给员工、盘点对账 |

**「登记」（`register_assets`）是两者的转换口**：从 `wms_stock` 划出 N 件 → 生成 N 个资产编号，
两边合计守恒，同一件东西不会被算两遍。登记之后这件东西只认资产账，调拨/退回/销毁都不再动 `wms_stock`。

### 状态机

```
登记 ──► 在库 ──调拨──► 已调拨 ──退回──► 在库
          │  │              │
          │  └─仓间调拨────► 在库（另一仓库，transfer_asset）
          └──────销毁────────┴──────► 已销毁（从「全部物品清单」划掉）
```

- **全部物品清单** = 在库 + 已调拨（不含已销毁）；**当前在库清单** = 仅在库。
- **销毁是唯一能把物品移出全部清单的操作**，变动流水（`wms_asset_log`）仍保留可追溯。
- 仓间调拨（`transfer_asset`）只改在库资产的所属仓库，不改变其状态与使用人。

### 三条硬约束（都在数据库层兜底）

1. **同一件物品只能给一个人**：调拨语句是
   `UPDATE wms_asset SET status='已调拨', holder_employee_id=? WHERE asset_no=? AND status='在库'`，
   靠**影响行数**判断是否抢到。并发时只有一个能改到 1 行，另一个拿到 0 行并被如实告知「已被 X 领用」。
2. **状态与使用人必须一致**：表上 `ck_asset_holder` CHECK 约束 —— 只有「已调拨」才允许有使用人。
   把「调拨了却忘记录使用人」「退回了却还挂着人」这类脏数据堵在数据库层。
   ⚠️ 改状态时 `holder_employee_id` 必须放在**同一条 UPDATE** 里改，否则中间态会违反约束。
3. **登记不能把库存扣成负数**：事务内 `SELECT ... FOR UPDATE` 加行锁后再校验、再扣减。

### 离职联动（事件驱动，跨模块）

`dismiss_employee` 在员工状态变更的**事务提交后**发布 `EmployeeResignedEvent`，
由 `event/AssetRecycleOnResignListener`（`@TransactionalEventListener` + `@Async`）
在**独立事务**中异步调用 `AssetService` 把名下物品全部退回仓库：

- 用 `@TransactionalEventListener` 确保看到已提交的离职状态，避免「资产已回收但员工状态回滚」的中间态；
- 异步执行不阻塞离职操作的主流程响应；
- **回收失败只记日志、不影响已完成的离职**，可通过 `return_asset` 手动补偿。

HR 与 WMS 两个模块共用 `AssetService`（HR 不直接依赖 WMS 的工具类），规则只写一份，不会随时间走样。
`AssetTools` 只做三件事：校验入参 → 调 `AssetService` → 把结果整理成模型看得懂的中文。

### 跨模块盲区（实测踩到，已在提示词里处理）

HR 与 WMS 的 checkpoint 线程是**隔离**的（`hr@thread` / `wms@thread`），
所以 WMS 模块**看不到** HR 执行的「离职自动回收」。实测现象：先调拨给赵鹏 → 再办离职 → 再让 WMS 盘点，
WMS 记得「调拨给了赵鹏」、盘点却显示「在库」，于是把它当成**数据异常**报给用户（数据其实完全正确）。

处理：WMS 提示词写明「离职会自动回收，不要报成数据不一致」，
并提供 `asset_history` 查变动流水 —— 模型能自己查到那条「退回（离职自动回收）」记录并解释清楚。

### 候选清单（调拨/退回/销毁时逐件勾选）

`spi/ApprovalOptionsProvider` 是给确认框提供"可勾选清单"的通用接口，`AssetService` 实现了它：

| 工具 | 候选范围 | 写回字段 |
|---|---|---|
| `allocate_asset` | 同一商品、状态=在库 | `assetNos` |
| `return_asset` | 同一商品、状态=已调拨 | `assetNos` |
| `scrap_asset` | 同一商品、状态≠已销毁 | `assetNos` |

- **范围收敛**：用模型给的 `assetNos` 反查商品，只列**同一个商品**的候选；
  模型给的编号一个都对不上时才退化为全量候选。
- 后端在 `ChatController.attachChoices()` 把 `choices / choiceField / choiceHint` 挂到审批项上，前端渲染成复选框。
- 以后再加"从一批对象里挑几个"的工具，只要在 `AssetService.OPTION_FIELDS` 登记「工具名 → 字段名」，
  并在 `forTool()` 里补一条候选范围规则即可。

## 工具边界与事务（数据安全相关）

写工具是唯一能改数据的地方，因此在这一层做了集中约束（`com.hr.agent.util.Args`）：

- **入参校验统一返回可读说明，不抛异常。** 返回值会作为工具结果回给模型，模型据此自我纠正；
  校验信息里带上"收到的值 + 正确格式"，模型转述给用户也自然。
  覆盖：手机号（11 位）、邮箱、性别（只 男/女）、姓名/名称长度与控制字符、数量上下限
  （正整数且 ≤ 1,000,000）、SKU / 仓库编码格式。
- **员工状态守卫**（`service/EmployeeGuard`）：调岗、调拨资产仅限在职员工，离职员工拦截；
  更新联系方式离职也允许。之前这些判断散落在 HrTools / AssetService 各处、规则不统一，现收拢一处。
- **原子性**：`inbound` / `outbound` 的「改库存 + 开单 + 记流水」用 `TransactionTemplate` 包在一个事务里，
  失败整体回滚并返回可读原因（**不用 `@Transactional` 注解** —— `ToolCallbacks.from()` 是注解扫描注册工具的，
  CGLIB 代理有丢 `@Tool` 的风险）。
- **并发防超卖**：`outbound` 在事务内用 `SELECT ... FOR UPDATE` 重新读取并加行锁再校验，
  不足则抛 `InsufficientStockException` 触发回滚（"先查再扣"在并发下会各扣一次）。
- **主键回显**：新增记录用 `GeneratedKeyHolder` 取真实主键，不用 `SELECT MAX(id)`（并发下会回显错人）。
- **外键边界**：`delete_position` 会分别统计该职位下的**在职**与**离职**员工。
  `employee.position_id` 外键是 RESTRICT，只统计在职会导致"职位下挂着离职员工时直接抛 SQL 异常"。
- **列表上限**：所有列表类工具 `LIMIT 50` 并返回"共 N 条，已显示前 M 条"。
- **歧义拦截**：商品/职位模糊匹配命中多个时不静默取第一个，要求用户确认，防止把 A 商品入到 B 商品上。
- **短路无意义写操作**：如"调岗到当前同一职位"直接返回说明，不弹确认框、不写库。

## 实现要点

- 模块 Agent：`ReactAgent` + `@Tool` 方法 + `ToolCallbacks.from(...)`（`org.springframework.ai.support`）；
  工具来源经 `ModuleAgent#toolBindings()` 声明（`LocalToolBinding` / `McpToolBinding`），由 `McpToolGateway` 统一适配成 `ToolCallback`。
- HITL：`.hooks(List.of(humanApprovalHook(module), new MessageGuardHook()))`，其中
  `HumanInTheLoopHook.builder().approvalOn(tool, ToolConfig.builder().description(desc).build())`。
- 恢复执行：`RunnableConfig.builder().threadId(..).addHumanFeedback(InterruptionMetadata).build()`，
  然后 `agent.invokeAndGetOutput(Map.of(), config)`；**绝不要加 `.resume()`**。
- 中断识别：`invokeAndGetOutput` 返回的 `NodeOutput` 若 `instanceof InterruptionMetadata` 即为「待人工确认」。
- `AgentFactory` 懒加载并按「渠道::模型」「模块::渠道::模型」缓存，**某个渠道缺 Key 也不影响启动**。
- `protocol=openai` → `OpenAiChatModel` / `OpenAiEmbeddingModel`（显式设置 `completionsPath`、`embeddingsPath`，避免拼成 `/v1/v1/...`）；`protocol=dashscope` → `DashScopeChatModel`。
- 密钥存库需排除 DashScope 全部自动配置（见 `HrAgentApplication`），否则会因缺少 `spring.ai.dashscope.api-key` 启动失败。
- Agent 调用抛受检异常 `GraphRunnerException`，Controller 已统一捕获（`GlobalExceptionHandler`）。

## 包结构与已知设计债

仓库含两个可独立构建部署的进程：根目录主应用 `hr-agent`（:20810，Web + Agent 引擎）与
`mcp-server/`（:20811，全部外部第三方工具经 MCP 协议暴露，详见「统一工具接入层（MCP）」）。

主应用按职责分包（`com.hr.agent.` 下）：

| 包 | 职责 |
|---|---|
| `controller` | 传输层端点 + 全局异常处理（Chat / Auth / Delivery / Amap）；业务数据 CRUD 与管理端接口在子包 |
| `controller/manage` | 业务数据 CRUD，**按域拆分**：HrManage（员工/职位）/ WmsManage（仓库/库存/商品）/ OrderManage（订单/配送/采购/退货）+ ManageSupport 公共辅助 |
| `controller/admin` | 管理端专属接口（模型来源/熔断降级/统计/用户/系统配置），`/api/admin/**` 仅 ROLE_ADMIN |
| `form` | 结构化前置表单（出库单/配送单的 schema+提交）与**意图拦截器**（OutboundFormInterceptor / DeliveryFormInterceptor） |
| `service` | 业务服务（Asset / BatchInbound / Delivery / ApiKey / ExternalTool / EmployeeGuard / SkuGenerator…），JdbcTemplate 内联，暂无 repository 层 |
| `spi` | harness 扩展点契约：`ApprovalOptionsProvider`（候选勾选）/ `ApprovalPreviewProvider`（明细预览）/ `ChatIntentInterceptor`（意图→卡片） |
| `config` | 纯 @Configuration 装配（AgentFactory / Security / User / Checkpoint / MCP 启动检查 / 列迁移） |
| `agents` | 模块 Agent 定义（ModuleAgent 契约 + 4 个实现） |
| `tools` | @Tool Bean（Hr / Wms / Asset / Order），只做参数解析与文案，业务规则下沉 service |
| `mcp` | 工具接入层（McpToolGateway + Local/McpToolBinding） |
| `hooks` | 模型钩子（MessageGuardHook） |
| `store` | 会话/HITL 持久化（ConversationStore / HitlService） |
| `event` | 应用事件（离职事件 + 资产回收监听器） |
| `util` | 工具（Args 入参校验） |
| `cli` | 运维命令行（ExternalToolKeyMigrator 密钥加密迁移） |

**harness 边界**：`model/**`（模型接入）+ `agents` 契约 + `config/AgentFactory`（编排）+ `hooks` + `store` +
`mcp` + 安全/管理端基座 属于系统环境层；`tools`/`service`/`form`/`event` 属于业务模块。
业务通过三个 SPI 接入 harness：审批候选/预览（spi 包既有）、意图拦截（`ChatIntentInterceptor`）——
ChatController 不再 import 任何业务类，新增"意图→卡片"只需业务侧注册一个 @Component。

已知设计债（已在代码 NOTE 注释中互相标注）：

- ~~`biz_delivery` 三条写入路径~~ **已收口**：`DeliveryService` 是唯一写入入口
  （`createDelivery` 两阶段 / `createImmediateDelivery` 立即配送 / `confirmOutbound` 确认出库），
  `OrderTools#create_delivery` 与管理端配送 CRUD 均委托。顺带修复了 `confirmOutbound` 写错
  `wms_order.type`/`wms_stock_log` 列名的潜伏 bug。
- ~~`ManageController` 局部 `@ExceptionHandler` 遮蔽全局处理器~~ **已收敛**：全局处理器对
  `IllegalStateException` 返回 409 + 具体原因，各域控制器不再有局部处理器。
- `GROUND_TRUTH_HINT` 在 HrTools/OrderTools/WmsTools/AssetService 四处有意重复，改动需四处同步。
- `form/OutboundFormController#submit` 复制 `WmsTools#outbound` 执行逻辑（按设计绕过 HITL），改动需双向同步。
- mcp-server 内复制了主应用的只读版外部工具配置/解密服务（`ExternalToolConfigService` /
  `ApiKeyEncryptionService`），两处注释互相标注同步义务。
