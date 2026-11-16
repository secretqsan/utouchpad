# 安全模型

## 设计原则

- 手机与电脑之间的所有业务数据都经过 AES-256-GCM 加密；握手用 ECDH 派生的配对密钥做双向 HMAC 证明。
- 二维码中的 token 一次性、5 分钟有效；手机校验服务返回的公钥，防止连到冒充的服务器。
- 会话密钥每次连接重新派生，两个方向独立，计数器严格递增，重放/乱序帧会被拒绝。
- Windows 密码**只在手机**保存：密码本身用随机 PDK 加密，PDK 由 Android Keystore 中的指纹密钥包裹；没有指纹无法解出 PDK。
- 电脑端不持久化密码；CP 只在内存中以 `SecureString` 形式交给 Windows。
- `%ProgramData%\Utochpad` 在服务以 SYSTEM 运行时收紧为仅 SYSTEM/Administrators。
- CP 管道允许 Authenticated Users 连接（兼容以交互用户身份加载的凭据提供程序），但服务只接受**活动控制台会话**或 session 0 的客户端；其他已登录用户的会话会被拒绝，无法向手机请求解锁。
- 配对后手机公钥保存在服务配置中；未配对设备无法建立会话。

## 需要注意的边界

- 局域网内没有 TLS。首次配对依赖二维码这个带外信道；配对完成后即使流量被旁路，内容仍是密文。
- 与 PIN 不同，凭据提供程序需要的是账户**密码**（本地账户密码或 Microsoft 账户密码）。Windows Hello PIN 不能用于这种方式。
- 只支持当前登录的单个 Windows 账户。
- 手机必须在解锁时保持 App 前台并处于解锁状态。

## 进一步阅读

- 配对与会话协议的完整流程见 [architecture.md](architecture.md)。
- 日志与常见安全相关故障见 [troubleshooting.md](troubleshooting.md)。