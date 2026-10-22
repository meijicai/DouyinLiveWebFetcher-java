# 抖音直播间弹幕抓取（Java 版）

这是原 Python 项目的独立 Java 17 移植版，保留了请求参数、WebSocket 心跳、ACK 和
protobuf 消息结构。Java 版本不依赖 Python、Node.js 或原项目文件。

> [!IMPORTANT]
> 本项目仅用于个人学习、技术研究和合法的数据处理。使用者应自行确保其使用行为符合
> 当地法律法规、目标平台服务条款及隐私规则，并对数据的获取、存储、使用和传播承担
> 全部责任。详细条款见 [DISCLAIMER.md](DISCLAIMER.md)。

## 环境

- JDK 17+
- Maven 3.8+

WebSocket `signature` 和 HTTP `a_bogus` 均由 Java 原生算法生成：

- WebSocket：MD5 + RC4 + 自定义 Base64
- `a_bogus`：SM3 + RC4 + 自定义 Base64

## 构建

```bash
cd DouyinLiveWebFetcher-java
mvn package
```

构建产物：

```text
target/douyin-live-java-1.0.0.jar
```

## 运行

命令行参数是直播间地址末尾的 `liveId`：

```bash
java -jar target/douyin-live-java-1.0.0.jar 896060880408
```

也可以使用环境变量：

```bash
DOUYIN_LIVE_ID=896060880408 java -jar target/douyin-live-java-1.0.0.jar
```

按 `Ctrl+C` 结束。默认输出格式与 Python 版一致。

## 作为库使用

```java
import com.douyin.live.DouyinLiveWebFetcher;
import com.douyin.live.LiveEvent;

try (var fetcher = new DouyinLiveWebFetcher(
        "896060880408",
        event -> {
            if (event.type() == LiveEvent.Type.CHAT) {
                System.out.printf("%s: %s%n", event.userName(), event.content());
            }
        },
        System.err::println)) {
    fetcher.start(); // 阻塞，直到连接关闭
}
```

如果要持续接收事件，请在单独线程中调用 `start()`。`stop()` 可以主动结束连接。

## 已实现的消息

- 聊天
- 礼物
- 点赞
- 进场
- 关注
- 观看人数统计
- 粉丝团
- 直播状态
- 表情聊天
- 直播间统计
- 直播间信息
- 排行榜
- 流配置适配

## 法律与责任声明

- 本项目与抖音、字节跳动及其关联公司不存在隶属、授权、合作或背书关系。
- 使用者不得利用本项目绕过访问控制、侵犯个人信息或隐私、实施攻击、骚扰、欺诈，
  或以其他违反法律法规和平台规则的方式获取、使用、出售或传播数据。
- 项目按“现状”提供，不保证功能持续可用、数据准确或符合特定用途。因使用或无法使用
  本项目产生的账号限制、平台处罚、数据损失或第三方索赔，由使用者自行承担。
- 本声明不构成法律意见，也不能排除适用法律明确禁止排除的责任。完整免责声明见
  [DISCLAIMER.md](DISCLAIMER.md)。

原生签名算法的参考来源和许可证见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
