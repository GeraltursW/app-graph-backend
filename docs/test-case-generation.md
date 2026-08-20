# 图谱与四层动作驱动的用例生成

## 1. 两类用例

### 1.1 终点采集用例

目标是全量覆盖每一条 `root -> leaf` 路径。

```text
首页 --消息--> 消息列表 --会话--> 聊天页
```

每一条叶子路径生成两条独立的首批用例。脚本按边上的控件说明依次到达终点，中间页面只做页面校验，不执行压力动作。所有典型操作和性能采集都集中在最终页面：

- `scrollLoop`：终点页面向上连续滑动 4 次，再向下回滚 3 次。
- `backForwardLoop`：从终点返回上一页，再通过原入口重新进入，连续执行 3 次。

该策略强调：

- 路径不能抽样。
- 同一终点的不同到达路径分别保留。
- 路径中任一步失败，终点数据不可视为有效。
- 采集窗口从终点页面稳定后开始，覆盖完整终点动作，路径恢复耗时不计入结果。
- 环或重复边通过路径 visited 集合阻断，避免无限生成。

### 1.2 过程采集用例

从任意页面恢复现场，执行一个或多个典型动作，并在动作过程中持续采集。

示例：

```text
视频详情页 + 点赞
视频详情页 + 滑动评论 + 长按评论
消息列表 + 下拉刷新 + 搜索
```

用户可以配置：

- 参与组合的动作层。
- 组合深度 1~3。
- 每个页面最多生成多少组合。
- 要采集的性能指标。

## 2. 生成接口

```http
POST /api/testCases/generate
Content-Type: application/json

{
  "appName": "QQ",
  "functionMatchRunId": "uuid-or-null",
  "modes": ["terminalPath", "processScenario"],
  "actionLayers": [
    "popupAction",
    "stateAction",
    "externalAction",
    "pageNaviAction"
  ],
  "scenarioDepth": 2,
  "maxScenariosPerPage": 12,
  "collectionMetrics": ["power", "cpu", "memory", "fps", "network"],
  "includeAutomationLimited": false
}
```

生成内容持久化在：

- `test_case_batches`：一次生成任务、配置与统计。
- `test_cases`：单条用例、步骤、采集策略、期望结果。

## 3. 终点路径步骤

```json
[
  {
    "order": 0,
    "type": "resetApp",
    "description": "关闭后台并重新进入应用"
  },
  {
    "order": 1,
    "type": "assertPage",
    "pageId": "home-hash",
    "pageTitle": "首页"
  },
  {
    "order": 2,
    "type": "navigate",
    "pageId": "message-hash",
    "actionType": "tap",
    "control": "消息列表"
  },
  {
    "order": 3,
    "type": "performAction",
    "actionType": "swipe",
    "pageId": "message-hash",
    "direction": "up",
    "repeat": 4,
    "collectDuringAction": true
  },
  {
    "order": 4,
    "type": "performAction",
    "actionType": "swipe",
    "pageId": "message-hash",
    "direction": "down",
    "repeat": 3,
    "collectDuringAction": true
  }
]
```

采集策略：

```json
{
  "phase": "terminalActions",
  "terminalPattern": "scrollLoop",
  "metrics": ["power", "cpu", "memory"],
  "startAfterFinalPageStable": true
}
```

## 4. 过程动作步骤

```json
[
  {
    "order": 0,
    "type": "restorePage",
    "pageId": "video-detail-hash",
    "strategy": "shortestKnownPath"
  },
  {
    "order": 1,
    "type": "performAction",
    "actionLayer": "stateAction",
    "actionType": "tap",
    "semanticName": "点赞",
    "target": {},
    "parameters": {},
    "expectedEffect": {
      "liked": true
    },
    "collectDuringAction": true
  }
]
```

## 5. Function Tree 如何参与

传入 `functionMatchRunId` 后，生成器优先读取已确认绑定：

1. 过程动作绑定到 Function 节点。
2. 没有动作绑定时，回退到页面绑定。
3. 用例保存 `functionId`，前端可以按厂商功能树聚合覆盖率。

因此覆盖率可以同时回答：

- 厂商声明的 900 个功能点中，哪些有页面证据。
- 哪些有可执行动作证据。
- 哪些已经生成用例。
- 哪些执行成功并产生性能数据。
- 哪些因为人脸、支付、权限等原因受限。

## 6. 脚本任务契约

脚本通过：

```http
GET /api/testCases/{testCaseId}/scriptTask
```

获取统一任务：

```json
{
  "status": "success",
  "task": {
    "taskId": "uuid",
    "appName": "QQ",
    "packageName": "com.tencent.mobileqq",
    "caseType": "processScenario",
    "resetPolicy": {
      "forceStopBeforeRun": true,
      "returnToHostAppFromMiniProgram": true,
      "dismissPopupAndAds": true,
      "forbidLogout": true
    },
    "steps": [],
    "collectionPolicy": {},
    "expectedResult": {}
  }
}
```

设备执行器需要准备：

1. 页面恢复：按已知最短路径进入 `startPageId`。
2. AI 识图：定位目标控件并输出置信度与坐标。
3. 动作执行：tap、swipe、longPress、input、back、deeplink。
4. 状态验证：截图、OCR、结构 hash、URL/Activity。
5. 性能采集：统一时间轴记录功耗、CPU、内存、FPS 和网络。
6. 结果回传：每一步状态、实际页面、截图、性能区间、失败原因。

## 7. 组合爆炸控制

若一个页面有 20 个动作，深度 3 的排列可达到数千条。工程上使用：

- `maxScenariosPerPage` 硬上限。
- 优先高置信、人工确认和 Function Tree 已绑定动作。
- 不重复同一动作。
- `pageNaviAction/externalAction` 后默认恢复起始页面再继续下一组合。
- 风险动作黑名单：注销、删除账号、支付确认、隐私授权。
- 历史覆盖去重：相同动作 fingerprint + 参数域 + App 版本不重复生成。

当前代码实现基础组合与页面上限。优先级排序、参数域展开和历史覆盖去重是下一阶段增强点。
