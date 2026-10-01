# canopen-stack-java

进程内本地 CANopen 协议栈与多节点仿真服务。协议栈运行在模拟 CAN 总线之上，
不依赖任何真实 CAN 硬件，覆盖节点生命周期、对象字典、SDO、PDO、心跳、紧急报文、
故障注入与重启恢复。事件全部在单一总线串行锁内处理，因而在故障与并发下行为确定、
无跨节点锁序死锁。

## 功能范围

### 对象字典（Object Dictionary）
- 条目按 `索引:子索引` 寻址，支持 1/8/16/32/64 位有符号、无符号整数、布尔、
  可见字符串与 DOMAIN（变长字节域）。
- 每次访问均校验：条目/子索引存在性、读/写权限、类型兼容与数值范围（可配 min/max）。
- 非法访问抛出携带标准 SDO 中止码的 `OdException`，并经 SDO 通道返回对端。

### NMT 节点生命周期
- 四状态：`INITIALISATION`、`PRE_OPERATIONAL`、`OPERATIONAL`、`STOPPED`。
- 命令：进入 OP、停止、进入 PRE-OP、复位节点、复位通信（支持节点单播与 0 广播）。
- 通信行为受状态限制：
  - INIT/STOPPED：仅接受 NMT/心跳；STOPPED 抑制 SDO、PDO 与 EMCY 帧；
  - PRE-OP：允许 SDO，不允许 PDO 过程数据；
  - OPERATIONAL：允许 SDO 与 PDO；EMCY 在 PRE-OP/OP 发送。
- 状态迁移、帧接收与定时事件共用同一串行锁，与进行中的传输并发时结果确定。

### SDO 传输
- 不超过 4 字节走加速传输（expedited），超过 4 字节走分段传输（segmented）。
- 分段传输严格校验 toggle 位；重复的分段请求/应答被幂等处理（重发上一帧）。
- 服务器检测会话空闲超时，发送中止帧并释放会话；客户端超时同样中止，双方回到空闲态。
- 非法对象、权限、范围、toggle/协议错误均以 CiA 301 中止码回答。

### PDO 映射与触发
- TPDO/RPDO 各 4 通道，映射项为 `索引/子索引/位长`，支持小端序打包/拆包。
- 配置时校验被映射对象存在、位长为 8 的正整数倍、总长度不超过 64 位（8 字节）。
- 触发方式：事件驱动、定时（event timer）、SYNC 同步；抑制时间（inhibit time）生效。
- 映射以不可变快照原子替换，配置替换后绝不会再按旧映射发送；处理帧时抓取快照，
  与映射变更并发时结果确定。

### 心跳与紧急报文
- 心跳生产者按 0x1017 周期发送状态帧（0x700+N），状态迁移时立即发一帧。
- 心跳监控者按守护窗口边沿检出超时（只报一次），心跳恢复后自动清除并可再次检出。
- 紧急报文（0x080+N）携带紧急码、错误寄存器与厂商数据；历史（0x1003）完整保留、
  可查询、可持久化。STOPPED/INIT 下 EMCY 帧被抑制但记录不丢失。

### 多节点模拟总线与故障注入
- 任意数量节点（Node-ID 1..127）共享同一进程内总线，按 11 位 COB-ID 寻址、
  全连接广播，节点自行按 COB-ID 过滤。
- 可注入丢帧、重复帧与延迟帧；随机决策来自固定种子的 `Random`，
  相同种子与相同发送序列产生完全一致的行为。
- 单写者泵保证广播原子顺序；嵌套发送不入死锁；单个监听器异常不影响其他节点。

### 重启恢复
- 节点状态、应用区对象字典（0x2000–0x5FFF）、心跳周期与紧急历史以 JSON 持久化。
- 重启后恢复到一致状态；进行中的 SDO 分段传输在恢复时被强制终止，绝不悬挂。
- 通信参数/PDO 映射按标准默认值重建；OPERATIONAL 恢复为 PRE-OPERATIONAL，
  由主站重新置 OP。

## 时间模型

- 虚拟时钟（默认）：时间只随 `CanNetwork.tick(ms)` 推进，事件顺序完全确定，
  适合单元测试与可复现仿真。
- 实时模式：`startRealtime()` 启动 2ms 周期守护线程，适合演示与真实节奏运行。

## 快速上手

```java
CanNetwork network = new CanNetwork();
CanOpenNode node = network.createNode(1);
node.start();                         // 进入 PRE-OPERATIONAL

SdoClient client = new SdoClient(network.getBus(), network.getClock());
client.download(1, 0x2000, 0, new byte[]{0x2A});
byte[] value = client.upload(1, 0x2000, 0);

node.configureTpdo(0, PdoType.EVENT_DRIVEN, 0, 0,
        List.of(new PdoMapping(0x2002, 0, 16)));
node.requestState(NmtState.OPERATIONAL);
node.tpdo(0).triggerEvent();

network.tick(10);                     // 推进虚拟时间
network.save(new File("net.json"));   // 持久化
CanNetwork restored = CanNetwork.load(new File("net.json"));
```

故障注入：

```java
FaultInjection faults = new FaultInjection();
faults.setSeed(42L);
faults.setDropRate(0.2);
faults.setDuplicateRate(0.1);
faults.setDelayRange(5, 20);          // 毫秒
CanNetwork network = new CanNetwork(new VirtualClock(false), faults);
```

## 错误码（中止码）语义

| 中止码 | 枚举 | 触发场景 |
| --- | --- | --- |
| 0x05030000 | `TOGGLE_BIT_INVALID` | 分段传输 toggle 位不匹配 |
| 0x05040001 | `SDO_PROTOCOL_ERROR` | SDO 协议帧格式/尺寸/序列错误 |
| 0x05040002 | `SDO_COMMAND_SPECIFIER_INVALID` | 无法识别的 SDO 命令符 |
| 0x05040005 | `OUT_OF_MEMORY` | 服务器内存不足 |
| 0x06010000 | `UNSUPPORTED_ACCESS` | 不支持该访问方式 |
| 0x06010001 | `READ_ON_WRITE_ONLY` | 读取只写对象 |
| 0x06010002 | `WRITE_ON_READ_ONLY` | 写入只读/常量对象 |
| 0x06010003 | `SDO_TIMEOUT` | SDO 会话超时 |
| 0x06010005 | `UNSUPPORTED_ACCESS_TO_OBJECT` | 对象存在但不支持该访问 |
| 0x0601000A | `WRONG_DEVICE_STATE` | 当前 NMT 状态不允许该操作 |
| 0x06020000 | `OBJECT_DOES_NOT_EXIST` | 索引不存在 |
| 0x06040041 | `PDO_MAP_INVALID` | PDO 映射对象不存在/位长非法 |
| 0x06040042 | `PDO_LENGTH_EXCEEDED` | PDO 映射总长度超过 8 字节 |
| 0x06040043 | `PARAMETER_INCOMPATIBILITY` | 参数类型不兼容 |
| 0x06070010 | `INTERNAL_INCOMPATIBILITY` | 内部不一致 |
| 0x06090011 | `SUBINDEX_DOES_NOT_EXIST` | 索引存在但子索引不存在 |
| 0x06090030 | `VALUE_TOO_HIGH` | 写入值高于配置上限 |
| 0x06090031 | `VALUE_TOO_LOW` | 写入值低于配置下限 |
| 0x06090032 | `VALUE_RANGE_OVERFLOW` | 值超出数据类型位宽 |
| 0x08000000 | `GENERAL_ERROR` | 通用错误 |
| 0x08000020 | `DATA_CANNOT_TRANSFERRED` | 数据无法传输或编码 |

## 测试

`mvn test` 运行全部自动化测试（31+ 用例），覆盖：NMT 状态迁移与通信限制、
OD 权限/范围/存在性错误码、SDO 加速与分段传输及失败恢复、PDO 三种触发/抑制/
映射原子变更、心跳超时边沿检出与恢复、EMCY 历史与并发不丢失、固定种子故障注入、
多节点无死锁、重启持久化与分段传输终止。

## 模块结构

```
canopen/
  core/      CanFrame、CanBus、SimulatedCanBus、VirtualClock、FaultInjection
  od/        ObjectDictionary、OdEntry、Access、DataType、AbortCode、OdException
  nmt/       NmtState、NmtCommand、NmtMaster
  sdo/       SdoClient、SdoServer、SdoCodec、SdoException
  pdo/       Tpdo、Rpdo、PdoMapping、PdoType、PdoCodec、SyncProducer
  emcy/      EmergencyProducer、EmergencyHistory、EmergencyMessage、Heartbeat*
  node/      CanOpenNode、NodeConfig、NodeSnapshot、DefaultDictionary、NodePersistence
  network/   CanNetwork
```
