# Function Tree 与页面图谱结合方案

## 1. 输入格式

厂商交付两个 JSON 文件。

元数据：

```json
{
  "appName": "QQ",
  "appVersion": "9.1.0",
  "vendorVersion": "2026.07"
}
```

功能树：

```json
{
  "name": "消息",
  "level": 1,
  "features": [
    {
      "sources": "官方功能说明",
      "DESC": "进入会话并发送消息",
      "type": "navigation",
      "gap": false,
      "check": "页面与动作均可执行",
      "fault": ""
    }
  ],
  "children": []
}
```

示例位于 `examples/function-tree/`。

## 2. 导入转换

```http
POST /api/functionTree/import
Content-Type: multipart/form-data

metadataFile = app-metadata.json
treeFile = function-tree.json
source = vendor
vendorVersion = 2026.07
```

后端执行：

1. 根据 `appName` 找到图谱应用。
2. 使用 `appId + source + vendorVersion` 保证版本幂等。
3. 递归展开任意层级的 `children`。
4. 生成稳定 `vendorFunctionId`、父节点、完整路径和展示顺序。
5. 保留原始 `features` 与原始节点 JSON。
6. 从 `DESC/type/check/gap/fault` 推断期望动作层和自动化限制。
7. 新版本激活时将同来源旧版本标记为 `archived`。

索引和分数不是在导入时固化。导入阶段只生成可检索的 `matchRules`；运行匹配时根据当前图谱重新建内存倒排索引并计算分数，避免页面增量更新后继续使用过期分数。

## 3. 四层动作如何匹配

| Function Feature 语义 | 首选动作层 | 示例 |
|---|---|---|
| 弹窗、面板、抽屉 | `popupAction` | 打开筛选面板 |
| 点赞、收藏、关注、开关 | `stateAction` | 收藏商品 |
| 相机、系统、SDK、小程序 | `externalAction` | 调用系统相册 |
| 进入、跳转、查看详情 | `pageNaviAction` | 点击消息列表 |

功能点可能绑定：

- 一个或多个页面：该功能在哪里呈现。
- 一个或多个动作：该功能如何进入、操作或退出。
- 页面和动作同时绑定：最完整的证据链。

`capabilityRole`：

- `entry`：进入功能，通常来自 `pageNaviAction`。
- `behavior`：功能内典型行为，通常来自 `popupAction/stateAction`。
- `exit`：外部跳转或系统能力，通常来自 `externalAction`。

## 4. 匹配运行

先估算：

```http
POST /api/functionMatch/estimate
Content-Type: application/json

{
  "catalogId": "uuid",
  "topK": 5,
  "minScore": 0.45,
  "autoConfirmScore": 0.85,
  "enableAiReview": false,
  "aiBatchSize": 40
}
```

估算返回全量笛卡尔积、本地候选比较量和预计 AI 批次数。

正式运行：

```http
POST /api/functionMatch/run
```

### 4.1 倒排索引

对 900 个 Function 节点的以下文本分词：

- `name`
- `description`
- `functionPath`
- `matchRules`

英文使用词 token，中文除完整连续文本外增加双字 token。最终得到：

```text
token -> functionId set
```

对每个页面或动作，只比较共享 token 的功能候选。没有词汇交集时只回退到少量顶层功能，不做 400 × 900 的全量比较。

### 4.2 页面评分

```text
pageScore =
  tokenCoverage × 0.55
  + trigramSimilarity × 0.15
  + directFunctionNameHit × 0.30
```

### 4.3 动作评分

```text
actionScore =
  actionSemanticSimilarity × 0.40
  + sourcePageSimilarity × 0.25
  + actionLayerCompatibility × 0.20
  + directFunctionNameHit × 0.15
```

动作层不兼容时，最终分数乘 `0.6`。

### 4.4 阈值

- `< minScore`：不保存。
- `minScore ~ autoConfirmScore`：`suggested`，需要人工复核。
- `>= autoConfirmScore`：`confirmed`。
- 每个目标最多保留 `topK` 个候选。

当前 Java 基线实现本地评分并持久化 AI 待复核边界。接入模型时只将模糊候选按 `aiBatchSize` 批量发送，不逐对调用 AI。

## 5. 400 页面与 900 功能点的调用量

最差笛卡尔积为：

```text
400 × 900 = 360,000 页面比较
```

这不是实际执行策略。倒排索引通常让单页面候选降到几十个，本地完成第一轮；AI 只处理模糊候选并批量发送。

例如 400 页面和 1,200 动作中约 20% 进入模糊区，共 320 个候选，批大小 40：

```text
ceil(320 / 40) = 8 次 AI 调用
```

实际值由 `/api/functionMatch/estimate` 根据当前库计算。要求越少调用，应优先：

1. 人工补齐高质量页面标题和动作语义。
2. 提高本地同义词规则覆盖。
3. 只对业务关注的 `pageIds/actionIds` 增量匹配。
4. 增大 AI 批大小，不进行逐候选调用。
5. 已人工确认的绑定跨运行复用。

## 6. 人工复核

```http
POST /api/functionBindings/{bindingId}/review

{
  "targetType": "action",
  "reviewStatus": "confirmed",
  "operatorNote": "人工验证为消息发送入口"
}
```

也可通过 `/api/functionBindings/manual` 手工建立 1.0 分确认绑定。

匹配结果用于：

- 前端 Function Tree 覆盖率展示。
- 功能点高亮其页面和动作。
- 用例按厂商功能归属分组。
- 缺失功能与自动化受限功能统计。

