# 游离子图归并与图谱建设日报开发文档

## 1. 开发范围

本文定义后端、前端、脚本和数据库的实施契约。接口 JSON 统一使用 camelCase；PostgreSQL 列可以使用 snake_case，由 ORM 或 MyBatis 映射。

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

```sql
alter table app_page_graph
  add column if not exists structure_status varchar(24) not null default 'ORPHAN',
  add column if not exists coverage_status varchar(24) not null default 'UNCOVERED',
  add column if not exists resolved_at timestamptz,
  add column if not exists resolved_by varchar(128),
  add column if not exists merge_source varchar(32),
  add column if not exists graph_version bigint not null default 0;
```

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
  event_type varchar(32) not null,
  source_type varchar(32) not null,
  covered_at timestamptz not null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists idx_page_coverage_event_url_time
  on page_coverage_event(app_name, page_url, covered_at);

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
  if old.embeddingText 无效 and new.embeddingText 有效:
    insert pageCoverageEvent
    insert appUrlCoverageFact
      on conflict (appName, pageUrlHash) do nothing
commit
```

### 2.6 日报快照

```sql
create table if not exists graph_daily_report (
  report_id varchar(64) primary key,
  report_type varchar(16) not null,
  period_start timestamptz not null,
  period_end timestamptz not null,
  generated_at timestamptz not null,
  timezone varchar(64) not null default 'Asia/Shanghai',
  summary jsonb not null,
  app_metrics jsonb not null,
  unique (report_type, period_start, period_end)
);

create index if not exists idx_daily_report_period
  on graph_daily_report(period_end desc);
```

同一期重复生成时更新快照，不创建重复趋势点。

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

## 5. 匹配服务实现

### 5.1 子图识别

1. 获取所有从真实根节点不可达的页面。
2. 忽略连接到 `APP_ROOT` 的临时入口边。
3. 在游离节点诱导子图中计算弱连通分量。
4. 入度为 0 的节点作为入口候选。
5. 多入口子图允许拆分或标记 `MULTI_ENTRY_REVIEW`。

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

节点数另按有效页面记录行数统计，不能与 URL 数混用。

### 6.3 覆盖率封顶

每个 APP 参与总体汇总的已覆盖数为：

```text
effectiveCovered = min(coveredUrlCount, totalUrlCount)
```

表格可以显示实际覆盖数，但覆盖率最多为 100%，并提示分母或口径可能需要核查。

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
- 同一期重复生成只更新一个趋势点。
- URL 同时存在有效和无效节点时判为已覆盖。
- 覆盖率封顶 100%，表格保留实际数量。
- TOP/TGI 分组和 Excel 名称无法匹配时给出数据质量提示。
- HTML 在无外网环境下可打开图表和明细。

## 12. 实施顺序

### 阶段 1：数据可信

1. 增加探索步骤、覆盖事件、URL 覆盖事实和结构事件表。
2. 改造上传和更新节点事务，正确登记 `firstCoverageTime`。
3. 初始化历史基线。

### 阶段 2：降低人工成本

1. 实现游离子图识别和父节点候选。
2. 实现预检、批量归并、重查验证和撤销。
3. 上线批量复核工作台。

### 阶段 3：新功能区治理

1. 引入逻辑 `APP_ROOT`。
2. 实现临时入口边和不可执行约束。
3. 支持整个子图定位、拖拽和入口补录。

### 阶段 4：建设日报

1. 导入 TOP/TGI、全量 URL 和高频 URL 配置。
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
