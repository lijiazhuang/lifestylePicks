# lifestylePicks-api

普通 Maven JAR，集中管理服务间调用契约、DTO、接口路径、OpenFeign 客户端和调用配置，不需要单独启动。Feign 使用固定 URL，不需要 Nacos、Eureka 或其他注册中心。

```text
com.lifestylepicks.api
├─ client       UserClient：业务代码注入的调用契约
├─ dto          UserSummary：跨服务展示资料
├─ message      SeckillOrderMessage：RocketMQ 订单消息契约
├─ contract     UserApiPaths：服务端与调用端共用的路径和批量上限
├─ feign        UserFeignClient、FeignUserClient、ApiFeignConfiguration、UserContextRequestInterceptor
├─ config       配置属性和 Boot 自动装配
└─ exception    RemoteCallException
```

当前用户资料远程调用使用 OpenFeign。内容服务和原单体的笔记、点赞、共同关注继续注入 `UserClient`。用户服务同样引用 api 中的 DTO 和路径常量。API 不依赖用户、店铺或原单体的业务实现；数据库访问、Controller 和业务规则继续在各自服务中。

## 引入和使用

继承父工程的新服务添加依赖，不需要写版本：

```xml
<dependency>
    <groupId>com.lifestylepicks</groupId>
    <artifactId>lifestylePicks-api</artifactId>
</dependency>
```

原单体尚未继承新父工程，因此已显式配置 `0.0.1-SNAPSHOT`。先在父工程执行 `mvn install`，让它能从同一个 Maven 仓库解析 api 及 common。

```java
import com.lifestylepicks.api.client.UserClient;
import com.lifestylepicks.api.dto.UserSummary;

@Resource
private UserClient userClient;

List<UserSummary> users = userClient.findBatch(userIds);
```

Boot 应用引入依赖后通过 `META-INF/spring.factories` 自动装配，本模块内部用 `@EnableFeignClients(clients = UserFeignClient.class)` 精确注册客户端，无需在每个服务中重复添加注解或扫描 api 包。OpenFeign starter 版本由父工程 Hoxton.SR12 管理，当前为 2.2.9.RELEASE；调用是同步阻塞的，适用于当前 MVC 服务。

`UserClient` 是业务使用的接口，`FeignUserClient` 保留去重、批量分片、排序和响应校验；`UserFeignClient` 是真正发起 HTTP 请求的声明式接口。保留这层适配，是因为一次业务查询可能需要分成多个 HTTP 请求，不能直接丢掉原有批量逻辑：

```java
@FeignClient(name = "lifestylepicks-user", contextId = "lifestylepicksUserClient",
        url = "${lifestylepicks.api.user-service-uri:${lifestylepicks.user-service-uri:${USER_SERVICE_URI:http://127.0.0.1:8083}}}",
        configuration = ApiFeignConfiguration.class)
public interface UserFeignClient {
    @GetMapping(UserApiPaths.BATCH_USERS)
    JsonNode findBatch(@RequestParam("ids") String ids);
}
```

这里 `name` 只标识客户端，地址来自 `url`，不通过服务名发现实例。Feign 的公共配置仅在客户端子容器生效，不影响其他客户端；不要在 WebFlux 事件循环中调用该阻塞客户端。

模块没有传递 MVC starter、数据库驱动或业务服务依赖。DTO 也不再存放在 common，原 `UserSummary` 已迁到 `com.lifestylepicks.api.dto`；common 继续负责用户上下文、身份 Header 和通用响应。

## 配置和行为

```yaml
lifestylepicks:
  api:
    enabled: true
    user-service-uri: http://127.0.0.1:8083
    connect-timeout: 500ms
    read-timeout: 1s
```

用户地址优先采用 `lifestylepicks.api.user-service-uri`，其次兼容旧 `lifestylepicks.user-service-uri`、环境变量 `USER_SERVICE_URI`，最后使用本地 8083。地址必须为无凭据、查询参数和 fragment 的 HTTP/HTTPS 基础地址，可包含基础路径；现有部署无需修改环境变量。

用户客户端校验输入，去重并按输入顺序返回，单次最多 100 个 ID，自动分片，跳过不存在的用户。HTTP 错误、业务失败或不合法响应转换为 `RemoteCallException`，不会自动回读旧库。`ApiFeignConfiguration` 配置连接与读取超时，默认不隐式重试、不自动跟随重定向。

`UserContextRequestInterceptor` 每次从 common 的 `UserContext` 读取当前 ID，先移除旧的 `X-User-Id`，再写入当前用户；匿名调用完全移除身份头。构建 Feign 时将它加入拦截器链末尾，确保配置中的默认 Header 或其他拦截器不会留下重复身份。构建器使用普通同步 Feign，不通过 Hystrix 线程隔离调用，以保持当前请求的用户上下文。

继续使用上面的 `lifestylepicks.api` 配置即可。如另外设置 Hoxton 对应的 `feign.client.config`，应统一维护超时策略，避免同一个客户端出现两套不同配置；该版本的命名配置 ID 为 `lifestylepicksUserClient`。

异步线程不能自动继承 ThreadLocal，调用前应显式传入或建立并清理任务的用户上下文。用户内部资料查询允许匿名的可信内部调用，因此旧单体的公开笔记查询也能读取作者资料。服务端口应只接受可信网关和内部请求。

调用方可提供自己的 `UserClient` Bean 替换默认实现，此时不注册本模块的用户 Feign 代理；或设置 `lifestylepicks.api.enabled=false` 关闭本模块客户端自动装配。旧单体保留 `LegacyUserClientConfiguration`，只在 `hmdp.legacy-user.enabled=true` 时提供本地回退适配；它没有 HTTP 代码，api 不反向依赖旧业务。

## 后续服务约定

新增远程调用时，在此模块添加对应 DTO、路径契约及 `XxxFeignClient`，接口使用 `@FeignClient`、`@GetMapping`/`@PostMapping` 等注解，并指定可配置的固定 `url`。Feign 接口放在 `feign` 包，复用 `ApiFeignConfiguration`，在自动配置的 `@EnableFeignClients` 中显式注册。若需要分片、排序等适配，可以保留业务 `XxxClient` 接口及适配实现。服务端实现引用共享 DTO/路径，不放入 api 模块；调用方只注入接口。

网关的路由代理仍配置在网关模块；Redis/MySQL 访问仍由业务服务负责。这里集中的是业务服务之间的调用。

## 验证

父工程 `mvn test/install` 会运行 API 测试：批量适配测试验证去重、分片、排序和非法响应；随机本地 HTTP 服务验证真实 Feign 代理的请求路径、参数、地址配置优先级、用户身份隔离、读取超时、不重试错误和不向重定向目标传递身份。此外覆盖自动装配、禁用开关、客户端替换和非法配置。测试不需要注册中心。旧单体的 `LegacyUserClientConfigurationTest` 验证显式回退。

`scripts/verify_user_service.py` 继续用独立 MySQL/Redis、真实用户服务、真实原单体和网关验证远程资料查询，单体测试库不含用户表。重构后的旧单体先用新 api 依赖重新打包，再运行此脚本。

加 `--profile-client content` 可以改为验证真实内容服务 → Feign → 用户服务。此模式只需要父工程内的用户、内容、网关 JAR 和独立 MySQL/Redis，不依赖旧单体或 RocketMQ，也能在单独上传的 `lifestylePicks` 仓库中运行。与其他验证脚本一样，可通过 `--java`、`--redis-server`、`--mysqld`、`--mysql` 指定可执行文件。

本次 Feign 切换已通过父工程 `clean install`、25 项 API 测试，以及 `legacy`、`content` 两种真实 HTTP 联调。两种联调的查询方数据库都不含用户表，验证了作者资料、点赞列表和共同关注通过 Feign 从用户服务获取。未启动注册中心，也未连接 RocketMQ Broker。
