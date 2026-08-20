# AI 对路径用例、终点动作与测试报告的理解规范

## 1. 文档目的

本文是设备 AI、脚本执行器和后端编排服务之间的共同语义约定。目标是让 AI 明确区分“如何到达页面”和“在哪里执行测试动作”，并输出可被后端稳定处理的结构化证据。

## 2. AI 必须理解的核心规则

### 2.1 路径阶段

`Root -> Leaf` 中除最后一个页面外，全部属于路径阶段。

AI 在路径阶段只能：

- 识别当前页面。
- 关闭广告和非业务弹窗。
- 找到边关系指定的入口控件。
- 执行一次导航动作。
- 校验是否进入预期页面。

AI 不应在中间页面执行连续滑动、长按、压力点击或性能结论判断。

### 2.2 终点阶段

最后一个页面属于终点阶段。只有确认以下条件后才能开始终点动作：

- 页面标题、URL 或结构特征与预期页面一致。
- 页面主内容完成首屏加载。
- 广告、系统授权框和无关弹窗已经处理。
- 当前页面不是登录、注销、支付确认或其他禁止自动操作页面。

## 3. 两种首批终点动作

### 3.1 `scrollLoop`

动作语义：验证终点页面在连续内容浏览和回滚过程中的性能稳定性。

```text
向上滑动 4 次 -> 每次等待内容稳定 -> 向下滑动 3 次 -> 等待页面稳定
```

AI 执行要求：

- 滑动区域优先选择主内容区，避开底部导航和浮动按钮。
- 每次滑动后确认页面仍属于同一功能页面。
- 到达页面边界时记录 `boundaryReached`，不要机械重复无效滑动。
- 动态内容变化不代表页面切换，使用结构特征进行校验。

四层动作映射：

```text
scrollLoop -> stateAction
```

### 3.2 `backForwardLoop`

动作语义：验证终点页面反复退出和重新进入时的加载、资源释放与状态恢复。

```text
终点页面 -> 返回上一页 -> 找到原入口 -> 重新进入终点 -> 重复 3 次
```

AI 执行要求：

- 返回后必须校验父页面，不能连续盲目返回。
- 重新进入必须优先使用图谱边记录的 `control`。
- 每次重新进入后校验终点页面结构，而不是依赖动态文本。
- 如果入口控件暂时不可见，可以做一次短距离滚动或等待，不得探索其他入口。
- 任一轮进入错误页面，停止循环并回传失败证据。

四层动作映射：

```text
backForwardLoop -> pageNaviAction(back + edge control re-entry)
```

## 4. 输入任务结构

设备脚本从 `scriptTask` 获取任务：

```json
{
  "taskId": "uuid",
  "caseType": "terminalPath",
  "resetPolicy": {
    "forceStopBeforeRun": true,
    "dismissPopupAndAds": true,
    "forbidLogout": true
  },
  "collectionPolicy": {
    "phase": "terminalActions",
    "terminalPattern": "backForwardLoop",
    "metrics": ["power", "cpu", "memory", "fps", "network"]
  },
  "steps": [
    {
      "order": 1,
      "type": "navigate",
      "control": "消息列表",
      "pageId": "home-hash"
    },
    {
      "order": 5,
      "type": "performAction",
      "actionType": "backForwardLoop",
      "pageId": "chat-hash",
      "control": "会话入口",
      "repeat": 3,
      "collectDuringAction": true
    }
  ]
}
```

## 5. AI 步骤输出结构

每一步都必须输出事实，不直接输出整条用例的最终性能结论：

```json
{
  "stepNo": 5,
  "stage": "script",
  "actionType": "backForwardLoop",
  "status": "passed",
  "attempt": 2,
  "expectedPageId": "chat-hash",
  "observedPageId": "chat-hash",
  "beforeImage": "s3://bucket/run/step-5-before.png",
  "afterImage": "s3://bucket/run/step-5-after.png",
  "aiObservation": "返回到会话列表后，通过原会话入口重新进入聊天页，结构校验一致",
  "durationMs": 2180,
  "failureType": null
}
```

允许的 `failureType`：

- `controlNotFound`：入口控件未找到。
- `pageMismatch`：进入页面与预期不一致。
- `actionTimeout`：动作或页面稳定等待超时。
- `blockedByDialog`：弹窗无法安全处理。
- `automationForbidden`：遇到登录、注销、支付等禁止动作。
- `collectionMissing`：性能采集数据缺失。

## 6. AI 与后端的判定边界

AI 负责：

- 当前页面识别。
- 控件识别。
- 动作是否执行。
- 执行后页面是否符合预期。
- 生成步骤观察、截图和失败类型。

后端负责：

- 对齐脚本步骤和性能时间轴。
- 计算本次值、历史平均和阈值。
- 判断性能是否异常。
- 计算周期通过率和稳定性。
- 生成最终测试报告。

AI 不得仅根据页面“看起来卡顿”就判定 FPS 或功耗失败。

## 7. 推荐系统提示词

```text
你是移动应用图谱测试执行代理。当前任务由路径阶段和终点阶段组成。

严格规则：
1. 中间页面只用于导航和页面校验，不执行压力动作。
2. 只有确认到达 terminalPageId 后才开始 collectDuringAction 动作。
3. scrollLoop 在主内容区向上滑动4次、向下滑动3次；每次操作后校验仍为同一功能页面。
4. backForwardLoop 每轮只返回一次，校验父页面后使用给定 control 重新进入终点，重复3次。
5. 遇到广告和普通弹窗可以关闭；不得登录、注销、支付、授权敏感权限或修改账号状态。
6. 动态文本变化不代表页面变化，优先比较布局结构、稳定控件和 page hash 特征。
7. 每一步输出 JSON 证据，包括 observedPageId、status、durationMs、截图和 failureType。
8. 你只判断动作和页面是否符合预期，不判断性能指标是否越过阈值。
```

## 8. 报告复盘关系

```text
周期趋势异常点
  -> runId
  -> metric result
  -> terminal action window
  -> stepNo
  -> before/after screenshot
  -> AI observation / failureType
```

该链路保证测试报告中的每个问题都可以回到具体周期、具体执行、具体终点动作和具体证据。
