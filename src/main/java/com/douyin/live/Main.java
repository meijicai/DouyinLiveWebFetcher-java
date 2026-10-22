package com.douyin.live;

import java.util.Locale;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        String defaultLiveId = System.getenv().getOrDefault("DOUYIN_LIVE_ID", "896060880408");
        if (args.length > 2 || (args.length == 1 && args[0].startsWith("-"))) {
            System.err.println("Usage: java -jar douyin-live-java-1.0.0.jar [liveId]");
            System.exit(2);
        }

        String liveId = args.length == 1 ? args[0].trim() : defaultLiveId;
        try (DouyinLiveWebFetcher fetcher = new DouyinLiveWebFetcher(
                liveId, Main::printEvent, message -> System.err.println("【X】" + message))) {
            Runtime.getRuntime().addShutdownHook(new Thread(fetcher::stop, "douyin-shutdown"));
            System.out.println("【√】准备连接直播间：" + liveId);
            fetcher.start();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("【X】程序被中断");
        } catch (Exception exception) {
            System.err.println("【X】启动失败：" + exception.getMessage());
            exception.printStackTrace(System.err);
        }
    }

    private static void printEvent(LiveEvent event) {
        String type = event.type().name().toLowerCase(Locale.ROOT);
        String count = Long.toUnsignedString(event.count());
        switch (event.type()) {
            case CHAT -> System.out.printf("【聊天msg】[%s]%s: %s%n",
                    event.userId(), event.userName(), event.content());
            case GIFT -> System.out.printf("【礼物msg】%s 送出了 %sx%s%n",
                    event.userName(), event.giftName(), count);
            case LIKE -> System.out.printf("【点赞msg】%s 点了%s个赞%n",
                    event.userName(), count);
            case MEMBER -> System.out.printf("【进场msg】[%s][%s]%s 进入了直播间%n",
                    event.userId(), event.content(), event.userName());
            case SOCIAL -> System.out.printf("【关注msg】[%s]%s 关注了主播%n",
                    event.userId(), event.userName());
            case ROOM_USER_SEQ -> System.out.printf("【统计msg】当前观看人数: %s, 累计观看人数: %s%n",
                    count, event.content());
            case FANSCLUB -> System.out.printf("【粉丝团msg】 %s%n", event.content());
            case CONTROL -> System.out.printf("【控制msg】%s%n", event.content());
            case EMOJI_CHAT -> System.out.printf("【聊天表情包msg】user：%s, %s%n",
                    event.userName(), event.content());
            case ROOM_STATS -> System.out.printf("【直播间统计msg】%s%n", event.content());
            case ROOM -> System.out.printf("【直播间msg】直播间id:%s%n", event.content());
            case ROOM_RANK -> System.out.printf("【直播间排行榜msg】%s%n", event.content());
            case ROOM_STREAM_ADAPTATION -> System.out.printf(
                    "【直播间适配msg】%s%n", event.content());
            case ROOM_STATUS -> System.out.printf("【%s】[%s]直播间：%s.%n",
                    event.userName(), event.userId(), event.content());
            default -> System.out.printf("【%s】%s%n", type, event.content());
        }
    }
}
