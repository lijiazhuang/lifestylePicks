# lifestylePicks-trade-service

默认端口 8085，管理优惠券、秒杀券和订单。异步保存订单、扣数据库库存已改为 RocketMQ，当前交易服务不再读写 Redis Stream；订单保存与扣库仍使用一个本地事务。

## RocketMQ 配置

```yaml
rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: seckill_order_producer_group
  consumer:
    group: seckill_order_consumer_group
lifestylepicks:
  trade:
    order-topic: seckill_order_topic
```

默认值与用户提供的配置一致，可通过 ROCKETMQ_NAME_SERVER、ROCKETMQ_PRODUCER_GROUP、ROCKETMQ_CONSUMER_GROUP、SECKILL_ORDER_TOPIC 覆盖。虚拟机 Broker 需要存在该 Topic，或具备合适的自动创建策略。

127.0.0.1 指交易服务所在机器。如果交易服务在 Windows 宿主机运行、MQ 在虚拟机中，需改成可访问的虚拟机 IP，且 Broker 返回的通信地址也应可访问；交易服务同样在虚拟机内运行时可使用本地地址。

只引入 Maven 客户端依赖 rocketmq-spring-boot-starter:2.2.3，没有下载或部署 RocketMQ 服务端，本次没有连接虚拟机 Broker。

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

verify_final_services.py 现在必须提供 --rocketmq-name-server 和 --rocketmq-topic，供后续连接已有专用测试 Broker/Topic。它使用独立测试组，不安装 Broker；本次没有执行真实 Broker 联调。
