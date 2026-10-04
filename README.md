# lifestylePicks

黑马点评微服务改造的 Maven 父工程，业务已经拆为用户、店铺、内容和交易四个服务，另有网关、common 和 api。

```text
lifestylePicks/
├─ pom.xml                         父工程：聚合模块并管理依赖版本
├─ lifestylePicks-common/           普通 JAR：身份 Header、UserContext、MVC 自动配置
├─ lifestylePicks-api/              普通 JAR：调用契约、DTO、HTTP 客户端及自动配置
├─ lifestylePicks-shop-service/     店铺和分类、MyBatis、缓存与 GEO，默认端口 8082
├─ lifestylePicks-user-service/     登录、资料和签到，默认端口 8083
├─ lifestylePicks-content-service/  笔记、关注、Feed、评论和上传，默认端口 8084
├─ lifestylePicks-trade-service/    优惠券、秒杀、订单，默认端口 8085
└─ lifestylePicks-gateway/
   ├─ pom.xml                      网关依赖及打包配置
   └─ src/main/
      ├─ java/com/lifestylepicks/gateway/GatewayApplication.java
      └─ resources/application.yaml
```

## 版本与边界

- Java 8、Spring Boot 2.3.12.RELEASE、Spring Cloud Hoxton.SR12。
- 该组合与原后端版本对齐，兼容依据见 [Spring 官方说明](https://spring.io/blog/2021/07/07/spring-cloud-hoxton-sr12-has-been-released/)。它是旧版迁移基线，已结束常规维护；生产部署前应单独安排版本升级。
- 原来的 `hm-dianping-backend` 仍是独立项目，尚未迁入此父工程。
- 网关负责转发、Redis Token 续期和登录校验，通过 `X-User-Id` 向下游传递可信用户 ID。具体业务权限由下游服务检查。
- 原后端暂时保留 Token 恢复以兼容现有 `UserHolder`，默认不再续期；用户登录由用户服务处理，网关仍转发 `authorization`。
- 网关基于 WebFlux/Netty，不要加入 `spring-boot-starter-web` 或数据库依赖。
- 当前通过固定地址连接后端，网关连接登录 Redis，不依赖注册中心或 MySQL。

## 构建与启动

在 `lifestylePicks` 目录执行。此版本基线请使用 JDK 8；当前机器默认 Java 是 25，可在当前 PowerShell 会话切换：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk1.8.0_202'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn '-Dmaven.repo.local=./.maven-repository' clean package
java -jar .\lifestylePicks-gateway\target\lifestylePicks-gateway-0.0.1-SNAPSHOT.jar
```

依赖缓存放在父工程的 `.maven-repository` 中，已加入 Git 忽略规则，避免构建写入机器上其他 Maven 仓库目录。

IDEA 中将根 `pom.xml` 导入为 Maven 项目，项目 SDK 和 Maven Runner 设为 JDK 8，运行 `GatewayApplication`。

可通过环境变量配置端口和后端地址：

```powershell
$env:GATEWAY_PORT = '10010'
$env:SHOP_SERVICE_URI = 'http://127.0.0.1:8082'
$env:USER_SERVICE_URI = 'http://127.0.0.1:8083'
$env:CONTENT_SERVICE_URI = 'http://127.0.0.1:8084'
$env:TRADE_SERVICE_URI = 'http://127.0.0.1:8085'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
# Redis 配置了密码时再设置：$env:REDIS_PASSWORD = '实际密码'
```

各服务 URI 使用 HTTP/HTTPS 基础地址，不附带 `/api` 前缀。网关正常配置没有 MONOLITH_URI 路由。

## 请求流程

```text
浏览器 localhost:8080/api/shop/1
  → Nginx 去掉 /api 前缀
  → 网关 localhost:10010/shop/1
  → 店铺服务 localhost:8082/shop/1
```

也可以直接请求网关 `http://localhost:10010/api/shop/1`：`shop-api` 路由会去掉一层 `/api`。直接请求 `/shop/1` 则由 `shop-direct` 路由原样转发。用户、店铺、内容、交易分别进入对应服务，正常配置没有单体兜底路由，未知路径返回 404。请求方法、查询参数、请求体及 `authorization` 头随请求转发。

现有 Nginx 的 `/api` 上游已配置为 `127.0.0.1:10010`。启动顺序为 MySQL/Redis、四个业务服务、网关、Nginx，正常运行不需要原单体。Nginx 另将不存在于旧静态目录的新 `/imgs` 图片交给内容服务；若 Nginx 已在运行，需在它的目录执行 `./nginx.exe -t` 后再执行 `./nginx.exe -s reload`。

网关存活检查：

```powershell
Invoke-RestMethod http://localhost:10010/actuator/health
Invoke-RestMethod http://localhost:10010/api/shop-type/list
```

健康接口检查网关和登录 Redis，不检查后端。Redis 使用 PING 检查，避免 Windows Redis INFO 返回路径造成的解析兼容问题。各业务接口依赖对应服务，内容的展示资料还需要用户服务。

## 两层登录过滤器

```text
RefreshTokenFilter（order = -200）
  → 删除客户端 X-User-Id
  → 用 authorization 查询 login:token:{token} 的 Hash id
  → Lua 原子读取用户 ID 并续期，登录态保存到 exchange.attributes
LoginAuthFilter（order = -100）
  → 按 HTTP 方法和路径匹配公开接口
  → 需要登录但未登录：返回 401；Redis 不可用：返回 503
  → 已登录：写入 X-User-Id，转发下游
```

第一层不因为缺失或过期 Token 拒绝请求。Redis 查询使用响应式 API，不使用 ThreadLocal 或阻塞调用。失效 Token 不会重新创建，有效 Token 的 TTL 从当前请求重新计算。

第二层默认要求登录，只公开 POST 验证码/登录、GET/HEAD 店铺详情与列表、店铺分类、优惠券列表和热门笔记。上传、修改店铺、添加优惠券和秒杀需要登录。公开接口有有效 Token 时也会传递用户 ID；未登录访问公开接口时不会携带身份头。

两种入口 `/api/user/me` 和 `/user/me` 使用同一套权限规则。规则配置使用不带 `/api` 的业务路径。白名单和时长在 `application.yaml` 的 `lifestylepicks.auth` 下配置：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `token-key-prefix` | `login:token:` | 与原后端登录 Hash 格式一致 |
| `token-ttl` | `36000m` | 沿用原项目 36000 分钟，即 25 天 |
| `redis-timeout` | `1s` | 单次登录态查询的总等待上限 |
| `public-endpoints` | 配置中的方法和路径列表 | 未列出的请求默认要求登录 |

调整 TTL 时应同时配置网关和用户服务的 `TOKEN_TTL`。原单体默认不再续期；只有显式回退到旧用户模式时才恢复旧续期。`REDIS_DATABASE` 默认为 0，应与用户服务使用同一个 Redis 实例、数据库和 key 前缀。

401 响应沿用 `{"success":false,"errorMsg":"请先登录"}` 结构，当前前端已自动跳转 `/login.html`。Redis 故障时公开接口按匿名请求放行，受保护接口不会绕过登录校验。

后续 MVC 微服务引入 common 后会自动读取 `X-User-Id`，保存并清理自己的用户上下文，使用方式见下文。部署时必须限制外部绕过网关直连业务服务，否则单纯信任此 Header 无法防止身份伪造。两个 GlobalFilter 处理匹配到业务路由的请求；本地 `/actuator/health` 不经过这两个过滤器，健康详情默认不公开。

## 公共用户上下文

`lifestylePicks-common` 是普通 Maven JAR，不是需要启动的服务。网关已引入它并复用 `UserHeaders.USER_ID`，后续每个 Spring MVC 业务服务也应引入：

```xml
<dependency>
    <groupId>com.lifestylepicks</groupId>
    <artifactId>lifestylePicks-common</artifactId>
</dependency>
```

继承本父工程的服务无需写依赖版本。独立工程需要指定 `0.0.1-SNAPSHOT`，并先将父工程和 common 安装到同一个本地仓库，或发布到团队仓库。在父工程目录可执行：

```powershell
mvn '-Dmaven.repo.local=./.maven-repository' install
```

common 通过 Boot 2 的 `META-INF/spring.factories` 自动加载配置，业务服务无需添加 `@ComponentScan`、手动注册拦截器或 `@EnableWebMvc`。消费端需要已有 `spring-boot-starter-web`。common 的 MVC 依赖是可选依赖，不会传递到 WebFlux 网关；自动配置也只在 Servlet/MVC 应用中生效。

```text
网关 X-User-Id: 42
  → 业务服务 UserContextInterceptor.preHandle
  → UserContext.setUser(new UserInfo(42L))
  → Controller / Service 获取当前用户
  → afterCompletion 清理 ThreadLocal
```

业务代码使用统一命名 `UserContext`：

```java
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.context.UserInfo;

Long userId = UserContext.getUserId();
UserInfo user = UserContext.getUser();
```

网关目前只传递用户 ID，因此 `UserInfo` 只有 `userId`，昵称、头像等资料应通过用户服务按需获取。匿名请求的 `getUser()` 和 `getUserId()` 返回 null，common 拦截器允许匿名请求经过，不重复实现网关的登录规则。无效、重复或超出 Long 范围的身份 Header 返回 400。

拦截器在每次请求开始时先清理旧上下文，在请求完成或业务异常时再次清理。MVC 异步释放原请求线程时通过 `afterConcurrentHandlingStarted` 清理，异步重新分派时重新读取 Header。ThreadLocal 不会自动进入线程池、Callable、MQ 消费线程，也不适用于 WebFlux；异步任务应在提交前捕获 userId 并作为参数传入。单纯引入 common 不会自动传播 HTTP Header；使用 api 的调用工厂时，会根据当前 UserContext 传递可信身份。

特殊服务可以用 `lifestylepicks.common.user-context.enabled=false` 关闭自动注册，或提供自己的 `UserContextInterceptor` Bean 替换默认实现。当前没有把原单体 `UserHolder` 替换为 `UserContext`，它在业务服务迁移时再逐步切换。

common 的 Maven 测试覆盖引入依赖后自动注册、MVC 请求身份恢复、匿名请求与异常清理、无效和重复身份、异步线程边界，以及非 Web/Reactive 应用不注册拦截器。测试消费端位于 common 包之外，以验证无需扩大组件扫描范围。

## 可复现验证

先完成 Maven 打包，再运行脚本。脚本仅使用 Python 标准库，启动独立端口、无持久化的 Redis 和模拟 HTTP 后端，自动停止验证进程，不访问现有 Redis 数据。

```powershell
python .\scripts\verify_gateway_auth.py --java 'C:\Program Files\Java\jdk1.8.0_202\bin\java.exe' --redis-server 'C:\Users\86156\config\Redis\redis-server.exe'
```

其他机器按安装位置调整两个可执行文件路径；若已在 PATH 中，可省略对应参数。运行日志保留在 `lifestylePicks-gateway/target/auth-check-*` 中。

验证覆盖真实 Redis 续期、过期/无效 Token、401/503、公开接口方法限制、两种路径入口、伪造身份头清除、公开接口传递有效身份、并发用户隔离，以及 Redis 停止后的处理。模拟后端只用于检查网关行为，不代表原单体的登录、数据库和秒杀业务已经联调。

## 后续拆分

现有和后续服务间调用集中在 [api 模块](lifestylePicks-api/README.md)。原单体的用户 HTTP 客户端已经移入 api，业务代码只注入 `UserClient`；用户服务共享 api 中的 DTO 和路径。

四个业务服务拆分已完成，接口、数据库配置和回退步骤见 [店铺](lifestylePicks-shop-service/README.md)、[用户](lifestylePicks-user-service/README.md)、[内容](lifestylePicks-content-service/README.md)、[交易](lifestylePicks-trade-service/README.md) 说明。服务进程独立，默认暂时复用 `hmdp` 数据库，各自只访问自己的表；尚未对现有数据库执行物理迁库。原单体业务控制器及订单消费者默认关闭，只保留显式回退开关。

新增业务服务后，在父 POM 的 `modules` 中加入模块，引入 common；有服务间调用或共享契约时引入 api，客户端统一写入 api，再在网关中增加优先级更高的业务路由。

每个新服务同时覆盖带 `/api` 和不带前缀的入口，前者使用 `StripPrefix=1`。本次已移除单体兜底路由；应急回退通过明确修改对应服务 URI 和旧单体开关完成。

## 四服务整体联调

父工程构建后，在本目录执行（其他机器调整可执行文件路径）：

```powershell
python .\scripts\verify_final_services.py --rocketmq-name-server '127.0.0.1:9876' --rocketmq-topic 'seckill_order_verify_topic' --java 'C:\Program Files\Java\jdk1.8.0_202\bin\java.exe' --redis-server 'C:\Users\86156\config\Redis\redis-server.exe' --mysqld 'C:\Program Files\mysql8\mysql-8.0.26-winx64\bin\mysqld.exe' --mysql 'C:\Program Files\mysql8\mysql-8.0.26-winx64\bin\mysql.exe'
```

交易异步链路已改为 RocketMQ。整体脚本现在需显式增加 --rocketmq-name-server 和 --rocketmq-topic，连接已有专用测试 Broker/Topic，不会安装 RocketMQ；它使用独立 MySQL/Redis、独立 schema 和 MQ 测试组。本次只执行交易服务说明中的无 Broker 验证，未连接虚拟机 RocketMQ。

RocketMQ 默认地址 127.0.0.1:9876，生产组 seckill_order_producer_group，消费组 seckill_order_consumer_group，Topic 为 seckill_order_topic。配置及补发、消费重试说明见 [交易服务](lifestylePicks-trade-service/README.md)。
