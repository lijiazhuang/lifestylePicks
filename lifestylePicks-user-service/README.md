# lifestylePicks-user-service

用户服务默认端口 8083，拥有 `tb_user`、`tb_user_info`，负责验证码登录、用户资料和签到。关注、笔记仍归内容业务，原单体通过批量 HTTP 接口读取用户展示资料。

| 方法与路径 | 功能 | 是否要求登录 |
| --- | --- | --- |
| POST `/user/code?phone=...` | 生成模拟短信验证码 | 否 |
| POST `/user/login` | 验证码登录，首次登录注册 | 否 |
| POST `/user/logout` | 删除当前会话 Token | 是 |
| GET `/user/me` | 当前用户 id/nickName/icon | 是 |
| GET `/user/{id}` | 指定用户展示资料 | 是 |
| GET `/user/info/{id}` | 用户详细资料 | 是 |
| POST `/user/sign` | 当日签到 | 是 |
| GET `/user/sign/count` | 本月截至今天的连续签到数 | 是 |
| GET `/internal/users/batch?ids=2,1` | 内部批量读取展示资料，最多 100 个 ID | 可信内部调用 |

网关支持带 `/api` 和不带前缀的 `/user` 请求。用户服务通过 common 恢复 `UserContext`，自身也拦截匿名私有请求。公开展示 DTO 位于 api 的 `UserSummary`，保持原前端 `id/nickName/icon` 格式，不返回手机号或密码；批量接口路径和上限也引用 api 中的契约。

## 构建与启动

在父工程目录使用 JDK 8：

```powershell
mvn '-Dmaven.repo.local=./.maven-repository' install
java -jar .\lifestylePicks-user-service\target\lifestylePicks-user-service-0.0.1-SNAPSHOT.jar
```

IDEA 中运行 `com.lifestylepicks.user.UserApplication`。正常配置启动四个业务服务和网关，不需要原单体；下文包含旧单体的历史兼容及回退说明。主要配置如下：

| 环境变量 | 默认值 |
| --- | --- |
| `USER_PORT` | `8083` |
| `USER_SERVICE_URI` | `http://127.0.0.1:8083`，供网关与原单体客户端使用 |
| `USER_DB_URL` | `jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&characterEncoding=utf8` |
| `USER_DB_USERNAME` / `USER_DB_PASSWORD` | `root` / `mysql` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `127.0.0.1` / `6379` / `0` |
| `REDIS_PASSWORD` | 空 |
| `TOKEN_TTL` | `36000m`，沿用原项目的 25 天 |
| `USER_LOG_CODE` | `false`，本地模拟验证码日志开关 |

原项目没有接入真实短信供应商，本次也保留模拟方式：验证码写入 Redis；本地开发显式设置 `USER_LOG_CODE=true` 后可在用户服务日志看到验证码。测试脚本只读取自己独立 Redis 的验证码，不发送真实短信。手机号加验证码是实际启用的登录方式，LoginFormDTO 的 password 字段仅保留原接口兼容。

## 登录状态与签到

- 验证码 key 为 `login:code:{phone}`，默认有效 2 分钟。验证失败不会删除正确验证码，成功通过 Lua 比较并删除，不能重复使用。
- 登录查询或创建用户，沿用原表的手机号唯一索引。并发插入冲突时重新读取已有用户，避免重复账号。
- Token 是随机 32 位字符串，仍由前端通过 `authorization` 原样传递。
- 登录 Hash 为 `login:token:{token}`，字段 `id/nickName/icon` 与原单体兼容。Hash 写入与设置 TTL 在同一 Lua 脚本中完成。
- 网关负责续期，用户服务负责签发和退出。两者必须使用同一 Redis 实例、数据库、key 格式和 `TOKEN_TTL`。
- 退出只删除当前用户的当前 Token，其他设备的会话保留。已经通过网关认证的在途请求不会被追溯撤销。
- 原单体仍从 Token 恢复旧 `UserHolder` 以支持未迁移业务，但默认不再续期，避免覆盖网关配置的 TTL。
- 签到保留 `sign:{userId}:yyyyMM` 位图，采用 `Asia/Shanghai` 日期。统计从今天向前直到首次未签到为止，最多统计当前月。

## 内容业务依赖

原单体注入 api 的 `UserClient`，由 api 的 HTTP 实现调用用户服务内部批量接口。热门笔记、Feed 按作者批量查询，点赞和共同关注保持输入 ID 顺序；缺失用户不再触发空指针，笔记显示“已注销用户”。单次请求最多 100 个 ID，客户端自动分批，默认连接超时 500ms、读取超时 1s，配置详见 [api 说明](../lifestylePicks-api/README.md)。

默认模式下用户服务故障不会悄悄改读旧用户表。内部接口仅投影公开字段，不通过网关转发到用户服务；业务服务端口需要限制为可信网关和内部服务可访问。当前没有启用服务间签名或服务网格身份认证。

原单体 Redisson 现在读取已有 `spring.redis` 配置（默认仍为本地 6379），与它的 StringRedisTemplate 使用同一地址、数据库和密码；原单体数据库配置文件没有因本次拆分被覆盖。

## 数据与回退

默认暂时复用 `hmdp` schema，但用户服务只操作自己的两张表。`src/main/resources/db/user.sql` 是两张表及原项目示例数据，仅供空库手动导入，无 DROP TABLE；启动时不会自动建表或导入，未操作现有数据库。

物理迁库时先停止用户写入，再导出最新的 `tb_user`、`tb_user_info`，导入例如 `hmdp_user`，然后修改 `USER_DB_URL`。不要用示例数据替换已有账号。手机号唯一索引必须保留；它是并发注册的最终保障。原单体的内容查询无需访问新用户数据库。

回退时原单体设置 `hmdp.legacy-user.enabled=true`，同时将网关 `USER_SERVICE_URI` 指向原单体。此开关恢复旧 `/user` 控制器、旧 Token 续期，以及提供本地 UserClient 替换 api 的默认 HTTP 实现。回退前应确认旧数据库中存在最新用户数据；当前服务故障不会自动触发回退。

## 可复现验证

先构建父工程及原单体 JAR，在父工程目录运行：

```powershell
mvn '-Dmaven.repo.local=./.maven-repository' install
mvn -f '../hm-dianping-backend/pom.xml' "-Dmaven.repo.local=$($PWD.Path)\.maven-repository" -DskipTests package
python .\scripts\verify_user_service.py --java 'C:\Program Files\Java\jdk1.8.0_202\bin\java.exe' --redis-server 'C:\Users\86156\config\Redis\redis-server.exe' --mysqld 'C:\Program Files\mysql8\mysql-8.0.26-winx64\bin\mysqld.exe' --mysql 'C:\Program Files\mysql8\mysql-8.0.26-winx64\bin\mysql.exe'
```

脚本仅使用 Python 标准库，启动随机端口的独立 MySQL/Redis、真实用户服务、真实原单体和网关，不使用现有数据库。它在用户库导入用户数据，故意不在单体库创建用户表，验证旧内容请求通过远程接口返回资料。

验证包含登录/退出、验证码错误/过期规则及单次使用、注册与并发请求、Token Hash 和续期、资料字段隐私、common 身份恢复、签到、不同会话隔离、内部批量上限，以及作者/点赞/共同关注资料查询。验证结束自动停止进程，日志和测试数据库保留在 `target/auth-check-*` 中。

HTTP 客户端测试已迁入 api 模块。原单体另有 `LegacyUserClientConfigurationTest` 验证本地回退适配；使用 `mvn -Dtest=LegacyUserClientConfigurationTest test` 可单独运行，不需要启动原有数据库集成测试。
