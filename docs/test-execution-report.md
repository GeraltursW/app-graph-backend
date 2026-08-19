# App Graph 测试执行与报告闭环

## 1. 目标

监控系统返回高危 URL 后，不预生成海量静态用例，而是按 URL 匹配图谱页面、即时生成最短恢复路径和典型动作，用设备脚本执行并形成可追溯报告。

```text
风险 URL -> 图谱页面匹配 -> 即时用例 -> 脚本执行 -> 指标/截图回传 -> Java 聚合 -> 前端报告
```

## 2. 三端职责

| 模块 | 输入 | 主要职责 | 输出 |
|---|---|---|---|
| Java 后端 | URL、图谱、Function Tree、阈值 | 匹配页面、生成用例、下发任务、保存事实、生成判定 | `scriptTask`、`testReport` |
| 设备脚本 | `scriptTask` | 重置应用、AI 识图、执行动作、采集性能、保存步骤截图 | 执行结果 JSON |
| Vue 前端 | 测试报告 | 批次总览、URL 下钻、基线对比、步骤证据展示 | 可汇报、可定位的报告界面 |

## 3. 脚本运行

纯本地演示，无设备和后端也可以生成标准回传 JSON：

```powershell
python scripts/app_graph_test_runner.py --output build/demo-test-report.json
```

真实执行时先从后端下载任务，然后回传结果：

```powershell
python scripts/app_graph_test_runner.py `
  --task task.json `
  --output build/run-result.json `
  --callback-url http://127.0.0.1:8080/appGraph/api/testRuns/import
```

`DemoDeviceDriver` 是明确的设备适配边界。接入 HDC/Appium 时替换 `reset_app()`、`execute()` 和 `collect_metrics()`，报告字段保持不变。

## 4. 后端接口

### 获取脚本任务

```http
GET /appGraph/api/testCases/{testCaseId}/scriptTask
```

### 回传完整执行结果

```http
POST /appGraph/api/testRuns/import
Content-Type: application/json
```

返回 `runId` 与报告查询地址。后端原样保留脚本事实，并分别写入：

- `test_execution_runs`：一次执行、设备、环境、结论。
- `test_execution_steps`：逐步动作、耗时、截图与 AI 观察。
- `test_metric_results`：基线、本次值、阈值和判定。

### 查询单次报告

```http
GET /appGraph/api/testReports/{runId}
```

### 查询应用报告列表

```http
GET /appGraph/api/testReports?appName=QQ
```

## 5. 报告阅读顺序

1. 批次层：多少预警 URL、匹配率、执行率、通过率。
2. URL 层：URL 匹配到哪个图谱页面、生成了哪条恢复路径。
3. 指标层：本次结果相对稳定基线和阈值的变化。
4. 证据层：异常发生在哪一步，操作、耗时、前后截图和 AI 观察是什么。

前端 Demo 使用同一结构的纯前端 Mock，因此没有 Java 服务时也能演示完整交互；切换真实接口时只需把报告查询结果映射到相同视图模型。
