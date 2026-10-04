# lifestylePicks-shop-service

从原单体迁出的店铺与店铺分类服务，默认端口 8082。只操作 `tb_shop`、`tb_shop_type`，保留现有前端接口和响应字段。

| 方法与路径 | 功能 | 登录要求 |
| --- | --- | --- |
| GET `/shop/{id}` | 店铺详情，Redis 穿透缓存 | 公开 |
| GET `/shop/of/type` | 分类分页；带 x/y 时查附近店铺 | 公开 |
| GET `/shop/of/name` | 名称搜索，分页大小 10 | 公开 |
| GET `/shop-type/list` | 分类按 sort 排序 | 公开 |
| POST `/shop` | 新增店铺，由数据库生成 ID | 登录 |
| PUT `/shop` | 更新店铺 | 登录 |

网关同样支持上述路径前加 `/api`。写接口从 common 的 `UserContext` 读取身份，没有身份返回 401。common 已通过依赖自动注册，无需复制拦截器。当前沿用原业务的数据模型，未增加店铺所有者或商户角色体系。

## 启动

使用 JDK 8，在父工程目录构建并启动：

```powershell
mvn '-Dmaven.repo.local=./.maven-repository' install
java -jar .\lifestylePicks-shop-service\target\lifestylePicks-shop-service-0.0.1-SNAPSHOT.jar
```

也可在 IDEA 中运行 `com.lifestylepicks.shop.ShopApplication`。配置通过环境变量覆盖：

| 变量 | 默认值 |
| --- | --- |
| `SHOP_PORT` | `8082` |
| `SHOP_DB_URL` | `jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&characterEncoding=utf8` |
| `SHOP_DB_USERNAME` / `SHOP_DB_PASSWORD` | `root` / `mysql` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `127.0.0.1` / `6379` / `0` |
| `REDIS_PASSWORD` | 空 |
| `SHOP_GEO_INITIALIZE` | `false` |

网关的 `SHOP_SERVICE_URI` 默认是 `http://127.0.0.1:8082`，变更店铺服务端口或主机后应同步修改。

## 数据与缓存

第一阶段复用现有 MySQL 实例和 `hmdp` schema，服务只访问自己的两张表，不依赖其他业务 Mapper。启动时自动建表和数据导入均关闭，没有执行任何现有数据库迁移。

`src/main/resources/db/shop.sql` 仅包含两张店铺表及原项目示例数据，没有 DROP TABLE。它用于手动导入空数据库，现有 `hmdp` 已有数据时不需要执行。

迁到独立库时，应在停止店铺写入后导出当前真实数据（不要用示例数据替代已有业务数据），导入新库，再修改 `SHOP_DB_URL`。例如先创建 `hmdp_shop`，然后导出原库的 `tb_shop`、`tb_shop_type` 并导入；其他业务继续访问原库。实际迁库需要按你的数据库账号和数据状态操作，本次代码没有执行这一步。

保留 Redis `cache:shop:{id}` 和 `shop:geo:{typeId}`，可直接接续原店铺缓存。缓存使用原 Hutool JSON 格式，有效店铺缓存 30 分钟，不存在的店铺缓存空字符串 2 分钟。仅迁移当前实际启用的穿透缓存，没有迁入旧代码中未启用的互斥锁和逻辑过期分支。

附近查询使用兼容 Redis 3.2+ 的 GEORADIUS，查询 5 公里内的数据，按距离排序，每页 5 条，响应 `distance` 单位为米。首次迁移或 Redis 索引缺失时，设置 `SHOP_GEO_INITIALIZE=true`，启动后会从店铺表补充 GEO 成员，不清空现有 key。初始化结束后可恢复为 false。

新增和更新在本地事务提交后清除对应缓存，并维护 GEO；分类变更会删除原分类的成员，再写入新分类。Redis 同步失败会记录日志，已提交的数据库写入不会被误报为回滚；缓存依赖 TTL 收敛，GEO 可通过初始化补充。此阶段没有引入可靠消息重试或跨存储原子事务。

## 单体退出与回退

原单体的 `ShopController` 和 `ShopTypeController` 默认不再注册。原代码保留供回退和原测试使用，用户、内容及交易控制器继续运行。

回退时显式开启单体的 `hmdp.legacy-shop.enabled=true`，并将网关 `SHOP_SERVICE_URI` 指向 `http://127.0.0.1:8081`。若已迁到独立数据库，需要先确认单体能够访问对应的最新店铺数据。店铺新服务故障时，网关不会自动把请求退回单体，避免两个服务同时写入。

业务服务端口应只允许可信网关和内部调用访问。common 读取可信入口传递的身份，不会验证 Token 或 Header 签名。

## 可复现联调

父工程完成构建后，在父工程目录运行：

```powershell
python .\scripts\verify_shop_service.py --java 'C:\Program Files\Java\jdk1.8.0_202\bin\java.exe' --redis-server 'C:\Users\86156\config\Redis\redis-server.exe' --mysqld 'C:\Program Files\mysql8\mysql-8.0.26-winx64\bin\mysqld.exe' --mysql 'C:\Program Files\mysql8\mysql-8.0.26-winx64\bin\mysql.exe'
```

其他机器调整可执行文件路径；已配置 PATH 时可省略对应参数。脚本仅使用 Python 标准库，明确通过 `--no-defaults`、独立数据目录和随机端口启动临时 MySQL，另启无持久化 Redis、店铺服务和网关，不连接现有数据库。结束后停止所有验证进程，日志和临时数据库留在本模块 `target/auth-check-*`，可在 Maven clean 时清理。

验证涵盖真实 MySQL/Redis 的详情、分类、名称查询，SQL/GEO 分页，缓存及空缓存，网关登录拦截和 common 身份恢复，新增更新后的缓存失效与 GEO 分类变更，失败写入回滚，以及服务停止时不会回退到单体。用户和其他未拆分业务在此脚本中使用模拟单体上游，仅验证路由保持正确。
