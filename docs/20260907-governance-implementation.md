# 图谱治理与日报：实施、接口和验证

日期：2026-09-07。以下为已实现的独立 Java 后端，不将设计稿中尚未落地的接口列为可用接口。

## 1. 数据与事务

Flyway `V6__coverage_and_graph_governance.sql`：

| 表/字段 | 用途 |
| --- | --- |
| `page_instances.embedding_text` | AI 对单截图的实际归类文本；导入接受 `embeddingText` 和 `embedding_text`，查询/编辑使用 camelCase |
| `apps.graph_version` | 节点、边、真实入口修改时推进，用于并入前的乐观校验 |
| `graph_entry_points` | 有证据的真实启动入口，决定图谱与用例生成的可达范围 |
| `graph_pending_entries` | 待确认区域元信息，不生成可执行假边 |
| `graph_operation_batches` | 幂等请求、响应、新增边、撤销状态审计 |
| `app_url_coverage_fact` | 主键 APP + SHA-256(URL)，保留原 URL 校验，不用长 URL 建 B-tree 键 |
| `page_coverage_event` | 有效覆盖登记事件，和页面写入处于同一事务 |
| `report_baselines` | 后台维护的分组、总 URL、高频 URL、来源与说明的不可变版本 |
| `graph_daily_reports` | 生成区间、基准版本、请求幂等键与完整 JSON 快照 |

覆盖条件：APP 有效、URL 非空、任一页面实例 `embedding_text` 非 NULL 且去空白后非空。不可用 `ai_summary/pageText` 替代。图谱结构可达性不作为 URL 已覆盖的必要条件，因此有截图证据但待接入的区域仍能贡献覆盖率。

有效页面写入触发器 `ON CONFLICT DO NOTHING` 保持 APP + URL 首次登记时间不可变。更换图片、URL 关联节点或重复上传不刷新；改变 URL 字符串则是新身份。事实不随页面删除清除，之后再出现不会重复新增。当前存量覆盖数仍按当前实例计算。

V6 将历史有效 URL 登记为 `is_baseline=true`，首次时间 NULL，不计作本期新增。历史非 orphan 且无入边页面沿用原主树根作为入口基线，证据标注 `Historical root baseline`，上线前应核对这些根；新导入页面不会据入度自动确认为 APP_HOME，需要人工入口确认。

所有新建/更新的 `page_edges` 经过数据库递归可达性检查与 APP 行锁。允许 A→B→D、A→C→D，拒绝 D→A 和 A→A。并入服务同时锁 APP、检查图版本、检查父节点可达并要求真实控件；1–500 条整个事务成功或全部失败。撤销只删除该批新增边，图版本变化则拒绝，避免覆盖后续编辑。

## 2. 可用接口

所有 JSON 字段使用 camelCase。POST 使用 `Content-Type: application/json`，`updateNode` 除外。

| 方法 | 路径 | 请求与用途 |
| --- | --- | --- |
| GET | `/appGraph/queryAppGraph/{appName}` | 原树/游离树 + 独立 `edges` + `graphVersion`；节点含 `embeddingText` |
| POST | `/appGraph/updateNode` | 原 multipart 字段增加可选 `embeddingText`；省略保持，空字符串清空 |
| GET | `/appGraph/orphans/workbench?appName=QQ` | `nodes/entries/graphVersion/unresolvedCount/unrepresentedCount` |
| POST | `/appGraph/orphans/batchMerge` | `{requestId,appName,graphVersion,items:[{pageId,newParentId,widgetDescription}]}` |
| POST | `/appGraph/orphans/rollbackBatch` | `{appName,batchId,graphVersion}` |
| POST | `/appGraph/orphans/createProvisionalEntry` | `{appName,pageId,graphVersion,reason}`，只记元信息 |
| POST | `/appGraph/orphans/registerEntry` | `{appName,pageId,graphVersion,entryKind,evidence}` |
| GET | `/appGraph/reports/baselines/current` | 最新 `{revision,source,apps,publishedAt}`；未配置 revision=0 |
| POST | `/appGraph/reports/baselines/publish` | 见下面基准示例，提交完整 APP 配置集合 |
| POST | `/appGraph/reports/generate` | `{requestId,date:"2026-09-06",reportType:"EVENING"}` |
| GET | `/appGraph/reports/daily` | 每日期/早晚类型最新一份报告，最多 60 份 |
| GET | `/appGraph/reports/trends` | 当前和 daily 使用同一快照列表 |
| GET | `/appGraph/reports/{reportId}` | 精确读取指定历史快照 |

`requestId/batchId` 使用 UUID；重试必须复用原请求 ID 和内容。图版本或基准版本冲突返回 409，客户端刷新后重新人工确认。不存在返回 404；无效输入返回 400。不要在失败后未经刷新自动换 ID 重放写请求。

```json
{
  "requestId": "4c4af915-c129-4f3b-8617-506656bd02bd",
  "expectedRevision": 0,
  "source": "监控平台2026-09-07基准",
  "apps": [{
    "appName": "QQ",
    "appGroups": ["TOP", "TGI"],
    "totalUrlCount": 1100,
    "priorityUrls": ["mqq://message/1"],
    "specialNote": "需登录"
  }]
}
```

APP 必须预先存在且名称能唯一识别。总量可为 null 表示未知，不可假填 0。高频 URL 去重且精确匹配，不能仅按数量估计高频交集。基准发布创建新 revision，不修改旧报告。

入口类型：`APP_HOME/DEEPLINK/NOTIFICATION/SYSTEM_INTENT/EXTERNAL_APP`。证据必填但当前不由 HDC 自动验证。工作台入口是未从真实入口可达子图的零入度节点；候选只来自采集原文 `previousPageId`/`previous_page_id`，`autoEligible=false`。没有可信父节点时先保留待确认状态。

独立关系 `edges[]`：`{id,fromPageId,toPageId,widgetDescription,label,actionType}`。共享后继只展开一次，所有实际边单独保留，避免树序列化指数增长与控件名称覆盖。`moveNode` 对多入边节点拒绝旧式整体替换，尚无逐边编辑 UI。

## 3. 日报口径

早报为北京时间 `[前日20:00,当日08:00)`，晚报为 `[08:00,20:00)`。尚未结束的区间禁止生成。

`newUrlCount` 从不可变首次事实按区间统计；`coveredUrlCount/nodeCount/orphanUrlCount` 按生成时数据库快照计算，而非重建过去状态。新 URL 即使当前节点被删除，历史新增仍保留，定位提示「无当前节点」。

每 APP 输出：`appName/appGroups/totalUrlCount/specialNote/nodeCount/coveredUrlCount/orphanUrlCount/newUrlCount/priorityUrlCount/priorityCoveredCount/functionCount/urls`。节点数按有有效归类的 canonical page 去重，覆盖数按 URL 去重。`orphanUrlCount` 指有页面记录但无有效归类的 URL，不等于结构待接入数量。三方功能数来自最新导入目录，未导入为 null。

URL 明细：`pageUrl/pageIds/pageTitle/covered/priority/isNew/firstCoverageTime`；缺失节点或历史基线可能无标题/时间。报告记录 `reportId/date/reportType/generatedAt/periodStart/periodEnd/baselineRevision/baselineSource/metricPolicyVersion`。REPEATABLE READ 保证同一报告查询快照一致。生成后的报告不自动变更。

前端提供 HTML 汇总与 URL 明细导出，没有后端 Excel 依赖。目前定时器未接入，可由现有调度平台在 08:00/20:00 之后调用 generate，记录 requestId 和返回 reportId；不要将手动生成声称为自动日报任务。

## 4. 脚本与生产接入

脚本将每次识别的 `embeddingText`（兼容 `embedding_text`）、URL、截图、稳定页面身份写入 `ai_result.json` 的 pages。已有扫描导入服务负责持久化，覆盖触发器自动记账；无需脚本计算 firstCoverageTime。

提供 `previousPageId` 时应是后端查询返回的 pageId，而不是未转换的脚本临时 ID。真实入口由前端人工确认。脚本回退、关闭弹窗或重复访问可以保存在动作/执行日志中；若把形成回路的跳转写入 page_edges，本版会拒绝并回滚整批导入，不会静默删除该边。

本版持久化针对 `canonical_pages/page_instances/page_edges`。用户生产 `app_page_graph.embedding_text` 是真实字段，但独立 Java 服务还没有生产单表适配器。不能在生产库直接运行设计稿的 SQL；需要做表结构审计、字段映射、备份、历史根确认和基线回填演练。如写入服务跨库，须在实际写入服务增加事务事件/幂等消费者，当前触发器不能监听另一个数据库。

尚无 pgvector 自动召回、AI 自动并入和 HDC 入口复现校验，本版为有事务与审计保障的人工工作台。当前未配置权限，限可信内网开发环境。

## 5. 运行与验证

```powershell
cd D:\codes\app-graph-backend
$env:JAVA_HOME='D:\officework\jdk-21'
$env:DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/app_relation'
$env:DATABASE_USERNAME='postgres'
$env:DATABASE_PASSWORD='<填写本地真实密码>'
& 'D:\officework\maven\apache-maven-3.9.16\bin\mvn.cmd' '-Dmaven.repo.local=D:\officework\maven\repository' spring-boot:run
```

默认端口 8080，Flyway 自动应用迁移，数据库应具备项目原有 pgvector 支持。先备份，不将开发示例密码当作真实密码。

2026-09-07 验证：独立 PostgreSQL 18.4 测试库（127.0.0.1:55438）已应用 V1–V6；Maven package 和 11 项测试通过，其中 6 项数据库集成测试涵盖：

1. 同 URL 多图片、重复归类不刷新首次时间；换 URL 形成新事实，占位符不形成事实。
2. 允许多父 DAG，拒绝自环和回路；查询保留全部独立边。
3. 两连接并发写入 A→B 与 B→A，第二个等待后被 23514 拒绝。
4. 批量输入错误整批回滚、重试幂等、撤销只影响该批。
5. 基准发布、报告幂等、快照持久化和事实不随当前归类清空而消失。
6. 待确认区域不伪造根边；有证据的真实入口登记后，其原有子图整体变为可达。

数据库测试默认不运行，必须仅指向隔离测试库并设置 `RUN_GOVERNANCE_DB_TESTS=true` 后运行 Maven test。这组集成测试会创建测试 APP、基准及报告，不得在生产库运行。

另完成真实 HTTP 健康检查、工作台、基准、图谱和报告生成验证。用户原 5432 数据库没有修改；当前仓库提交不等于已部署生产环境。
