# 游离子图归并与图谱建设日报开发文档

> 状态：2026-09-07 已在独立 Java 仓库实施 V6 迁移、人工归并和日报接口。本文包含后续开发规格；真实已实现的表、接口及部署边界以 [实施与联调手册](./20260907-governance-implementation.md) 为准。尚未对生产 `app_page_graph` 单表执行迁移。

## 1. 开发范围

本文定义后端、前端、脚本和数据库的实施契约。接口 JSON 统一使用 camelCase；PostgreSQL 列可以使用 snake_case，由 ORM 或 MyBatis 映射。

生产业务数据契约以用户确认的 `app_page_graph` 和 `embedding_text` 为准。当前独立 Java 仓库迁移脚本使用 `canonical_pages/page_instances/page_edges`，是另一种存储实现；不能据此否认生产字段存在，也不能直接把本文 SQL 当成该仓库现有表迁移执行。

落地时增加 `CoverageSourceRepository`，以真实业务库或经过显式字段映射的适配器提供 `appId/pageId/pageUrl/embeddingText`。生产适配器查询 `app_page_graph`；独立 Java 适配器必须先接入并持久化真实 `embeddingText`，不能将 `ai_summary` 当成同义字段。如果日报服务与图谱写入服务不在同一数据库事务中，写入侧需要事务事件表和幂等消费者，不能宣称跨服务写入处于一个本地事务。

本期覆盖判定固定为：APP 有效、URL 非空，且任一关联节点的 `embedding_text` 非 NULL、去除空白后非空。`embedding_text` 不等于向量，图片换绑不改变 URL 身份。`firstCoverageTime` 采用服务端首次有效登记时间；设备的 `observedAt` 单独记录为证据，不用于回填或刷新该时间。

本期新增模块：

```text
脚本
  探索会话上下文、previousPageId、入口控件、步骤序号

后端
  游离子图识别、父节点候选、批量归并、撤销、覆盖事实、日报

前端
  游离复核工作台、新功能区入口确认、日报页面、URL 定位

数据库
  探索步骤、临时入口、结构事件、覆盖事件、URL 覆盖事实、日报快照
```

## 2. 数据库设计

### 2.1 页面字段补充

已有页面表应能够表达以下字段；实际表名按后端项目迁移脚本调整：

下面 SQL 是生产单表模型的设计示例。正式发布应先回填旧节点状态并验证，再切换查询；不得把默认 `ORPHAN/UNCOVERED` 直接作为全部历史数据的真实状态。

```sql
alter table app_page_graph
  add column if not exists structure_status varchar(24) not null default 'ORPHAN',
  add column if not exists coverage_status varchar(24) not null default 'UNCOVERED',
  add column if not exists resolved_at timestamptz,
  add column if not exists resolved_by varchar(128),
  add column if not exists merge_source varchar(32);
```

`graphVersion` 属于 APP 级图谱，不放在每个页面上充当全图版本。单独的图版本记录按 APP 保存并在结构写入事务中锁定、校验和递增。

`firstCoverageTime` 不建议只存页面节点，因为同一 URL 可以有多个节点。节点可以保留派生值用于展示，日报以 URL 事实表为准。

### 2.2 探索步骤

```sql
create table if not exists graph_exploration_step (
  id bigserial primary key,
  session_id varchar(64) not null,
  app_name varchar(128) not null,
  step_index integer not null,
  page_id varchar(64),
  page_url text,
  previous_page_id varchar(64),
  previous_page_url text,
  entry_widget text,
  action_type varchar(32),
  function_area varchar(128),
  screenshot_file_name text,
  raw_payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique (session_id, step_index)
);

create index if not exists idx_exploration_step_app_session
  on graph_exploration_step(app_name, session_id, step_index);
```

### 2.3 游离子图及候选入口

```sql
create table if not exists orphan_subgraph (
  subgraph_id varchar(64) primary key,
  app_name varchar(128) not null,
  entry_page_id varchar(64) not null,
  status varchar(24) not null default 'PENDING_ENTRY',
  node_count integer not null default 1,
  provisional_edge_id varchar(64),
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

create table if not exists orphan_parent_candidate (
  id bigserial primary key,
  subgraph_id varchar(64) not null references orphan_subgraph(subgraph_id),
  page_id varchar(64) not null,
  parent_page_id varchar(64) not null,
  rank_no integer not null,
  score numeric(6,5) not null,
  evidence jsonb not null default '[]'::jsonb,
  model_version varchar(64),
  created_at timestamptz not null default now(),
  unique (subgraph_id, page_id, parent_page_id)
);

create index if not exists idx_orphan_candidate_review
  on orphan_parent_candidate(subgraph_id, rank_no, score desc);
```

### 2.4 结构变更审计

```sql
create table if not exists graph_structure_event (
  event_id bigserial primary key,
  batch_id varchar(64) not null,
  app_name varchar(128) not null,
  page_id varchar(64) not null,
  operation varchar(32) not null,
  old_parent_id varchar(64),
  new_parent_id varchar(64),
  old_edge jsonb,
  new_edge jsonb,
  merge_source varchar(32) not null,
  match_score numeric(6,5),
  operator_name varchar(128),
  graph_version bigint not null,
  operated_at timestamptz not null default now()
);

create index if not exists idx_structure_event_batch
  on graph_structure_event(batch_id, operated_at);
```

`oldEdge` 和 `newEdge` 用于撤销。审计记录不随页面删除而删除。

### 2.5 覆盖事件与 URL 首次覆盖事实

```sql
create table if not exists page_coverage_event (
  event_id bigserial primary key,
  app_name varchar(128) not null,
  page_id varchar(64) not null,
  page_url text not null,
  page_url_hash char(64) not null,
  event_type varchar(32) not null,
  source_type varchar(32) not null,
  covered_at timestamptz not null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists idx_page_coverage_event_url_time
  on page_coverage_event(app_name, page_url_hash, covered_at);

create table if not exists app_url_coverage_fact (
  id bigserial primary key,
  app_name varchar(128) not null,
  page_url text not null,
  page_url_hash char(64) not null,
  first_coverage_time timestamptz,
  first_page_id varchar(64),
  source_type varchar(32),
  is_baseline boolean not null default false,
  created_at timestamptz not null default now(),
  unique (app_name, page_url_hash)
);
```

`pageUrlHash` 是未截断的原始 `pageUrl` 的 SHA-256。它避免超长 URL 直接建立 B-tree 唯一索引；命中已有 hash 时仍比较原始 URL，发现不一致则作为 hash 冲突报警并拒绝静默合并。

唯一约束只存在于 URL 粒度的事实表，不限制页面表和事件表中的重复 URL。当前业务采用 URL 完整字符串精确匹配，因此计算 hash 前不删除查询参数、不改变大小写、不补斜杠。

覆盖登记伪代码：

```text
begin transaction
  保存页面、截图、识别结果
  if 写入后的 APP 有效 and URL 非空 and embeddingText 有效:
    对目标 APP + URL 加锁或通过唯一约束竞争登记
    insert appUrlCoverageFact（服务端登记时间）
      on conflict (appName, pageUrlHash) do nothing
    校验冲突记录的 URL 原文一致，不更新已有 firstCoverageTime
  if 节点有效覆盖状态或 URL 关联确实发生变化:
    insert pageCoverageEvent（携带源事件 ID，幂等写入）
commit
```

例子：U 从图片 A 换到图片 B，U 的事实存在，因此时间不变。有效节点从 URL A 修改为 B 时，A 的历史保留；B 已有覆盖事实则保留原时间，B 从未覆盖才新登记。只存在 URL 占位而没有有效 `embedding_text` 时，不提前写入覆盖事实。

覆盖事实记录的是历史首次成果；实时分子来自当前有效关联。删除最后一条有效关联可以降低当前覆盖数，但不能删除首次覆盖事实。用户当前以 APP 名称匹配，生产映射需保证名称唯一且受控；有稳定 APP 主键时，将其作为事实唯一键并保留名称用于展示，改名不得生成新覆盖。

### 2.6 日报快照

```sql
create table if not exists graph_daily_report (
  report_id varchar(64) primary key,
  report_type varchar(16) not null,
  period_start timestamptz not null,
  period_end timestamptz not null,
  generated_at timestamptz not null,
  revision integer not null default 1,
  baseline_id varchar(64) not null,
  metric_policy_version varchar(32) not null,
  timezone varchar(64) not null default 'Asia/Shanghai',
  summary jsonb not null,
  app_metrics jsonb not null,
  url_details jsonb not null default '[]'::jsonb,
  unique (report_type, period_start, period_end, revision)
);

create index if not exists idx_daily_report_period
  on graph_daily_report(period_end desc);
```

同一期主动重新生成时追加修订，不覆盖已经发布的内容；趋势按该期最新修订显示一个点。网络重试使用同一个请求幂等键，返回原修订。`urlDetails` 为生成时的明细快照，规模较大时拆为报告明细表或不可变对象存储文件。生成过程使用一致的数据库读快照，固定分母版本和口径版本。

### 2.7 后台维护日报基准

运行时不读取本地 Excel。后台维护以下实体，前端维护和系统同步都调用同一组服务：

| 实体 | 必要字段 | 约束 |
|---|---|---|
| `reportBaseline` | baselineId、revision、status、source、updatedAt、publishedAt | DRAFT/PUBLISHED，发布后不可原地修改 |
| `reportAppTarget` | baselineId、appId、appName、appGroups、totalUrlCount、specialNote | 每版本每 APP 一行，总数非负；TOP/TGI 可重叠，总览按 APP 去重 |
| `reportPriorityUrl` | baselineId、appId、pageUrl、pageUrlHash | 每版本每 APP + URL 去重，保留完整原文 |
| `reportSyncJob` | jobId、idempotencyKey、source、status、errors、baselineId | 批量同步失败不发布半份基准 |

维护流程为“读取当前已发布版本 -> 编辑或同步生成草稿 -> 校验 -> 原子发布新版本”。报告查询实时使用最近成功发布的版本，展示来源和更新时间。后台不能自行推导厂商尚未提供的全量分母。

如果只有全量 URL 数量，则保留计数覆盖口径，不能生成全量未覆盖 URL 明细；高频清单有逐条 URL，可以准确计算交集。未配置或分母为 0 时覆盖率返回 null，界面显示“—”；缺失 APP 和重复/冲突名称进入数据质量列表，不静默按 0 覆盖处理。当前分子按最新有效业务范围计算，历史首次事实跨版本保留，两者不可混用。

## 3. 边模型

页面边至少包含：

```json
{
  "edgeId": "edge-001",
  "sourcePageId": "page-a",
  "targetPageId": "page-b",
  "edgeType": "PAGE_NAVIGATION",
  "widgetDescription": "消息",
  "actionType": "CLICK",
  "isExecutable": true,
  "reviewStatus": "MANUAL_CONFIRMED",
  "evidenceIds": ["step-12", "asset-88"]
}
```

约束：

- `PROVISIONAL_ENTRY` 必须 `isExecutable=false`。
- 页面不能成为自己的父节点。
- 新边不能形成环。
- 父子页面必须属于同一个 APP。
- 每个结构变更必须生成新的 `graphVersion`。

无环约束适用于所有新增图谱边，包括人工、脚本导入和批量自动归并。`A -> B -> D` 与 `A -> H -> D` 允许同时存在；`D -> A` 会形成环，应拒绝。返回、前进等执行动作可以记录在四层动作或脚本步骤中，但不绕过图谱无环约束。

当前 `GraphService.moveNode` 会删除目标全部入边，批量归并不得直接循环调用该实现。请求应标明操作为 `ADD_ENTRY` 或 `REPLACE_ENTRY`；后者需要 `replacedEdgeId`，仅替换指定的归类边并保留其他已确认入口，审计保存完整受影响边集合。

## 4. 后端 API

所有接口统一前缀 `/appGraph`。

### 4.1 扫描游离子图

```http
POST /appGraph/orphans/scanSubgraphs
Content-Type: application/json

{
  "appName": "QQ",
  "pageIds": [],
  "rebuildCandidates": false
}
```

返回游离子图、入口节点和统计数量。

### 4.2 生成父节点候选

```http
POST /appGraph/orphans/matchParents

{
  "appName": "QQ",
  "subgraphIds": ["subgraph-001"],
  "topK": 3,
  "enableAiReview": false
}
```

返回：

```json
{
  "status": "success",
  "data": {
    "matchedCount": 80,
    "autoMergeCount": 52,
    "reviewCount": 20,
    "manualCount": 8,
    "items": [
      {
        "subgraphId": "subgraph-001",
        "entryPageId": "page-b",
        "candidates": [
          {
            "parentPageId": "page-a",
            "score": 0.96,
            "reasons": ["HDC 前序页面一致", "入口控件一致"]
          }
        ]
      }
    ]
  }
}
```

### 4.3 预检批量归并

```http
POST /appGraph/orphans/previewBatchMerge
```

预检同 APP、节点存在、父节点存在、环路、重复边、不可执行边和版本冲突，不修改数据。

### 4.4 批量归并

```http
POST /appGraph/orphans/batchMerge

{
  "appName": "QQ",
  "graphVersion": 18,
  "items": [
    {
      "pageId": "page-b",
      "newParentId": "page-a",
      "edgeType": "PAGE_NAVIGATION",
      "widgetDescription": "消息",
      "matchScore": 0.96,
      "mergeSource": "AUTO_HDC"
    }
  ]
}
```

返回逐条结果，不以第一条失败掩盖其他结果：

```json
{
  "status": "success",
  "data": {
    "batchId": "merge-20260907-001",
    "graphVersion": 19,
    "successCount": 1,
    "failureCount": 0,
    "results": [
      {
        "pageId": "page-b",
        "success": true,
        "structureStatus": "MERGED",
        "reachableFromRoot": true
      }
    ]
  }
}
```

### 4.5 创建待确认入口

```http
POST /appGraph/orphans/createProvisionalEntry

{
  "appName": "QQ",
  "subgraphId": "subgraph-001",
  "entryPageId": "page-b",
  "reason": "未发现真实入口"
}
```

### 4.6 撤销批次

```http
POST /appGraph/orphans/rollbackBatch

{
  "batchId": "merge-20260907-001",
  "expectedGraphVersion": 19
}
```

如果相关节点在归并后又被修改，返回冲突，不强制覆盖新数据。

### 4.7 日报接口

```text
GET  /appGraph/reports/daily?reportType=MORNING&date=2026-09-07
GET  /appGraph/reports/trends?days=30&appGroup=TOP
GET  /appGraph/reports/apps/{appName}/urls?reportId=...&type=NEW
POST /appGraph/reports/generate
GET  /appGraph/reports/{reportId}/exportHtml
```

`generate` 返回固定统计区间、生成时刻、五项汇总和 APP 明细。

基准维护接口（待实现）：

```text
GET  /appGraph/reports/baselines/current
POST /appGraph/reports/baselines/createDraft
POST /appGraph/reports/baselines/upsertAppTarget
POST /appGraph/reports/baselines/upsertPriorityUrls
POST /appGraph/reports/baselines/removePriorityUrls
POST /appGraph/reports/baselines/publish
POST /appGraph/reports/baselines/sync
GET  /appGraph/reports/baselines/syncJobs/{jobId}
```

修改请求包含 `baselineId/expectedRevision`；应用配置含 `appId/appGroups/totalUrlCount/specialNote`，URL 清单项含 `appId/pageUrl`。发布执行完整校验和版本冲突检查；同步请求含源标识、幂等键和全量/增量模式，不能把未上传的增量项解释为删除。

## 5. 匹配服务实现

### 5.1 子图识别

1. 获取所有从真实根节点不可达的页面。
2. 忽略连接到 `APP_ROOT` 的临时入口边。
3. 在游离节点诱导子图中计算弱连通分量。
4. 入度为 0 的节点作为入口候选。
5. 多入口子图允许拆分或标记 `MULTI_ENTRY_REVIEW`。

本系统禁止环。扫描发现历史循环数据时，将其标记为结构异常并停止自动接入，提示人工修复，不能静默删边。无环子图仍可能多入口：`B -> D <- C` 接入 B 后只能确认 B、D 可达，C 保持待处理。子图成员和入口集合需显式存储或在固定图版本上可重建，不能用一个 `entryPageId` 推断整组已完成。

### 5.2 候选召回

候选父节点必须满足：

- 同一个 `appName`。
- 属于当前最新图谱版本。
- 不在待归并子图内部。
- 不能是入口节点的后代。
- 优先来自 HDC 的 `previousPageId`、Function Tree 功能区和 pgvector Top-K。

### 5.3 AI 批量复核

仅发送灰区候选，单批 20 至 50 个入口。请求包含脱敏后的标题、URL、功能区、进入控件、截图摘要和 Top-K 候选，不发送无关原始数据。

AI 只能输出候选排序和理由，不能直接写数据库。后端必须校验返回的 `parentPageId` 是否来自候选集合。

## 6. 日报查询实现

### 6.1 新增 URL

事实表存在时直接查询：

```sql
select app_name, count(*) as new_url_count
from app_url_coverage_fact
where is_baseline = false
  and first_coverage_time >= :period_start
  and first_coverage_time < :period_end
group by app_name;
```

如果只保留事件表，必须先对全部历史求最早时间，再筛选报告区间：

```sql
with first_cover as (
  select app_name, page_url, min(covered_at) as first_coverage_time
  from page_coverage_event
  group by app_name, page_url
)
select app_name, count(*)
from first_cover
where first_coverage_time >= :period_start
  and first_coverage_time < :period_end
group by app_name;
```

禁止先按报告区间过滤事件再执行 `min`，否则同一 URL 在后续产生节点事件时会被重复统计。

### 6.2 当前覆盖与游离

按 APP + URL 聚合，存在任一有效节点即判为已覆盖：

```sql
with url_status as (
  select
    app_name,
    page_url,
    bool_or(embedding_text is not null and trim(embedding_text) <> '') as covered
  from app_page_graph
  where page_url is not null and trim(page_url) <> ''
  group by app_name, page_url
)
select
  app_name,
  count(*) filter (where covered) as covered_url_count,
  count(*) filter (where not covered) as orphan_url_count
from url_status
group by app_name;
```

节点数另按有效页面记录行数统计，不能与 URL 数混用。上述 SQL 对应生产 `app_page_graph` 适配器；独立 Java 模型须按标准页面去重后统计节点，不能把多张图片实例误计为多个功能节点。

### 6.3 覆盖率封顶

每个 APP 参与总体汇总的已覆盖数为：

```text
effectiveCovered = min(coveredUrlCount, totalUrlCount)
```

表格可以显示实际覆盖数，但覆盖率最多为 100%，并提示分母或口径可能需要核查。

分母来自已发布 `reportAppTarget`；高频覆盖通过当前有效 URL 集合与 `reportPriorityUrl` 精确求交集。总体覆盖率用分子之和除以分母之和，不平均各 APP 百分比。分母缺失或为 0 的应用不进入比例汇总，返回排除原因。去 0 口径还要求已覆盖数大于 0。封顶并不证明覆盖到了基准中的每条 URL，只有总数时界面需说明这是数量口径。

## 7. 前端开发

### 7.1 路由

建议增加：

```text
/app-graph/orphan-review
/app-graph/report/daily
```

### 7.2 游离复核工作台

使用 Ant Design Vue：

- `a-table` 展示游离子图和候选父节点。
- `a-checkbox` 多选。
- `a-progress` 或 `a-tag` 表示置信度等级。
- `a-drawer` 展示截图、HDC 路径、Function Tree 和候选证据。
- `a-select` 搜索并人工指定父节点。
- `a-popconfirm` 确认批量归并和撤销。
- 对请求增加 loading、防重复提交和逐条结果提示。

G6 图谱表现：

- 游离子图使用独立 Combo。
- 入口候选节点使用橙色边框。
- `PROVISIONAL_ENTRY` 使用灰色虚线。
- 自动确认使用蓝色标签，人工确认使用绿色标签。
- 选择表格行时聚焦对应子图，不重新执行全量布局。

### 7.3 日报页面

使用现有 Vben Admin 页面间距和 Ant Design Vue 原生组件：

- 顶部 `a-segmented` 切换早报/晚报和 TOP/TGI。
- `a-card`、`a-statistic` 展示五项汇总。
- ECharts 展示数量趋势和覆盖率趋势。
- `a-table` 展示 11 列 APP 指标。
- URL 明细使用 `a-drawer` 和虚拟滚动表格。
- 点击 URL 调用图谱定位方法；多个页面节点匹配同一 URL 时显示候选列表。
- 增加“基准维护”入口，支持 APP 分组、全量 URL 数量、高频 URL 清单维护、校验和发布。
- 展示当前已发布基准版本、来源及更新时间；过往报告使用自身快照，实时图谱定位不到历史节点时说明节点已变更或删除。

## 8. 前端状态机

批量归并：

```text
IDLE
-> LOADING_CANDIDATES
-> REVIEWING
-> PREVIEWING
-> SUBMITTING
-> RELOADING_GRAPH
-> VERIFYING
-> SUCCESS | PARTIAL_SUCCESS | FAILED
```

按钮在 `SUBMITTING` 到 `VERIFYING` 期间禁用。只有重新查询验证通过的记录才从待复核列表移除。

## 9. 脚本改造

HDC 脚本每一步必须维护稳定的探索上下文：

```text
sessionId
stepIndex
currentPageId / currentPageFingerprint
previousPageId / previousPageFingerprint
entryWidget
actionType
beforeScreenshot
afterScreenshot
currentUrl
timestamp
```

如果页面尚未获得后端 `pageId`，先使用客户端临时 ID，上传后由后端返回映射。脚本不能只上传最终截图，否则后台无法判断新区域入口。

## 10. 并发与一致性

- 使用 `graphVersion` 做乐观锁。
- 批量操作先预检，再在事务内更新边和结构事件。
- `app_url_coverage_fact` 使用 `appName + pageUrlHash` 唯一约束和 `on conflict do nothing` 保证首次时间不被刷新。
- 图谱重新查询后执行可达性检查。
- 大批量操作返回逐条结果，并生成 `batchId`。
- 撤销使用审计快照恢复，不直接猜测旧父节点。

## 11. 测试清单

### 11.1 游离归并

- HDC 有明确 `previousPageId` 时自动生成唯一候选。
- B 到 E 子图只要求处理 B 的入口。
- 找不到入口时生成不可执行临时边。
- 同 APP 校验、自引用和环路校验有效。
- 部分批量归并失败时返回逐条结果。
- 重复提交保持幂等。
- 并入后节点从真实根可达且从 `orphans` 消失。
- 撤销后父边、状态和版本正确恢复。

### 11.2 首次覆盖

- 新有效页面只登记一次 URL 首次覆盖。
- 游离转覆盖正确登记。
- 修改标题、图片、动作不刷新首次时间。
- 同 URL 多页面节点不重复计入日报。
- 并发写入不会生成两个 URL 事实记录。
- 历史基线不计入上线日新增。

### 11.3 日报

- 北京时间早报、晚报区间边界不重复。
- 同一期新修订仍只展示一个最新趋势点，原报告内容可追溯。
- URL 同时存在有效和无效节点时判为已覆盖。
- 覆盖率封顶 100%，表格保留实际数量。
- TOP/TGI 分组、应用映射和基准清单冲突时给出数据质量提示。
- 无本地 Excel 文件时仍可维护基准和生成日报。
- URL 从图片 A 转到 B、跨节点重新关联、重复上传时，已有首次覆盖时间不变。
- 基准修改后，新报告使用新版本，历史报告分母和明细保持不变。
- HTML 在无外网环境下可打开图表和明细。

## 12. 实施顺序

### 阶段 1：数据可信

1. 确认生产 `embedding_text` 映射和所有写入入口，增加探索步骤、覆盖事件、URL 覆盖事实和结构事件表。
2. 在受控写入暂停窗口内对历史有效 APP + URL 初始化基线，回填节点状态并核对总数。
3. 部署并验证所有写入入口的幂等覆盖登记后恢复写入，记录启用时刻；不能暂停时另行设计增量切换与对账，不能跳过。

### 阶段 2：降低人工成本

1. 实现游离子图识别和父节点候选。
2. 实现预检、批量归并、重查验证和撤销。
3. 上线批量复核工作台。

### 阶段 3：新功能区治理

1. 引入逻辑 `APP_ROOT`。
2. 实现临时入口边和不可执行约束。
3. 支持整个子图定位、拖拽和入口补录。

### 阶段 4：建设日报

1. 实现 TOP/TGI、全量 URL 数量和高频 URL 清单的后台维护、同步及版本发布。
2. 实现日报聚合、趋势快照和 URL 明细。
3. 完成图谱定位和离线 HTML 导出。

## 13. 完成定义

本功能完成需要满足：

- 后端能够自动识别游离子图并返回 Top-K 父节点候选。
- 高置信度结果可批量归并，低置信度结果保留人工控制。
- 新功能区不会生成虚假的可执行 root 边。
- 所有结构修改可审计、可验证、可撤销。
- `firstCoverageTime` 在 APP + URL 粒度幂等且不可被普通更新刷新。
- 日报能够稳定生成五项卡片、两张趋势图、APP 表格和 URL 明细。
- URL 明细能够定位回图谱节点和对应测试证据。
