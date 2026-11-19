# OCI Lens

面向单机自托管的 OCI 与本机监控台。界面采用顶部导航，可查看实例、主机、流量、费用和同步记录。浏览数据时只读取本地 SQLite 快照。

## 启动

在装有 Docker Compose 的 Linux 主机执行以下命令。

```sh
bash scripts/init-local.sh
docker compose up --build -d --wait
```

浏览器打开 `http://127.0.0.1:28462`。首次启动前可在 `.env` 设置 `MONITOR_ADMIN_USERNAME`，初始默认账号名为 `admin`，初始密码由初始化脚本生成到 `secrets/admin-password`。登录后可在「设置 → 账号」修改账号名和密码，容器重启继续使用数据卷中保存的凭据。远程使用时，通过 SSH 端口转发访问，或配置 HTTPS 反向代理并设置 `MONITOR_COOKIE_SECURE=true`。

登录页和顶部导航都可切换深浅主题，选择保存在当前浏览器。默认使用深色主题，顶部导航提供退出登录入口。

## 接入 OCI

**部署在 OCI 上。** 保持默认 `OCI_AUTH_MODE=auto`。进入「设置 → OCI 接入」，确认自动识别的实例和采集范围。用具备 IAM 配置权限的账号在租户主区域打开 Cloud Shell，粘贴完整授权命令。待权限生效后返回界面执行连接检查。 [Oracle Cloud Shell](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/cloudshellintro.htm)

**部署在其他主机。** 在 `.env` 设置 `OCI_AUTH_MODE=config_file`、`OCI_REGION`、`OCI_COMPARTMENT_OCID`。根据控制台生成的配置创建 `secrets/oci/config`，将私钥放入同一目录，并让 `key_file` 指向容器内的绝对路径 `/home/monitor/.oci/<文件名>`。配置与私钥需要允许容器用户 UID 10001 读取。然后重建容器，在设置页检查连接。 [Oracle API Key 教程](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm)

要读取费用，管理员可设置 `OCI_LENS_BILLING_ENABLED=true` 并授予 `usage-report` 读取权限。跨 Compartment 的子网可通过 `OCI_LENS_NETWORK_COMPARTMENT_OCID` 指定额外的网络读取范围。更改采集租户或范围需要独立数据卷。

本机默认每 5 分钟采样。OCI 连接验证后，默认按 `Asia/Shanghai` 每天 08:00 同步。手动同步、定时任务、诊断和补采共享每 UTC 日请求上限与每 UTC 月预估检索点数上限。默认分别为 80 次和 1 亿点。该预算约束本应用发出的资源查询。对费用有严格要求且无法核对租户额度时，可继续使用本机模式。 [Oracle Monitoring 定价](https://www.oracle.com/manageability/pricing/)

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
