# App Graph Java 后端基础功能

## 1. 目标与边界

该服务是 App Graph 的独立 Java 后端，负责：

1. 接收手机脚本和 AI 识图生成的 JSON、截图。
2. 将“截图实例”归一化为“功能页面节点”。
3. 存储页面、图片、边、四层动作和人工复核结果。
4. 向前端返回递归图谱与游离页面。
5. 导入厂商 Function Tree，并匹配页面与动作。
6. 生成脚本可执行的全路径和过程采集用例。

手机控制仍属于设备执行器。Java 后端下发任务、记录结构与结果，不直接把 HDC/Appium 的设备状态塞进 Web 请求线程。

## 2. 代码结构

```text
src/main/java/com/geraltursw/appgraph/
  common/        统一异常、JSON、健康检查
  config/        环境变量、CORS、本地存储配置
  importer/      AI 文件夹导入
  graph/         图谱查询、节点编辑、图片和结构调整
  functiontree/  Function Tree 导入、检索、评分和复核
  testcase/      用例生成、查询和脚本任务输出

src/main/resources/db/migration/
  V1  图谱基础表
  V2  Function Tree 与用例表
  V3  Python 旧库兼容字段
```

## 3. 核心数据模型

### 3.1 页面双层模型

`canonical_pages` 表示功能页面，`page_instances` 表示一次真实截图和识别结果。

例如 QQ 消息列表在不同时间显示不同头像、未读数和文本：

- 截图哈希不同。
- OCR 不同。
- 页面结构一致。
- 图谱中只保留一个功能节点。
- 每次采集仍保留独立页面实例。

导入时优先使用脚本提供的 `structureHash`。没有该字段时，后端使用 `normalizedLayout` 或 `widgets` 生成 SHA-256。

### 3.2 边与动作

`page_edges` 只表达页面之间的导航关系：

```text
页面 A --点击消息列表--> 页面 B
```

`page_actions` 表达页面内所有可执行动作，包括不跳页动作：

- `popupAction`：弹窗、抽屉、半屏面板。
- `stateAction`：点赞、收藏、关注、开关。
- `externalAction`：相机、系统分享、小程序、外部 App。
- `pageNaviAction`：进入另一个功能页面。

动作使用 `actionFingerprint` 去重，人工编辑后覆盖同页面的动作快照。

### 3.3 图片

截图文件存储在 `STORAGE_ROOT`，数据库保存文件名。前端通过 `/image/{imageName}` 读取。

导入外部 URL 时可以直接保留 URL。导入本地路径时，后端复制文件到受控目录，避免前端访问任意本地路径。

## 4. 数据导入

请求：

```http
POST /api/imports/scanFolder
Content-Type: application/json

{
  "folder": "D:\\inbox\\qq-20260729"
}
```

处理顺序：

```text
读取 ai_result.json
  -> 按 packageName 创建或复用 App
  -> 创建 Scan
  -> 计算/读取 structureHash
  -> 创建或复用 Canonical Page
  -> 保存 Page Instance 与截图
  -> 展开 action 四层到 page_actions
  -> 保存 page_edges
  -> 完成 Scan
```

导入响应会分别给出：

- 新建功能页面数量。
- 复用功能页面数量。
- 页面实例数量。
- 边数量。

## 5. 前端兼容接口

### 5.1 应用列表

```http
GET /appList
```

```json
{
  "status": "success",
  "apps": [
    { "appName": "QQ", "count": 300 }
  ]
}
```

### 5.2 查询图谱

```http
GET /queryAppGraph/QQ
```

返回：

```json
{
  "roots": [
    {
      "id": 1,
      "pageId": "hash-id",
      "pageTitle": "消息首页",
      "pageText": "页面说明",
      "pageUrl": "qq://message",
      "images": ["home.png"],
      "aiInference": {},
      "aiRecursive": false,
      "action": {
        "popupAction": [],
        "stateAction": [],
        "externalAction": [],
        "pageNaviAction": []
      },
      "pageInfo": {},
      "children": []
    }
  ],
  "orphanPages": []
}
```

### 5.3 更新页面

`POST /updateNode` 使用 `multipart/form-data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `pageId` | string | 页面稳定 hash ID |
| `pageTitle` | string | 人工标题 |
| `pageText` | string | 人工复核描述 |
| `pageUrl` | string | 页面 URL |
| `widgetDescription` | string | 父页面进入控件 |
| `keepImages` | JSON string | 保留的旧图片文件名 |
| `newImages` | file[] | 新上传图片 |
| `aiInference` | JSON string | AI 推理对象 |
| `aiRecursive` | boolean | 是否 AI 探索产生 |
| `action` | JSON string | 完整四层动作对象 |

保存时动作整体替换并重新展开到 `page_actions`，保证 Function Tree 匹配和用例生成读取的是人工复核后的结果。

## 6. 本地运行

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/app_relation'
$env:DATABASE_USERNAME='postgres'
$env:DATABASE_PASSWORD='<your-password>'
.\mvnw.cmd spring-boot:run
```

Flyway 会兼容已有 Python 数据表，并补充 Java 服务需要的字段。

## 7. 生产化建议

当前版本适合本地联调和演示。进入共享环境前建议增加：

1. OAuth2/JWT 和 App 级数据权限。
2. S3/MinIO 替代单机截图目录。
3. 导入任务队列，避免大批量文件占用请求线程。
4. Function Match 和用例生成异步化。
5. 用 Testcontainers 建立独立集成测试数据库。
6. 图谱版本号与乐观锁，避免多人编辑互相覆盖。
7. 审计表，记录人工复核前后差异。
