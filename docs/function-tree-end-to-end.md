# Function Tree 端到端落地方案

## 1. 目标与六条落地链路

Function Tree 是厂商声明的“应用应具备哪些功能”，页面图谱是脚本和 AI 实际发现的“功能出现在哪里”，四层动作是“用户如何触发或使用该功能”。

三者通过 Binding 多对多关联，不把厂商功能树强行改造成页面树：

```text
Vendor Function Tree
        |
        v
Function Page/Action Binding
      /                 \
Page Graph          Four-layer Action
```

当前实现覆盖：

1. 导入厂商 metadata JSON 和任意层级 Function Tree JSON。
2. 归一化、版本幂等、功能节点落库。
3. 页面和四层动作在后端本地召回、评分和匹配。
4. 高置信候选自动确认，低置信、近分候选进入复核。
5. 前端展示覆盖概览、候选证据、页面定位和动作功能标签。
6. 人工批量复核、历史确认继承，并向用例生成提供可信绑定。

## 2. 输入与导入

metadata JSON：

```json
{
  "appName": "QQ",
  "appVersion": "9.1.0",
  "vendorVersion": "2026.07"
}
```

Function Tree JSON：

```json
{
  "name": "消息",
  "level": 1,
  "features": [
    {
      "sources": "QQ 官方",
      "DESC": "查看消息和会话列表",
      "type": "navigation",
      "gap": false,
      "check": "可进入会话页面",
      "fault": ""
    }
  ],
  "children": [
    {
      "name": "单聊",
      "level": 2,
      "features": [],
      "children": []
    }
  ]
}
```

```http
POST /appGraph/api/functionTree/import
Content-Type: multipart/form-data

metadataFile=<metadata.json>
treeFile=<function-tree.json>
source=qq-official
vendorVersion=2026.07
```

导入规则：

- 通过 `appName` 关联 `apps`。
- 通过 `appId + source + vendorVersion` 保证幂等。
- 递归处理 `children`，不限制层级。
- 保存稳定厂商 ID、父节点、层级、完整路径、展示顺序和原始 JSON。
- 从 `DESC/type/check/gap/fault/sources` 提取匹配规则、期望动作层和自动化限制。
- 新目录激活时归档同来源旧版本，前端只使用最新激活版本。
- 导入阶段不固化页面分数，运行匹配时基于最新图谱重新计算。

主要表：

- `function_catalogs`
- `vendor_functions`
- `function_match_runs`
- `function_page_bindings`
- `function_action_bindings`

## 3. 本地匹配引擎

运行时对功能名称、描述、祖先路径和规则分词，构建 `token -> functionId set` 倒排索引。页面通过标题、描述、URL、页面类型和图谱上下文召回候选；动作通过动作名称、描述、来源页面和动作层召回候选。

没有共享语义信号的动作不会仅凭“动作层兼容”建立映射。因此 400 页面 × 900 功能点不会执行 360,000 次 AI 调用。第一阶段完全在 Java 本地完成，AI 只需批量复核筛出的模糊候选。

页面评分：

```text
rawPageScore =
  tokenCoverage * 0.55
  + sequenceSimilarity * 0.15
  + directNameHit * 0.30

pageScore = min(1, rawPageScore / 0.55)
```

动作评分：

```text
rawActionScore =
  actionSemanticSimilarity * 0.40
  + sourcePageSimilarity * 0.25
  + layerCompatibility * 0.20
  + directNameHit * 0.15

actionScore = min(1, rawActionScore / 0.80)
```

动作必须同时满足动作层兼容，以及动作自身语义或来源页面上下文命中。每条 Binding 保存原始分、校准方式、命中词、页面/动作相似度和动作层兼容性。

## 4. 四层动作与功能

| 动作层 | Function Tree 语义 | 图谱表现 |
|---|---|---|
| `pageNaviAction` | 进入、跳转、详情、列表 | 关联页面边和入口控件 |
| `stateAction` | 点赞、收藏、关注、开关 | 保留在节点内部，不创建虚假页面 |
| `popupAction` | 弹窗、菜单、抽屉、半屏 | 标记来源页面和弹层行为 |
| `externalAction` | 相机、系统、SDK、小程序 | 标记外部边界和自动化风险 |

已确认动作 Binding 会在前端节点 Inspector 的对应动作旁显示 Function 标签。

## 5. 自动决策策略

默认策略版本为 `function-match-v2`：

| 目标 | 自动确认 | 进入复核 |
|---|---:|---:|
| 页面 | `score >= 0.85` | `score >= 0.55` |
| 动作 | `score >= 0.90` | `score >= 0.65` |

最佳候选与第二候选的分差还必须不小于 `0.12`。分数够高但候选过近时标记 `conflicted`，不会自动确认。

| 状态 | 含义 |
|---|---|
| `autoConfirmed` | 策略自动确认 |
| `humanConfirmed` | 人工确认 |
| `inherited` | 从上一完成运行继承可信绑定 |
| `pendingReview` | 达到复核阈值 |
| `conflicted` | 最佳与次佳候选过近 |
| `rejected` | 人工拒绝 |

人工确认优先级高于策略判断，后续运行不会静默覆盖人工结论。

## 6. API 流程

估算和运行：

```http
POST /appGraph/api/functionMatch/estimate
POST /appGraph/api/functionMatch/run
```

运行请求：

```json
{
  "catalogId": "uuid",
  "topK": 5,
  "autoConfirmScore": 0.85,
  "reviewScore": 0.55,
  "actionAutoConfirmScore": 0.90,
  "actionReviewScore": 0.65,
  "minScoreMargin": 0.12,
  "enableInheritance": true,
  "enableAiReview": false,
  "aiBatchSize": 40,
  "pageIds": [],
  "actionIds": []
}
```

`pageIds/actionIds` 为空表示全量运行；增量探索后只传新增或语义变化的 ID。

查询：

```http
GET /appGraph/api/functionTree/catalogs?appName=QQ
GET /appGraph/api/functionTree/catalogs/{catalogId}
GET /appGraph/api/functionMatch/runs?catalogId={catalogId}
GET /appGraph/api/functionMatch/runs/{runId}
GET /appGraph/api/functionMatch/runs/{runId}/bindings
GET /appGraph/api/functionMatch/runs/{runId}/coverage
```

单条复核：

```http
POST /appGraph/api/functionBindings/{bindingId}/review

{
  "targetType": "action",
  "reviewStatus": "humanConfirmed",
  "operatorNote": "已通过截图和真机动作复核",
  "reviewedBy": "demo-user"
}
```

批量复核：

```http
POST /appGraph/api/functionBindings/reviewBatch

{
  "targetType": "page",
  "bindingIds": ["uuid-1", "uuid-2"],
  "reviewStatus": "rejected",
  "operatorNote": "批量排除同类误命中",
  "reviewedBy": "demo-user"
}
```

人工建立映射：

```http
POST /appGraph/api/functionBindings/manual

{
  "runId": "uuid",
  "functionId": "uuid",
  "targetType": "action",
  "targetId": "uuid",
  "capabilityRole": "behavior",
  "operatorNote": "人工建立功能归属"
}
```

## 7. 前端闭环

1. 用户选择应用，前端查询最新 Function Catalog。
2. 没有目录时点击“导入”，选择 metadata 和 tree 两个 JSON。
3. 导入成功后自动运行匹配并查询最新完成的 Run。
4. 左侧“官方功能”显示树、页面数、动作数和决策统计。
5. 点击功能，高亮关联图谱节点，右侧进入 Function Review。
6. 工作台按“需处理 / 全部 / 已确认”筛选候选。
7. 点击候选定位来源页面，支持单条和整组确认/拒绝。
8. 已确认动作在节点四层动作列表旁显示厂商 Function 标签。

功能高亮是语义覆盖层，不改变页面图谱原有父子结构。

## 8. 可信覆盖与用例生成

只有 `autoConfirmed/humanConfirmed/inherited/confirmed` 进入用例生成。`pendingReview/conflicted/rejected` 不参与可信用例。

```text
Function
-> trusted Page Binding
-> trusted Action Binding
-> graph path
-> executable steps
-> device execution
-> performance and functional result
```

页面跳转功能生成根到目标页的路径用例；状态、弹层和外部动作生成以动作所在页为起点的过程采集用例。

## 9. 增量与规模化

- 新页面或动作入库后，只对变化的 `pageIds/actionIds` 运行增量匹配。
- 新 Run 优先继承历史可信绑定，避免每个应用反复人工复核。
- 前端默认只要求处理 `pendingReview/conflicted`。
- AI 采用候选批次调用，不按 Function × Page 笛卡尔积调用。
- 支付、账号安全、生物识别等高风险功能可扩展为强制人工复核名单。

## 10. QQ 演示结果

使用 2026-07-29 QQ 演示数据运行 `function-match-v2`：

```text
Function: 5
Page: 300
Action: 302
Page Binding: 67
Action Binding: 8
Auto Confirmed: 16
Pending Review: 47
Conflicted: 12
```

旧策略曾产生 906 条动作候选；增加语义门槛、来源页面上下文、分差判断和最佳候选约束后，动作候选收敛到 8 条。

## 11. 验收标准

- 两个 JSON 可通过前端上传并在后端幂等落库。
- 任意层级 `children` 可正确查询和展示。
- 匹配在后端执行，前端不自行制造正式结果。
- 高置信且分差足够的候选自动确认。
- 冲突和待复核候选不进入可信用例。
- 点击 Function 可以高亮并定位页面。
- 已确认四层动作显示 Function 标签。
- 单条和批量复核可落库并立即回查。
- 新运行可继承历史可信结论。
- 全量和增量匹配均可通过 API 执行。
