# canopen-stack-java

运行在**进程内模拟 CAN 总线**之上的本地 CANopen 协议栈与多节点仿真服务，不依赖任何真实 CAN
硬件。协议行为遵循 CiA 301（CANopen 应用层与通信规范），覆盖节点生命周期、对象字典、
SDO、PDO、心跳/节点守护、紧急报文、多节点总线、故障注入与重启恢复。

## 功能范围

### 对象字典（`od`）
- 条目按 `(索引, 子索引)` 寻址，支持 UNSIGNED8/16/32/64、INTEGER8/16/32/64、
  VISIBLE_STRING、OCTET_STRING、DOMAIN。
- 每次访问都校验：条目/子索引存在性、访问权限（RO/WO/RW/CONST）、数据长度、取值范围。
- 非法访问抛出携带标准 SDO abort code 的 `OdAccessException`，不同失败原因可区分。

### 节点生命周期 / NMT（`nmt`、`node`）
- 四状态状态机：`INITIALISATION → PRE_OPERATIONAL → OPERATIONAL ↔ STOPPED`。
- 命令：Start(0x01)、Stop(0x02)、Enter Pre-Op(0x80)、Reset Node(0x81)、
  Reset Communication(0x82)；支持单节点与广播（COB-ID `0x000`）。
- 通信行为受限：
  - SDO：PRE_OPERATIONAL / OPERATIONAL；
  - PDO：仅 OPERATIONAL；
  - EMCY：PRE_OPERATIONAL / OPERATIONAL；
  - 心跳：PRE_OPERATIONAL / OPERATIONAL / STOPPED。
- 初始化完成发送 bootup（`0x700+nodeId`，数据 `0x00`）。
- **确定性并发**：每个节点拥有独占的单线程执行器，NMT 迁移、SDO/PDO/心跳帧处理、
  定时器回调全部串行化为一个全序；复位先终止在途 SDO/PDO，因此“状态迁移与在途传输并发”
  不会产生不一致或悬挂。

### SDO（`sdo`）
- 客户端/服务端模型，服务端 COB-ID `0x600+nodeId` 接收、`0x580+nodeId` 应答。
- ≤4 字节走**加速传输（expedited）**，更大对象走**分段传输（segmented，每段 7 字节）**。
- 分段传输严格校验 toggle 位；重复帧重放上一应答而不重复读/写数据。
- 协议看门狗超时（默认 1000 ms）：本端中止会话并尽力发送 abort，双方状态一致、可恢复。
- 对端 abort、OD 拒绝均映射为标准 abort code，失败后可立即发起新传输。
- 节点 Reset 会向在途会话发送 abort（`0x060A0023`）并复位服务端，客户端同步唤醒失败。

### PDO（`pdo`）
- 4 个 TPDO（`0x1A00+i`）与 4 个 RPDO（`0x1600+i`），映射仅允许在 PRE_OPERATIONAL 变更。
- 遵循“子索引 0 写 0 解锁 → 改映射 → 写子索引 0 = N 激活”的 CiA 301 流程。
- 激活时校验：被映射对象存在、定长且可映射、TPDO 对象可读 / RPDO 对象可写、
  总位长度 ≤ 64 位（8 字节）；非法映射返回标准 abort code。
- 触发方式：
  - EVENT（0xFF 类，应用事件）；
  - TIMER（周期定时）；
  - SYNC（0 或 1..240，收到 `0x080` SYNC 帧后发送）。
- 事件与定时触发受**抑制时间（inhibit time，单位 100 µs）**约束。
- 每次映射激活/去激活都有单调版本号；旧版本的已调度/在途帧不再发送，
  映射移除后不会再按旧映射发包。

### 心跳与节点守护（`hb`）
- 生产者按 `0x1017` 配置的周期在 `0x700+nodeId` 发送当前 NMT 状态字节。
- 消费者/守护为每个被监视节点设置守护窗口，窗口内未收到心跳/bootup 即回调超时一次，
  恢复后自动重新守护（不重复刷超时）。

### 紧急报文（`emcy`）
- 错误发生时在 `0x080+nodeId` 发送 8 字节 EMCY（错误码 + 错误寄存器 0x1001 + 5 字节
  厂商字段），并写入容量受限、并发安全的环形历史，可随时查询。
  历史保留全部 raise 事件；相同活跃错误码在总线上只发一帧，错误清零发送 0x0000。

### 多节点模拟总线与故障注入（`can`、`network`）
- 所有节点共享一条 `SimulatedBus`；报文按 COB-ID 寻址，节点不接收自身 TX（同真实控制器）。
- 支持注入：**丢帧概率、重复帧概率、固定延迟**。
- 故障判定使用以 seed 初始化的伪随机序列，**相同 seed 下故障模式逐帧可复现**。
- 单个监听器异常被隔离，不会中断总线投递；故障下节点状态保持一致、无死锁。

### 重启恢复（`persist`、`sim`）
- JSON 快照持久化每个节点的 NMT 状态、可持久化对象字典数据与 EMCY 历史
  （原子写：临时文件 + rename）。
- 重启后节点恢复到一致状态；通信参数恢复上电默认值，SDO 服务端/PDO 管理器重新初始化，
  **任何进行中的分段传输都会被终止而非悬挂**。

## 错误码语义（SDO abort code，CiA 301）

| 代码 | 枚举 | 含义 |
| --- | --- | --- |
| `0x05040000` | `TIMEOUT` | SDO 协议超时 |
| `0x05040001` | `INVALID_COMMAND_SPECIFIER` | 命令说明符非法/未知 |
| `0x05040003` | `INVALID_SEQUENCE` | 分段 toggle/序号错误 |
| `0x06010000` | `UNSUPPORTED_ACCESS` | 不支持该访问（如 OPERATIONAL 下改 PDO 映射） |
| `0x06010001` | `READ_WRITE_ONLY` | 读只写对象 |
| `0x06010002` | `WRITE_READ_ONLY` | 写只读对象 |
| `0x06020000` | `OBJECT_DOES_NOT_EXIST` | 索引不在对象字典 |
| `0x06030000` | `CANNOT_MAP_TO_PDO` | 对象不可映射到 PDO（变长/权限不符） |
| `0x06040041` | `PDO_LENGTH_EXCEEDED` | PDO 映射总长度超过 64 位 / 负载长度不符 |
| `0x06040042` | `PARAMETER_INCOMPATIBILITY` | 参数不兼容 |
| `0x06070010/12/13` | `TYPE_MISMATCH_*` | 数据类型/长度不匹配（相等/过长/过短） |
| `0x06090011` | `SUBINDEX_DOES_NOT_EXIST` | 子索引不存在 |
| `0x06090030` | `VALUE_RANGE_EXCEEDED` | 取值超出 min/max |
| `0x060A0023` | `RESOURCE_UNAVAILABLE` | SDO 资源不可用（复位时中止在途会话） |
| `0x08000000` | `GENERAL_ERROR` | 通用错误 |

## REST 仿真接口

由 Spring Boot 暴露（基址 `/api/sim`）：
- `POST /start?seed=` 启动仿真网络；
- `POST /nodes?nodeId=` 创建节点（自动 bootup 进入 PRE_OPERATIONAL）；
- `GET /nodes` 查询节点状态；
- `POST /faults?drop=&duplicate=&delayMillis=&seed=` 动态注入故障；
- `POST /restart` 从持久化文件恢复并重启全部节点。

持久化文件默认 `data/canopen-sim.json`，可用系统属性
`-Dcanopen.persistence.path=...` 覆盖。

## 编程式使用示例

```java
CanOpenNetwork network = new CanOpenNetwork(new FaultProfile(0.1, 0.1, 2, 42L), null);
CanOpenNode server = network.createNode(2);
CanOpenNode clientNode = network.createNode(1);
SdoClient client = network.createSdoClient(1, 1000);

network.broadcastNmt(NmtCommand.START.getCode(), 0);          // 全部进入 OPERATIONAL
client.download(2, 0x2000, 0, new byte[]{0x01, 0x00});        // 加速 SDO 下载
byte[] value = client.upload(2, 0x2001, 0);                   // 加速 SDO 上传

server.configurePdo(0, true,
        List.of(new MappingEntry(0x2001, 0, 16)),
        PdoTriggerType.EVENT, 0x1AA, 50_000, 0);              // 仅 PRE_OPERATIONAL 可配
```

## 构建与测试

```bash
mvn test          # 30 个测试，全部离线可运行（JDK 17）
mvn spring-boot:run
```

测试覆盖：NMT 状态迁移、对象字典错误码、SDO 加速/分段/中止/超时、PDO 映射校验与
三种触发/抑制/映射变更、心跳超时、EMCY 历史、故障注入可复现性、状态迁移与在途传输的
并发确定性，以及重启恢复（状态/OD/EMCY、在途分段传输终止）。
