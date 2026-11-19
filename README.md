# OCI Lens

面向单机自托管的 OCI 与本机监控台。界面采用顶部导航，可查看实例、主机、流量、费用和同步记录。浏览数据时只读取本地 SQLite 快照。

## 部署与运维

在装有 Docker、Compose、Python 3 和 OpenSSL 的 Linux 主机，从仓库目录运行以下命令。首次部署以及拉取新代码后都使用 `rebuild`。

```sh
bash scripts/oci-lens.sh rebuild
```

首次部署如需先调整配置，可运行 `bash scripts/oci-lens.sh init`，编辑 `.env` 后再重建。默认访问地址为 `http://127.0.0.1:28462`，初始账号为 `admin`。首次生成的密码保存在 `secrets/admin-password`。用「设置 → 账号」修改的账号和密码保存在 SQLite。启停和重建沿用同一数据卷，保留当前凭据。已有管理员且初始化文件缺失时，脚本补一个空文件供 Compose 挂载。

```sh
bash scripts/oci-lens.sh start
bash scripts/oci-lens.sh status
bash scripts/oci-lens.sh logs server
bash scripts/oci-lens.sh doctor
bash scripts/oci-lens.sh backup
bash scripts/oci-lens.sh stop
```

`start` 使用已有镜像，`rebuild --no-cache` 可以忽略构建缓存。重建先构建镜像，再备份现有 SQLite 到 `backups/`，最后切换容器并等待健康检查。远程使用时，通过 SSH 转发访问，或配置 HTTPS 反向代理并设置 `MONITOR_COOKIE_SECURE=true`。

### 忘记管理员密码

部署过新版镜像后，在部署主机运行 `bash scripts/oci-lens.sh reset-password`，终端将隐藏输入并要求确认新密码。使用 `--generate` 可生成随机密码，脚本会打印权限为 `0600` 的密码文件路径。数据库有多个管理员时，用 `--username 当前账号名` 指定要重置的记录。

脚本在写入前备份当前 SQLite，保留账号名和业务数据。此前运行的后端会在操作结束后恢复，已有登录会话随密码更新失效。网络接入、采集和迁移的配置继续使用当前部署的 `.env` 和卷。

登录页和顶部导航支持深浅主题，选择保存在当前浏览器。

## 接入 OCI

**部署在 OCI 上。** 保持默认 `OCI_AUTH_MODE=auto`。进入「设置 → OCI 接入」，确认自动识别的实例和采集范围。用具备 IAM 配置权限的账号在租户主区域打开 Cloud Shell，粘贴完整授权命令。待权限生效后返回界面执行连接检查。 [Oracle Cloud Shell](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/cloudshellintro.htm)

**部署在其他主机。** 在 `.env` 设置 `OCI_AUTH_MODE=config_file`、`OCI_REGION`、`OCI_COMPARTMENT_OCID`。根据控制台生成的配置创建 `secrets/oci/config`，将私钥放入同一目录，并让 `key_file` 指向容器内的绝对路径 `/home/monitor/.oci/<文件名>`。配置与私钥需要允许容器用户 UID 10001 读取。然后重建容器，在设置页检查连接。 [Oracle API Key 教程](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm)

要读取费用，管理员可设置 `OCI_LENS_BILLING_ENABLED=true` 并授予 `usage-report` 读取权限。跨 Compartment 的子网可通过 `OCI_LENS_NETWORK_COMPARTMENT_OCID` 指定额外的网络读取范围。更改采集租户或范围需要独立数据卷。

本机默认每 5 分钟采样。OCI 连接验证后，默认按 `Asia/Shanghai` 每天 08:00 同步。手动同步、定时任务、诊断和补采共享每 UTC 日请求上限与每 UTC 月预估检索点数上限。默认分别为 80 次和 1 亿点。该预算约束本应用发出的资源查询。对费用有严格要求且无法核对租户额度时，可继续使用本机模式。 [Oracle Monitoring 定价](https://www.oracle.com/manageability/pricing/)

## 本机、云盘与额度

概览用一张图显示部署主机的 CPU、内存、根分区和网卡趋势。「监控 → 本机」按日期查询历史，包含同机导入的采样。「设置 → 监控偏好」可为当前浏览器指定默认入口。在 Linux Compose 中，主机采集使用只读挂载。根分区默认读取 `MONITOR_SERVER_ROOT_SOURCE=/`。想缩小挂载范围时，可以设置为根文件系统上一个专用的已存在目录，并用宿主机 `df` 对照。容器内通过挂载点统计文件系统总量、已用量和可用量。

要监控额外挂载点，在 Compose override 为 `server` 新增只读 bind mount。例如将宿主机 `/mnt/data` 挂载到 `/host/disks/data`，再设置 `MONITOR_SERVER_EXTRA_DISKS=/mnt/data=/host/disks/data`。多个映射以逗号分隔。本机网卡默认采集宿主机带有系统设备的接口，设置 `MONITOR_SERVER_NETWORK_INTERFACE` 可选定一个接口。本机常规采样默认保存 72 小时，可通过 `MONITOR_SERVER_HISTORY_RETENTION_HOURS` 调整。

新生成的 Cloud Shell 命令包含 `--with-volumes`，为现有动态组另建云盘只读策略。使用 API Key 时，请为调用身份配置作用域内的 `inspect volumes` 和 `inspect volume-attachments`，以及执行 `ListAvailabilityDomains` 和 `ListRegionSubscriptions` 所需的只读权限。权限生效后在概览执行一次同步，再打开实例详情查看云盘。现有主策略与当前账单开关的授权不一致时，先在 OCI IAM 核实已有策略。

「用量与费用 → 本月使用」展示本机主网卡按 UTC 日汇总的月度流量与覆盖天数，以及 OCI 的 A1、出站计量和采集范围内的云卷分配量。OCI 的计量使用账单读取开关与 `read usage-report` 权限。主网卡趋势来自 OCI Monitoring。云盘采集使用独立只读权限，读取失败时保留最近的可用数据及失败调用。 [Oracle Free Tier](https://www.oracle.com/cloud/free/) · [Oracle 核心服务 IAM](https://docs.oracle.com/en-us/iaas/Content/Identity/Reference/corepolicyreference.htm)

## 验证与开发

前端位于 `web/`，后端位于 `server/`，分别使用 pnpm 和 Java 21 Maven 构建。

```sh
cd web
pnpm install --frozen-lockfile
pnpm check
pnpm build
```

```sh
cd server
mvn test
```

要生成离线示例，在 `web/` 执行 `pnpm build:demo`，然后在根目录执行 `python3 scripts/export-preview.py`。用浏览器打开脚本输出的本地 HTML 即可查看示例数据。

Liquid Glass 界面使用 [Glasscn Registry](https://glasscn.app/docs) 的组件和样式源码，并按 OCI Lens 的界面需求调整。

## 文档

- [实施方案](docs/implementation-plan.md)
- [当前架构与状态口径](docs/architecture.md)
- [从 OCI ARM Monitor 迁移历史数据](docs/migrate-from-oci-arm-monitor.md)
