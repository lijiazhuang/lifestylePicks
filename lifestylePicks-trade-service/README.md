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

默认每秒扫描一次，每批最多 100 条，发送超时 3 秒，未确认后 5 秒再试。只有 SEND_OK 才移除待投递记录。发送超时可能已经送达 Broker，因此不盲目退库存，而是补发同一订单，消费者幂等处理。[发送重试说明](https://rocketmq.apache.org/docs/featureBehavior/05sendretrypolicy/)

消费采用集群模式，异常抛出交给 RocketMQ 重试，最多 16 次后进入 %DLQ%seckill_order_consumer_group。新组从已有消息开始读取，已有组按 Broker 进度继续。[消费重试说明](https://rocketmq.apache.org/docs/4.x/consumer/02push/)

OrderWriter 用分布式锁和数据库唯一索引保证幂等；锁持有到事务提交。数据库失败回滚库存并抛异常。提交后 Redis 更新失败也重试，已有订单不会再次扣库。确认数据库库存不足时标记 FAILED、释放买家资格并把 Redis 库存收敛到 0；重复 FAILED 消息不再写库。

PENDING 不设 TTL，避免长时间停机后不能补发；SUCCESS/FAILED 保留 7 天。发送确认后由 Broker 保证投递，消费重试耗尽的消息需人工检查并重放 DLQ，状态不会自动假装订单成功。

未确认的记录仍依赖 Redis 持久化和可用性，没有引入数据库 outbox 或跨存储事务。需要关注 seckill:outbox 积压及 RocketMQ 重试/DLQ。

## 数据和切换

默认复用 hmdp schema，启动不自动执行 DDL。db/trade.sql 供空库导入，已有订单表应核对并补 (user_id,voucher_id) 唯一索引，见 db/upgrade_trade.sql。本次没有修改现有数据库。

切换前停止旧 Stream 消费者并处理 stream.orders 的待消费/未确认记录，再切换到 RocketMQ。新服务不自动导入或删除旧 Stream，单体消费者仍默认关闭，不应同时启用两套链路。

其余变量保持原样：TRADE_PORT、TRADE_DB_URL/USERNAME/PASSWORD、REDIS_HOST/PORT/DATABASE/PASSWORD。TRADE_MQ_ENABLED 默认 true，可关闭消息组件，但关闭后不会异步落库。无 Broker 的测试还显式排除 RocketMQAutoConfiguration；仅关闭监听器不能代替关闭客户端自动配置。

## 验证

父工程 mvn install 执行模拟 MQ 的单元测试，不连接 Broker。

在父工程运行 scripts/verify_rocketmq_logic.py，并按本机路径提供 --java、--redis-server、--mysqld、--mysql。脚本只启动独立 MySQL/Redis 和 JUnit，模拟 RocketMQTemplate、直接调用消费者处理器，不启动 RocketMQ。

覆盖发送失败保留预留、确认丢失但已消费不退库、重复消费不重复扣库、SQL 插入失败回滚库存、永久库存拒绝。测试使用独立数据库，不连接现有数据。

verify_final_services.py 必须提供 --rocketmq-name-server 和 --rocketmq-topic，供连接已有专用测试 Broker/Topic。它使用独立测试组，不安装 Broker；该全量脚本尚未作为本次修复验证运行。

2026-10-07 本次修复已通过 10 项订单消息单元测试，并用项目现有客户端从 Windows 向专用诊断 Topic 真实发送和读取回验，得到 SEND_OK 且正文一致。原失败订单 `645837908791525377` 自动补发后 Redis 状态为 SUCCESS，交易服务查询确认数据库中已存在同 ID 的未支付订单；专用诊断 Topic 和消费组测试后清理。此验证不代表容量压测或支付成功。
