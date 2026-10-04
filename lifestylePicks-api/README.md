# lifestylePicks-api

普通 Maven JAR，集中管理服务间调用契约、DTO、接口路径、HTTP 实现和调用配置，不需要单独启动。

```text
com.lifestylepicks.api
├─ client       UserClient：业务代码注入的调用契约
├─ dto          UserSummary：跨服务展示资料
├─ message      SeckillOrderMessage：RocketMQ 订单消息契约
├─ contract     UserApiPaths：服务端与调用端共用的路径和批量上限
├─ http         HttpUserClient、ApiRestTemplateFactory
├─ config       配置属性和 Boot 自动装配
└─ exception    RemoteCallException
```

当前用户资料 HTTP 调用已全部迁入此模块，原单体的笔记、点赞、共同关注改为注入 `UserClient`。用户服务同样引用 api 中的 DTO 和路径常量。API 不依赖用户、店铺或原单体的业务实现；数据库访问、Controller 和业务规则继续在各自服务中。

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

Boot 应用引入依赖后通过 `META-INF/spring.factories` 自动装配，无需额外扫描 api 包。调用方已有 Spring Web、Jackson 和 `RestTemplateBuilder` 时生效。默认实现是同步 `RestTemplate`，适用于当前 MVC 服务；不要在 WebFlux 事件循环中直接调用阻塞客户端。

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

用户地址优先采用 `lifestylepicks.api.user-service-uri`，其次兼容旧 `lifestylepicks.user-service-uri`、环境变量 `USER_SERVICE_URI`，最后使用本地 8083。现有部署无需修改环境变量。

用户客户端校验输入，去重并按输入顺序返回，单次最多 100 个 ID，自动分片，跳过不存在的用户。HTTP 错误、业务失败或不合法响应转换为 `RemoteCallException`，不会自动回读旧库。通用传输工厂设置超时，并在每次请求时从 common 的 `UserContext` 读取当前 ID、覆盖 `X-User-Id`；匿名调用清除身份头，避免复用客户端时串用身份。

异步线程不能自动继承 ThreadLocal，调用前应显式传入或建立并清理任务的用户上下文。用户内部资料查询允许匿名的可信内部调用，因此旧单体的公开笔记查询也能读取作者资料。服务端口应只接受可信网关和内部请求。

调用方可提供自己的 `UserClient` Bean 替换默认实现，或设置 `lifestylepicks.api.enabled=false` 关闭自动装配。旧单体保留 `LegacyUserClientConfiguration`，只在 `hmdp.legacy-user.enabled=true` 时提供本地回退适配；它没有 HTTP 代码，api 不反向依赖旧业务。

## 后续服务约定

新增远程调用时，在此模块添加对应 `XxxClient`、DTO 和路径契约，HTTP 实现放到 `http` 包，使用 `ApiRestTemplateFactory` 复用超时及身份传递策略，并在自动配置中注册客户端。服务端实现引用共享 DTO/路径，但不放入 api 模块。调用方只注入接口，不在 Service/Controller 中新建 RestTemplate、WebClient 或手写远程 URL。

网关的路由代理仍配置在网关模块；Redis/MySQL 访问仍由业务服务负责。这里集中的是业务服务之间的调用。

## 验证

父工程 `mvn test/install` 会运行 API 测试，覆盖自动装配、地址兼容、配置优先级、客户端替换、超时校验、批量分片和排序、错误响应及身份头清理。旧单体的 `LegacyUserClientConfigurationTest` 验证显式回退。

`scripts/verify_user_service.py` 继续用独立 MySQL/Redis、真实用户服务、真实原单体和网关验证远程资料查询，单体测试库不含用户表。重构后的旧单体先用新 api 依赖重新打包，再运行此脚本。
