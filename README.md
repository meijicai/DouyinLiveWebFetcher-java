# 抖音直播间弹幕抓取（Java 版）

这是原 Python 项目的独立 Java 17 移植版，保留了请求参数、WebSocket 心跳、ACK 和
protobuf 消息结构。Java 版本不依赖 Python、Node.js 或原项目文件。

## 环境

- JDK 17+
- Maven 3.8+
- Maven 3.8+

WebSocket `signature` 和 HTTP `a_bogus` 均由 Java 原生算法生成：

- WebSocket：MD5 + RC4 + 自定义 Base64
- `a_bogus`：SM3 + RC4 + 自定义 Base64

## 构建

```bash
cd /Users/mjc/PycharmProjects/DouyinLiveWebFetcher-java
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

本项目仅用于学习和技术研究，请遵守目标平台的服务条款。原生签名算法的参考来源和
许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
