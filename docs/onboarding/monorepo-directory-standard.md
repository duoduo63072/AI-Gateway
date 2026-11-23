# Monorepo 目录结构标准

> 适用于 AI 时代的 Monorepo 工程。目标是让人和 AI 都能快速理解：代码在哪里、规范在哪里、部署在哪里、设计在哪里、自动化在哪里。

## 顶层目录

```
repo/
├── apps/              # 应用入口 — 可独立运行的前端、客户端、管理后台
├── services/          # 后端服务 — 按业务或能力拆分的服务代码
├── packages/          # 共享代码包 — 多个 apps/services 复用的代码
├── schemas/           # 系统契约 — 服务间、前后端间的数据边界定义
├── infra/             # 基础设施 — 部署资源定义（非 CI 编排）
├── .yunxiao/          # 阿里云云效流水线配置
├── docs/              # 文档与团队知识库
├── tools/             # 工程工具与自动化脚本
├── tests/             # 跨应用、跨服务的测试
└── configs/           # 仓库级公共配置
```

## 目录详细说明

### `apps/` — 应用入口

存放可独立运行的前端、客户端、管理后台等。每个子目录通常是一个完整应用。

```
apps/
├── admin-web/         # Web 管理后台 (Vue 3 + Ant Design Vue)
├── h5-mobile/         # 移动端 H5 应用 (Vue 3 + Vant)
├── mini-app/          # 微信小程序 (原生 WXML/WXSS/JS)
└── portal/            # 官网 / 门户 (可选)
```

### `services/` — 后端服务

存放按业务或能力拆分的后端服务代码。每个子目录通常是一个可独立构建、测试、部署的服务。

```
services/
├── pom.xml                   # 父 POM，聚合所有模块
├── honyar-iot-main/          # IoT 核心服务
├── honyar-hotel-main/        # 酒店管理服务
├── honyar-mes-main/          # MES 制造执行服务
├── roses-kernel/             # 共享内核/框架层
├── honyar-plugins/           # 鸿雁业务插件
└── enterprise-plugins/       # 企业级插件模块
```

### `packages/` — 共享代码包

存放多个 apps 或 services 会复用的代码。不建议放具体业务应用代码。

```
packages/
├── ui/                # UI 组件库
├── utils/             # 工具函数
├── types/             # 类型定义
├── sdk/               # SDK
├── config/            # 配置包
└── shared-logic/      # 业务公共逻辑
```

### `schemas/` — 系统契约与数据结构定义

明确服务之间、前后端之间、系统之间的数据边界。

```
schemas/
├── openapi/           # OpenAPI / Swagger 规范
├── graphql/           # GraphQL Schema
├── protobuf/          # Protobuf 定义
├── database/          # 数据库 Schema / Migration
├── events/            # 事件结构定义
└── webhooks/          # Webhook Payload 定义
```

### `infra/` — 基础设施与部署资源定义

存放"部署资源定义"，不是 CI 流水线编排。

```
infra/
├── terraform/         # Terraform 云资源配置
├── kubernetes/        # K8s 部署清单
├── helm/              # Helm Charts
├── docker/            # Docker 相关配置（多服务编排等）
└── nginx/             # Nginx 配置
```

### `.yunxiao/` — 云效 CI/CD 流水线配置

```
.yunxiao/
├── hiot-service-dev.yml     # IoT 后端测试环境流水线
├── hiot-service-prod.yml    # IoT 后端生产环境流水线
└── admin-web.yml            # 管理后台流水线 (可选)
```

> 如果同时使用 GitHub Actions，放在 `.github/workflows/`；GitLab CI 放在 `.gitlab-ci.yml`。

### `docs/` — 文档与团队知识库

```
docs/
├── architecture/      # 架构文档：系统设计、模块关系、技术选型
├── product/           # 产品文档：业务规则、用户流程、术语定义
├── design/            # UI/UX 设计资料：Figma 链接、设计规范、截图
├── adr/               # 架构决策记录：为什么选择某个技术方案
├── runbooks/          # 运维手册：故障处理、发布回滚、应急流程
├── api/               # API 使用说明、接口约定补充说明
└── onboarding/        # 新人接入：环境搭建、开发流程、仓库说明
```

### `tools/` — 工程工具与自动化

```
tools/
├── scripts/           # 通用脚本：构建、发布、代码生成、迁移
├── generators/        # 代码生成器、模板生成工具
├── agents/            # AI Agent 配置、提示词、任务模板
└── devtools/          # 内部开发辅助工具
```

### `tests/` — 跨服务测试

存放端到端测试、集成测试、契约测试、性能测试。单个应用或服务自己的测试放在各自目录内。

### `configs/` — 仓库级公共配置

如果某些配置已被 `packages/config` 管理，可以不单独建这个目录。

```
configs/
├── eslint.config.js
├── prettier.config.js
└── tsconfig.base.json
```

## 根目录关键文件

```
repo/
├── README.md                  # 仓库总说明
├── CONTRIBUTING.md            # 贡献指南：分支规范、提交规范、PR 流程
├── CHANGELOG.md               # 版本变更记录
├── LICENSE                    # 授权说明
├── CLAUDE.md                  # AI 助手上下文（项目概述、技术栈、约定）
├── PROJECT_PLAN.md            # 项目计划与里程碑跟踪
│
├── docker-compose.yml         # 服务编排（生产/测试部署）
├── .env.example               # 环境变量示例（不放真实密钥）
├── .gitignore                 # Git 忽略规则
├── .editorconfig              # 编辑器格式统一
│
├── package.json               # JS/TS Monorepo 根配置（可选）
├── pnpm-workspace.yaml        # pnpm workspace 声明（可选）
├── turbo.json                 # Turborepo 配置（可选）
└── nx.json                    # Nx 配置（可选）
```

## 原则

1. **单一职责** — 每个目录只做一件事，名字即含义
2. **可独立构建** — `apps/` 和 `services/` 下的每个子目录可以独立构建和部署
3. **共享下沉** — 被多处引用的代码放 `packages/`，不被引用的留在各自目录
4. **配置就近** — 应用级配置放在应用目录内，仓库级配置放 `configs/` 或根目录
5. **文档分层** — 架构决策、运维手册、产品文档各归各处，不要全堆在根目录
