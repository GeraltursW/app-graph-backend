# Python 后端到 Java 后端迁移说明

## 1. 迁移结果

新仓库是独立 Spring Boot 服务，不依赖 FastAPI 进程，也不是对 Python API 的代理。

| Python 能力 | Java 位置 | 状态 |
|---|---|---|
| 健康检查 | `common/HealthController` | 已迁移 |
| 文件夹导入 | `importer/ImportService` | 已迁移 |
| 页面结构去重 | `importer/ImportService` | 已迁移 |
| 应用列表/图谱查询 | `graph/GraphService` | 已迁移 |
| 页面编辑与图片 | `graph/GraphService` | 已迁移 |
| 四层动作编辑与展开 | `graph/GraphService` | 已迁移 |
| 游离节点创建 | `graph/GraphService` | 已迁移 |
| 节点移动/删除 | `graph/GraphService` | 已迁移 |
| Function Tree 导入 | `functiontree/FunctionTreeService` | 已迁移 |
| Function Match 估算/运行 | `functiontree/FunctionTreeService` | 已迁移 |
| 人工复核/手工绑定 | `functiontree/FunctionTreeService` | 已迁移 |
| 两类用例生成 | `testcase/TestCaseService` | Java 新增并持久化 |
| 脚本任务输出 | `testcase/TestCaseService` | Java 新增 |

## 2. 未放入 Java Web 服务的内容

HDC/Appium 设备操作、截图和端侧 AI 识图是“设备执行器”，不属于后端 Web 服务。它们可以继续使用 Python，也可以另建 Java/Node 执行器；两者通过 JSON 任务契约交互。

这样拆分的原因：

- 设备可能断线，不能占用 HTTP 请求线程等待。
- 一个后端需要调度多台手机。
- 脚本升级频率高于后台。
- 设备执行需要重试、租约、心跳和任务取消。

后续建议增加 `device_workers / execution_runs / execution_steps / metric_samples` 表和任务领取、心跳、结果回传接口。

## 3. 字段与数据库兼容

HTTP JSON 使用 camelCase：

```text
pageId, pageTitle, pageText, pageUrl, widgetDescription
```

PostgreSQL 列保留 snake_case：

```text
page_hash_id, display_name, ai_summary, page_url, widget_description
```

这是刻意的边界：

- 前端和 API 保持 JavaScript 习惯。
- SQL 保持 PostgreSQL 习惯。
- 不需要对旧库进行全列改名。

Flyway 的 `baseline-on-migrate` 支持接入已有 Python 数据库；V3 会补齐 Java 复核字段。

## 4. 上线切换建议

1. 备份现有数据库。
2. 在测试库运行 Java 服务，让 Flyway 完成迁移。
3. 对比 `/appList` 和 `/queryAppGraph/{appName}` 返回。
4. 用一份新的 AI 采集目录测试导入。
5. 导入一版 Function Tree 并执行 estimate/run。
6. 生成用例并拉取 scriptTask。
7. 前端 API Base URL 从 Python 端口切到 Java 8080。
8. 保留 Python 后端只读一周，再停止进程。

不要让 Python 和 Java 同时写同一应用图谱，除非已经增加图谱版本锁。

