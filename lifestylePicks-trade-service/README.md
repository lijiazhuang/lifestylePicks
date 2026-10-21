# lifestylePicks-trade-service

默认端口 8085，管理优惠券、秒杀券和订单。异步保存订单、扣数据库库存已改为 RocketMQ，当前交易服务不再读写 Redis Stream；订单保存与扣库仍使用一个本地事务。

## RocketMQ 配置

```yaml
rocketmq:
  name-server: 192.168.221.131:9876
  producer:
    group: seckill_order_producer_group
  consumer:
    group: seckill_order_consumer_group
lifestylepicks:
  trade:
    order-topic: seckill_order_topic
```

默认使用现有虚拟机 192.168.221.131 的 NameServer，可通过 ROCKETMQ_NAME_SERVER、ROCKETMQ_PRODUCER_GROUP、ROCKETMQ_CONSUMER_GROUP、SECKILL_ORDER_TOPIC 覆盖。虚拟机 Broker 需要存在该 Topic，或具备合适的自动创建策略。

127.0.0.1 指交易服务所在机器。如果交易服务在 Windows 宿主机运行、MQ 在虚拟机中，需改成可访问的虚拟机 IP，且 Broker 返回的通信地址也应可访问；交易服务同样在虚拟机内运行时可使用本地地址。

使用 Maven 客户端依赖 rocketmq-spring-boot-starter:2.2.3，连接现有 Docker RocketMQ 5.2.0，不额外下载或部署服务端。

## Docker 连接和存储排查

发送异常需要看完整 cause 链；OrderMessagePublisher 现在保留异常堆栈，而不是只记录异常类名。

- NameServer 能连通不等于 Broker 能连通。使用 `mqadmin topicRoute` 核对 `brokerAddrs`，宿主机访问此虚拟机时应返回 `192.168.221.131:10911`。`127.0.0.1:10911` 会让 Windows 客户端连接自身。Broker 配置 `brokerIP1=192.168.221.131`，端口映射和防火墙必须允许该地址；Docker 启动命令不能再次生成旧回环地址。
- `MQBrokerException CODE: 14` 且提示 `disk is full` 时，先检查消息存储所在文件系统，而不是扩大发送超时或关闭磁盘保护。2026-10-07 修复时 VM 根分区使用率 99%，主要可释放占用为约 2.8GB 的历史 POP 日志。20 个已轮转日志被压缩校验并保留为 `.gz`，未清理消息存储或数据库；POP 轮转上限调整为每份 20MB、最多 5 份，避免原 128MB × 20 份再次占满空间。
- 当前部署的 Compose 文件为 `D:\project\agent\ragent\resources\docker\rocketmq-stack-5.2.0.compose.yaml`，已修正启动 IP 并加入 POP 日志限制。当前 Broker 容器保留原数据，通过在线更新和带备份的启动入口修正实现恢复；未通过重建容器恢复服务。

## 异步流程

```text
网关鉴权 → common UserContext
  → Redis Lua 校验时间/库存/重复购买，预扣库存并记录 PENDING 与待投递 ID
  → 后台投递到 RocketMQ，等待 Broker 确认
  → RocketMQ 监听器读取消息中的 userId
  → OrderWriter 在本地事务中扣数据库库存、创建订单
  → 更新 SUCCESS，完成消费
```

SeckillOrderMessage 位于 api 的 message 包，字段为 orderId/userId/voucherId。接口仍快速返回字符串订单 ID；GET /voucher-order/{id} 只查询自己的结果。成功受理不等于数据库订单已落地。本阶段没有新增支付、退款或核销接口。

## 发送与消费可靠性

Redis 保留库存、买家集合、时间元数据和状态。Lua 原子写入预留和 seckill:outbox 有序集合，没有 XADD/XREADGROUP/XACK 操作。outbox 只用于向 MQ 补发，业务消费与落库仅由 MQ 监听器完成。

当前 YAML 配置为每轮执行结束后等待 500ms，每批最多 300 条。间隔使用 `lifestylepicks.trade.dispatch-interval`（毫秒），数量使用 `lifestylepicks.trade.dispatch-batch-size`，也可通过环境变量 `SECKILL_DISPATCH_BATCH_SIZE` 配置；数量必须大于零。发送仍为逐条同步发送，因此实际周期还包含整批发送和 Redis 操作耗时，不能用批量大小除以等待间隔当作投递吞吐。

发送超时 3 秒。未确认后按每条订单独立的指数退避补发，只有 SEND_OK 才移除待投递记录。发送超时可能已经送达 Broker，因此不盲目退库存，而是补发同一订单，消费者幂等处理。[发送重试说明](https://rocketmq.apache.org/docs/featureBehavior/05sendretrypolicy/)

### 发送端指数退避与全局告警

以下配置均位于 `lifestylepicks.trade`：

| 配置 | 默认值 | 含义 |
|---|---|---|
| dispatch-retry-delay | 5s | 首次投递失败后的基础等待时间 |
| dispatch-retry-max-delay | 5m | 基础等待时间翻倍的上限，不能小于首次等待时间 |
| publish-retry-alert-window | 5m | 所有订单补发失败的滑动统计窗口 |
| publish-retry-alert-threshold | 100 | 窗口内补发失败次数的告警阈值 |
| publish-retry-alert-cooldown | 5m | 同一个 outbox 的全局 ERROR 告警冷却时间 |

订单 Hash `seckill:status:{orderId}` 的 `publishFailCount` 记录完整应用层投递失败次数，包含首次失败，兼容旧 PENDING 记录缺少该字段的情况。每次基础延时按 `5s → 10s → 20s → 40s → 80s → 160s → 300s → 300s…` 增长，再随机取其 80%–100%；例如首次实际等待约 4–5 秒，达到上限后实际等待约 4–5 分钟。下一次投递时间保存在 ZSET score 中，不使用线程休眠，不改变普通投递器扫描间隔。

首次投递失败仍只记 WARN；同一订单从第二次投递失败起才写入全局窗口。每次应用层 `syncSend` 内部可能还有 SDK 重试，这些网络尝试不单独计数。告警使用 `SECKILL_ORDER_PUBLISH_RETRY_ALERT` 标记，包含 Topic、outbox、窗口、失败次数、阈值、待投递数量、当前订单 ID、当前订单失败次数和下次重试时间。它是本地 ERROR 日志，需人工查看或由日志平台采集。

窗口 ZSET 为 `{outbox-key}:publish-retry-failures`，每次失败事件使用独立 ID，移除窗口外数据，并设置与窗口相同的过期时间。冷却标记为 `{outbox-key}:publish-retry-alert`。计数、调度、窗口更新和告警冷却领取在同一段 Lua 中完成，同一业务的多个实例共享统计和冷却；独立 outbox 则相互隔离。冷却标记先在 Redis 中领取，再记录日志，因此采用“每个冷却期最多一次”的告警方式，不是持久化告警投递服务。

达到阈值后仍保留原预留、原订单 ID 和 outbox 记录，继续退避补发，不自动关闭订单或回退库存。SUCCESS/FAILED 不再增加次数或重新调度；缺失状态、错误 key 类型等异常不删除待投递记录。Redis 无法登记重试时输出 `SECKILL_ORDER_PUBLISH_DEFER_FAILED`，保留数据供恢复后检查。MQ 已确认成功、仅 Redis 清理失败时使用独立的 `SECKILL_ORDER_PUBLISH_CLEANUP_FAILED` 日志，不误计为 MQ 补发失败。

退避降低重试压力，不能阻止 MQ 故障期间不断受理新订单造成的积压；本次没有引入入口熔断、自动补偿或单订单次数耗尽后停止重试。

消费采用集群模式，异常抛出交给 RocketMQ 重试，最多 16 次后进入 %DLQ%seckill_order_consumer_group。新组从已有消息开始读取，已有组按 Broker 进度继续。[消费重试说明](https://rocketmq.apache.org/docs/4.x/consumer/02push/)

OrderWriter 用分布式锁和数据库唯一索引保证幂等；锁持有到事务提交。数据库失败回滚库存并抛异常。提交后 Redis 更新失败也重试，已有订单不会再次扣库。确认数据库库存不足时标记 FAILED、释放买家资格并把 Redis 库存收敛到 0；重复 FAILED 消息不再写库。

PENDING 不设 TTL，避免长时间停机后不能补发；SUCCESS/FAILED 保留 7 天。发送确认后由 Broker 保证投递，消费重试耗尽的消息需人工检查并重放 DLQ，状态不会自动假装订单成功。

### 死信日志告警

`SeckillOrderDeadLetterListener` 订阅 `%DLQ%${rocketmq.consumer.group}`，使用独立告警组 `${rocketmq.consumer.group}_dead_letter_alarm`，与正常订单消费者分开。默认分别为 `%DLQ%seckill_order_consumer_group` 和 `seckill_order_consumer_group_dead_letter_alarm`；变更业务消费组后会自动跟随。

正常消费者的 `maxReconsumeTimes=16` 是消费失败的重试上限；Broker 重试耗尽后转入死信队列。这不是生产者发送失败重试 16 次，当前发送端的 defer 补发流程保持原样。

死信消费者接收原始 `MessageExt`，即使正文不是合法订单 JSON，也能用 ERROR 日志记录 `SECKILL_ORDER_DEAD_LETTER`、原 Topic、消息 ID、原消息 ID、消息 keys（正常订单为 orderId）、重试次数、队列位置、原始时间和 UTF-8 正文。记录完成后正常确认消费，只作为人工处理入口，不更新订单状态、不扣退库存、不自动重放。它不是短信/邮件告警服务；需通过应用日志查看或由日志平台采集。

新告警组从第一条已有死信读取；已有组重启后按保存的进度继续。正常确认不会立即删除 Broker 中的物理消息，消息仍受 Broker 保留策略约束；人工重放前先核对数据库订单及 Redis 预留，避免误操作。

死信 Topic 必须具备读取权限。当前 RocketMQ 5.2.0 的正常死信创建路径包含读写权限，但历史 Topic 配置可能不同，也可能尚未产生第一条死信。可用 `mqadmin topicRoute` 检查实际 Topic；若不存在或缺少读取权限，在现有虚拟机执行以下命令准备一个读写队列（只配置 Topic，不发送订单、不清空消息）：

```sh
docker exec rmqbroker sh /home/rocketmq/rocketmq-5.2.0/bin/mqadmin updateTopic \
  -n 192.168.221.131:9876 -b 192.168.221.131:10911 \
  -t '%DLQ%seckill_order_consumer_group' -r 1 -w 1 -p 6
```

`6` 为读写权限；如果业务消费组、Broker 地址或容器名变更，应同步替换。这里只调整该死信 Topic 的权限，不调整 Broker 全局权限或 ACL；启用 ACL 的部署还需授权告警组读取。没有读取权限时消费者可能无法收到死信，不能只检查服务启动成功。

本次验证：交易服务离线构建成功，原有 10 项消息逻辑测试和新增 2 项死信监听器测试通过；未运行真实 Broker 的重试耗尽链路，Docker 管理连接在检查时被远端关闭。没有向业务 Topic 注入失败消息，也没有调整虚拟机配置。相关实现依据：[Spring 2.2.3 原始消息转换](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.2.3/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/support/DefaultRocketMQListenerContainer.java)、[Broker 5.2.0 死信创建](https://github.com/apache/rocketmq/blob/rocketmq-all-5.2.0/broker/src/main/java/org/apache/rocketmq/broker/processor/SendMessageProcessor.java)。

未确认的记录仍依赖 Redis 持久化和可用性，没有引入数据库 outbox 或跨存储事务。需要关注 seckill:outbox 积压及 RocketMQ 重试/DLQ。

## 数据和切换

默认复用 hmdp schema，启动不自动执行 DDL。db/trade.sql 供空库导入，已有订单表应核对并补 (user_id,voucher_id) 唯一索引，见 db/upgrade_trade.sql。本次没有修改现有数据库。

切换前停止旧 Stream 消费者并处理 stream.orders 的待消费/未确认记录，再切换到 RocketMQ。新服务不自动导入或删除旧 Stream，单体消费者仍默认关闭，不应同时启用两套链路。

其余变量保持原样：TRADE_PORT、TRADE_DB_URL/USERNAME/PASSWORD、REDIS_HOST/PORT/DATABASE/PASSWORD。TRADE_MQ_ENABLED 默认 true，可关闭消息组件，但关闭后不会异步落库。无 Broker 的测试还显式排除 RocketMQAutoConfiguration；仅关闭监听器不能代替关闭客户端自动配置。

## 验证

发送端退避与告警可在父工程目录运行 `scripts/verify_publish_retry.py`，使用 `--java`、`--redis-server`、`--maven` 提供本机可执行文件路径。脚本只启动临时独立端口的 Redis，关闭持久化，并运行离线 Maven/JUnit；退出时停止自己创建的 Redis，不连接现有 Redis、MySQL 或 RocketMQ，不执行 FLUSHDB。日志保存在交易服务 `target/publish-retry-verify-*`。

本次该脚本构建成功、25 项测试通过：真实独立 Redis 集成测试 8 项、消息逻辑测试 13 项、死信日志测试 2 项、配置校验测试 2 项。覆盖退避范围与封顶、旧状态兼容、首次失败排除、跨订单窗口、并发计数和冷却去重、窗口过期、终态不重排、错误元数据保留、独立 outbox 隔离，以及发送确认后清理失败不误计数。没有向正式 Topic 注入失败消息。

父工程 mvn install 执行模拟 MQ 的单元测试，不连接 Broker。

在父工程运行 scripts/verify_rocketmq_logic.py，并按本机路径提供 --java、--redis-server、--mysqld、--mysql。脚本只启动独立 MySQL/Redis 和 JUnit，模拟 RocketMQTemplate、直接调用消费者处理器，不启动 RocketMQ。

覆盖发送失败保留预留、确认丢失但已消费不退库、重复消费不重复扣库、SQL 插入失败回滚库存、永久库存拒绝。测试使用独立数据库，不连接现有数据。

verify_final_services.py 必须提供 --rocketmq-name-server 和 --rocketmq-topic，供连接已有专用测试 Broker/Topic。它使用独立测试组，不安装 Broker；该全量脚本尚未作为本次修复验证运行。

2026-10-07 本次修复已通过 10 项订单消息单元测试，并用项目现有客户端从 Windows 向专用诊断 Topic 真实发送和读取回验，得到 SEND_OK 且正文一致。原失败订单 `645837908791525377` 自动补发后 Redis 状态为 SUCCESS，交易服务查询确认数据库中已存在同 ID 的未支付订单；专用诊断 Topic 和消费组测试后清理。此验证不代表容量压测或支付成功。
