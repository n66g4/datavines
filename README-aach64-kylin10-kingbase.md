# Datavines · 麒麟 V10 aarch64 + 人大金仓适配说明

> 分支：`aach64-kylin10-kingbase`  
> 上游：基于 [datavane/datavines](https://github.com/datavane/datavines) `dev`  
> 目标环境：**银河麒麟 V10（aarch64）** + **MariaDB 元库** + **人大金仓 KingbaseES 被检库**

本文说明本分支相对上游的改动、推荐部署方式与使用注意。通用安装与功能介绍见 [README.zh-CN.md](README.zh-CN.md)。

---

## 1. 架构约定

```text
┌────────────── 麒麟 V10 aarch64（同机） ──────────────┐
│  Datavines Server + Web UI + Local（JDBC）引擎       │
│         │ 元数据（MySQL 协议）      │ 只读质检 SQL    │
│         ▼                          ▼                 │
│  ┌─────────────────┐        ┌─────────────────┐     │
│  │ MariaDB :3306   │        │ 金仓 :54321     │     │
│  │ DB: datavines   │        │ 业务库（被检）   │     │
│  └─────────────────┘        └─────────────────┘     │
└─────────────────────────────────────────────────────┘
```

| 组件 | 角色 |
|------|------|
| MariaDB / MySQL | **平台元库**（规则、任务、调度、结果等），Spring profile：`mysql` |
| 人大金仓 KingbaseES | **仅被检业务库**；勿作元库（现场常见 `--dbmode=oracle`） |
| 执行引擎 | **Local（JDBC）**；一期不依赖 Spark |
| Registry | 数据库模式即可，可不依赖 ZK |

---

## 2. 本分支主要改动

### 2.1 人大金仓连接器 `kingbase`

- 新插件：`datavines-connector-kingbase`
- 驱动：`com.kingbase8.Driver`
- JDBC URL：`jdbc:kingbase8://host:port/database`
- 方言与指标脚本对齐 PostgreSQL；数据源类型在 UI 中可选 **kingbase**
- Catalog 刷新：只拉取 **TABLE**，过滤系统 schema / `sys_`、`all_`、`dba_` 等前缀，减少字典视图噪音

部署时请将金仓官方 JDBC（如 `kingbase8-8.6.0.jar`）与连接器 jar 一并放入 `libs/`。

### 2.2 数据源密码脱敏

- 列表/详情中密码展示为 `******`
- 更新或测试连接时，若前端传空 / `******`，服务端回填库中真实密码，避免误清空

### 2.3 错误数据存储：本地 CSV（`file`）

- 开放 `file` 类型作为错误明细存储（UI 与创建校验对齐）
- 落盘格式为服务器本地 **CSV 文本文件（.csv）**，不是 Excel / Markdown
- 配置项：`data_dir`（目录）、`column_separator`（列分隔符，推荐 `\u0001`）
- 创建/更新时校验类型是否支持错误存储，避免 API 旁路写入不支持的类型

### 2.4 作业批量导入

- 接口：`POST /api/v1/job/batch-import`（支持 Excel / CSV / JSON）
- Web「作业」页提供批量导入与模板下载
- 支持按规则拆分（`PER_RULE`）或按表拆分（`PER_TABLE`）

### 2.5 Catalog 系统库过滤

- 元数据拉取时将 `kingbase` 视为需过滤系统库的类型之一，配合连接器侧表名过滤，降低 `TABLE_DELETED` 误报

---

## 3. 构建

环境要求与上游一致：**JDK 8**、**Maven 3.6+**。

离线/内网目标机若无法拉依赖，请在有网机器构建后上传产物。

```sh
# 完整发行包（含 UI）
mvn clean package -Prelease -DskipTests

# 或按模块增量打包（示例）
mvn -pl datavines-connector/datavines-connector-plugins/datavines-connector-kingbase,datavines-server -am package -DskipTests
```

前端单独构建（会打进 server 的 `static`）：

```sh
cd datavines-ui
npm install
npm run build:prod
```

连接器模块 `finalName` 可能为 `datavines-1.0.0-SNAPSHOT.jar`，上传到目标机时请改名为：

- `datavines-connector-kingbase-1.0.0-SNAPSHOT.jar`
- `datavines-connector-file-1.0.0-SNAPSHOT.jar`
- `datavines-server-1.0.0-SNAPSHOT.jar`
- `datavines-core-1.0.0-SNAPSHOT.jar`

---

## 4. 部署要点（MariaDB 元库）

1. 初始化元库：执行上游 `scripts/sql/datavines-mysql.sql`
2. 配置 `spring.profiles.active=mysql`，数据源指向 MariaDB
3. 安装目录示例：`/opt/datavines`，启动：

```sh
cd /opt/datavines
./bin/datavines-daemon.sh start mysql
```

4. Web 默认端口：**5600**
5. 同机部署时注意堆内存，避免与金仓争抢（例如 `-Xmx2g -Xms512m`）

### 被检库（金仓）

- 在 Web「数据源」中选择类型 **kingbase**
- 使用只读账号；错误明细建议落到本地 `file` CSV，不要写回业务库
- 端口与实例以现场为准（常见 `54321`）

### 错误明细目录示例

```text
/data/datavines/error-data
```

列分隔符建议使用 SOH（`\u0001`），避免字段内逗号干扰。

---

## 5. 验证清单

- [ ] 登录 Web，数据源类型列表含 **kingbase**
- [ ] 金仓数据源测试连接成功；Catalog 刷新后主要为业务表
- [ ] 错误数据存储类型列表含 **file（本地 CSV 文本文件）**，配置表单可填目录与分隔符
- [ ] 创建不支持的错误存储类型被拒绝
- [ ] 作业可调度执行，结果与错误 CSV 可查看
- [ ] （可选）批量导入模板下载与导入成功

---

## 6. 已知限制

- 目标机无外网时，依赖与 yum 均需离线处理；**构建在有网环境完成**
- 金仓 Oracle 兼容模式与 PG 方言仍有差异，复杂 SQL / 函数需按现场验证
- 一期默认 Local 引擎；大规模并行与 Spark 不在本分支范围
- 请勿将数据库密码、SSH 口令等密钥提交到 Git

---

## 7. 参考

- 上游中文说明：[README.zh-CN.md](README.zh-CN.md)
- 元库 SQL：`scripts/sql/datavines-mysql.sql`
- 金仓连接器源码：`datavines-connector/datavines-connector-plugins/datavines-connector-kingbase/`

如有现场差异（端口、兼容模式、JDBC 版本），以实际环境为准，优先保证：**元库 MariaDB、被检库金仓、错误数据本地 CSV、Local 引擎可跑通**。
