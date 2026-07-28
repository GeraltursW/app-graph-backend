# App Graph Backend

第三方移动应用页面图谱的独立 Java 后端。服务接收手机自动化与 AI 识图结果，完成页面去重、图关系维护、四层动作存储、厂商 Function Tree 匹配和测试用例生成。

## 技术栈

- Java 21
- Spring Boot 4.1
- Spring MVC / Validation / JDBC
- PostgreSQL 18+
- Flyway
- pgvector 可选启用
- Maven Wrapper

## 快速启动

1. 创建数据库：

```sql
CREATE DATABASE app_relation;
```

2. 配置环境变量。默认值适配本机 PostgreSQL：

```powershell
$env:DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/app_relation'
$env:DATABASE_USERNAME='postgres'
$env:DATABASE_PASSWORD='<your-password>'
$env:STORAGE_ROOT='D:\storage\app-graph-images'
```

3. 启动：

```powershell
.\mvnw.cmd spring-boot:run
```

4. 验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
Invoke-RestMethod http://127.0.0.1:8080/appList
```

服务默认地址：`http://127.0.0.1:8080`。

## 从采集文件导入

文件夹结构：

```text
inbox/qq-20260729/
  ai_result.json
  screenshots/
    home.png
    message.png
```

调用：

```powershell
$body = @{ folder = 'D:\inbox\qq-20260729' } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -ContentType 'application/json' `
  -Body $body `
  http://127.0.0.1:8080/api/imports/scanFolder
```

导入器使用 `structureHash` 作为功能页面去重主信号。图片内容变化但结构相同的页面，会新增 `page_instances`，不会重复创建 `canonical_pages`。

## 核心接口

| 方法 | 地址 | 作用 |
|---|---|---|
| GET | `/health` | 服务健康检查 |
| POST | `/api/imports/scanFolder` | 导入 AI JSON 与截图文件夹 |
| GET | `/appList` | 应用列表与图节点数 |
| GET | `/queryAppGraph/{appName}` | 查询递归图谱与游离页 |
| POST | `/createOrphanNode` | 创建游离 URL |
| POST | `/moveNode` | 调整父子关系 |
| POST | `/deleteNode` | 删除页面节点 |
| POST | `/updateNode` | multipart 更新页面、图片、AI 结论和四层动作 |
| GET | `/image/{imageName}` | 读取本地截图 |
| POST | `/api/functionTree/import` | 导入厂商元数据和 Function Tree |
| POST | `/api/functionMatch/estimate` | 估算本地比较量与 AI 调用量 |
| POST | `/api/functionMatch/run` | 执行页面/动作与功能点匹配 |
| POST | `/api/testCases/generate` | 生成终点采集与过程采集用例 |
| GET | `/api/testCases/{id}/scriptTask` | 输出脚本可执行任务 |

## 文档

- [后端基础功能](docs/backend-basic-guide.md)
- [Function Tree 结合方案](docs/function-tree-integration.md)
- [用例生成方案](docs/test-case-generation.md)
- [Python 到 Java 迁移说明](docs/python-to-java-migration.md)

## pgvector

基础服务不强制依赖 pgvector，避免扩展未安装时图谱服务无法启动。`embedding_records.embedding` 默认使用文本兼容列。

PostgreSQL 服务器安装 pgvector 后执行：

```powershell
psql -U postgres -d app_relation -f scripts/enable-pgvector.sql
```

该脚本将列转换为 `vector(1536)` 并创建 HNSW cosine 索引。

## 构建

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
```

产物位于 `target/app-graph-backend-0.0.1-SNAPSHOT.jar`。
