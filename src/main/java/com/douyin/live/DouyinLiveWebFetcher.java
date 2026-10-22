package com.douyin.live;

import com.google.protobuf.ByteString;
import douyin.Douyin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Java implementation of the Douyin web live-room event fetcher.
 */
public final class DouyinLiveWebFetcher implements AutoCloseable {
    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0";

    private static final URI DOUYIN_HOME = URI.create("https://www.douyin.com/");
    private static final String LIVE_HOME = "https://live.douyin.com/";
    private static final Pattern ROOM_ID_PATTERN =
            Pattern.compile("roomId\\\\\":\\\\\"(\\d+)\\\\\"");
    private static final Pattern ROOM_STATUS_PATTERN =
            Pattern.compile("\"room_status\"\\s*:\\s*(\\d+)");
    private static final Pattern USER_ID_PATTERN =
            Pattern.compile("\"id_str\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern NICKNAME_PATTERN =
            Pattern.compile("\"nickname\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final String liveId;
    private final String userAgent;
    private final Consumer<LiveEvent> eventHandler;
    private final Consumer<String> errorHandler;
    private final CookieManager cookieManager;
    private final HttpClient httpClient;
    private final WebcastSignature webcastSignature = new WebcastSignature();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CountDownLatch connectionClosed = new CountDownLatch(1);

    private volatile String ttwid;
    private volatile String roomId;
    private volatile WebSocket webSocket;
    private volatile ScheduledExecutorService heartbeatExecutor;

    public DouyinLiveWebFetcher(String liveId) throws IOException {
        this(liveId, System.out::println, System.err::println);
    }

    public DouyinLiveWebFetcher(
            String liveId,
            Consumer<LiveEvent> eventHandler,
            Consumer<String> errorHandler) throws IOException {
        this.liveId = Objects.requireNonNull(liveId, "liveId").trim();
        if (this.liveId.isEmpty()) {
            throw new IllegalArgumentException("liveId must not be empty");
        }
        this.userAgent = System.getProperty("douyin.userAgent", DEFAULT_USER_AGENT);
        this.eventHandler = Objects.requireNonNull(eventHandler, "eventHandler");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Connects and blocks until the WebSocket is closed.
     */
    public void start() throws IOException, InterruptedException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Fetcher is already running");
        }
        stopping.set(false);

        try {
            String resolvedRoomId = getRoomId();
            String wss = buildWebSocketUrl(resolvedRoomId);
            WebSocket.Listener listener = new Listener();
            URI webSocketUri = URI.create(wss.replace("|", "%7C"));
            CompletableFuture<WebSocket> connection = httpClient.newWebSocketBuilder()
                    .header("Cookie", "ttwid=" + getTtwid())
                    .header("User-Agent", userAgent)
                    .connectTimeout(Duration.ofSeconds(20))
                    .buildAsync(webSocketUri, listener);

            webSocket = connection.get(25, TimeUnit.SECONDS);
            connectionClosed.await();
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException exception) {
            throw new IOException("Unable to connect to Douyin WebSocket", exception);
        } finally {
            running.set(false);
        }
    }

    public void stop() {
        stopping.set(true);
        stopHeartbeat();
        WebSocket current = webSocket;
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "client stopped");
        }
    }

    /**
     * Queries whether the room is currently live.
     */
    public RoomStatus getRoomStatus() throws IOException, InterruptedException {
        String nonce = getAcNonce();
        String signature = getAcSignature(nonce);
        String msToken = generateMsToken(182);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("aid", "6383");
        params.put("app_name", "douyin_web");
        params.put("live_id", "1");
        params.put("device_platform", "web");
        params.put("language", "zh-CN");
        params.put("enter_from", "page_refresh");
        params.put("cookie_enabled", "true");
        params.put("screen_width", "5120");
        params.put("screen_height", "1440");
        params.put("browser_language", "zh-CN");
        params.put("browser_platform", "Win32");
        params.put("browser_name", "Edge");
        params.put("browser_version", "140.0.0.0");
        params.put("web_rid", liveId);
        params.put("room_id_str", getRoomId());
        params.put("enter_source", "");
        params.put("is_need_double_stream", "false");
        params.put("insert_task_id", "");
        params.put("live_reason", "");
        params.put("msToken", msToken);

        String query = encodeQuery(params);
        String aBogus = AbogusSigner.sign(query, userAgent);
        String url = "https://live.douyin.com/webcast/room/web/enter/?" + query + "&a_bogus=" + aBogus;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent)
                .header("Referer", LIVE_HOME + liveId)
                .header("Cookie", "ttwid=" + getTtwid()
                        + ";__ac_nonce=" + nonce
                        + "; __ac_signature=" + signature)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Room status request failed: HTTP " + response.statusCode());
        }

        String body = response.body();
        Matcher statusMatcher = ROOM_STATUS_PATTERN.matcher(body);
        if (!statusMatcher.find()) {
            throw new IOException("Room status response did not contain room_status");
        }
        int roomStatus = Integer.parseInt(statusMatcher.group(1));
        Matcher idMatcher = USER_ID_PATTERN.matcher(body);
        String userId = idMatcher.find() ? idMatcher.group(1) : "";
        Matcher nicknameMatcher = NICKNAME_PATTERN.matcher(body);
        String nickname = nicknameMatcher.find() ? unescapeJson(nicknameMatcher.group(1)) : "";
        RoomStatus result = new RoomStatus(roomStatus == 0, roomStatus, userId, nickname);
        emit(LiveEvent.of(LiveEvent.Type.ROOM_STATUS, 0)
                .withUser(userId, nickname)
                .withContent(result.live() ? "正在直播" : "已结束"));
        return result;
    }

    public String getTtwid() throws IOException, InterruptedException {
        String current = ttwid;
        if (current != null && !current.isBlank()) {
            return current;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(LIVE_HOME))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        requireSuccess(response.statusCode(), "live home");
        current = findCookie(URI.create(LIVE_HOME), "ttwid");
        if (current == null || current.isBlank()) {
            throw new IOException("Douyin did not return a ttwid cookie");
        }
        ttwid = current;
        return current;
    }

    public String getRoomId() throws IOException, InterruptedException {
        String current = roomId;
        if (current != null && !current.isBlank()) {
            return current;
        }
        String url = LIVE_HOME + liveId;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent)
                .header("Cookie", "ttwid=" + getTtwid()
                        + "&msToken=" + generateMsToken(182)
                        + "; __ac_nonce=0123407cc00a9e438deb4")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(response.statusCode(), "live room");
        Matcher matcher = ROOM_ID_PATTERN.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("Unable to locate roomId in the live-room page");
        }
        current = matcher.group(1);
        roomId = current;
        return current;
    }

    public String generateMsToken(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    private String getAcNonce() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(DOUYIN_HOME)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        requireSuccess(response.statusCode(), "Douyin home");
        String nonce = findCookie(DOUYIN_HOME, "__ac_nonce");
        if (nonce == null || nonce.isBlank()) {
            throw new IOException("Douyin did not return an __ac_nonce cookie");
        }
        return nonce;
    }

    private String getAcSignature(String nonce) {
        return AcSignature.create("www.douyin.com", nonce, userAgent);
    }

    private String buildWebSocketUrl(String resolvedRoomId) throws IOException, InterruptedException {
        String wss = "wss://webcast100-ws-web-lq.douyin.com/webcast/im/push/v2/"
                + "?app_name=douyin_web"
                + "&version_code=180800&webcast_sdk_version=1.0.14-beta.0"
                + "&update_version_code=1.0.14-beta.0&compress=gzip&device_platform=web"
                + "&cookie_enabled=true&screen_width=1536&screen_height=864"
                + "&browser_language=zh-CN&browser_platform=Win32&browser_name=Mozilla"
                + "&browser_version=5.0%20(Windows%20NT%2010.0;%20Win64;%20x64)%20"
                + "AppleWebKit/537.36%20(KHTML,%20like%20Gecko)%20Chrome/126.0.0.0%20Safari/537.36"
                + "&browser_online=true&tz_name=Asia/Shanghai"
                + "&cursor=d-1_u-1_fh-7392091211001140287_t-1721106114633_r-1"
                + "&internal_ext=internal_src:dim|wss_push_room_id:" + resolvedRoomId
                + "|wss_push_did:7319483754668557238|first_req_ms:1721106114541"
                + "|fetch_time:1721106114633|seq:1|wss_info:0-1721106114633-0-0"
                + "|wrds_v:7392094459690748497&host=https://live.douyin.com"
                + "&aid=6383&live_id=1&did_rule=3&endpoint=live_pc&support_wrds=1"
                + "&user_unique_id=7319483754668557238"
                + "&im_path=/webcast/im/fetch/&identity=audience"
                + "&need_persist_msg_count=15&insert_task_id=&live_reason="
                + "&room_id=" + resolvedRoomId + "&heartbeatDuration=0";

        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : wss.substring(wss.indexOf('?') + 1).split("&")) {
            int separator = pair.indexOf('=');
            query.put(separator >= 0 ? pair.substring(0, separator) : pair,
                    separator >= 0 ? pair.substring(separator + 1) : "");
        }
        String[] fields = {
                "live_id", "aid", "version_code", "webcast_sdk_version",
                "room_id", "sub_room_id", "sub_channel_id", "did_rule",
                "user_unique_id", "device_platform", "device_type", "ac", "identity"
        };
        StringBuilder material = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                material.append(',');
            }
            material.append(fields[i]).append('=').append(query.getOrDefault(fields[i], ""));
        }
        String signature = webcastSignature.sign(md5(material.toString()));
        return wss + "&signature=" + signature;
    }

    private void handleBinaryMessage(byte[] bytes) {
        try {
            Douyin.PushFrame pushFrame = Douyin.PushFrame.parseFrom(bytes);
            byte[] payload = pushFrame.getPayload().toByteArray();
            if (payload.length >= 2
                    && (payload[0] & 0xff) == 0x1f
                    && (payload[1] & 0xff) == 0x8b) {
                payload = gunzip(payload);
            }
            Douyin.Response response = Douyin.Response.parseFrom(payload);

            if (response.getNeedAck()) {
                Douyin.PushFrame ack = Douyin.PushFrame.newBuilder()
                        .setLogId(pushFrame.getLogId())
                        .setPayloadType("ack")
                        .setPayload(ByteString.copyFrom(
                                response.getInternalExt(), StandardCharsets.UTF_8))
                        .build();
                WebSocket current = webSocket;
                if (current != null) {
                    current.sendBinary(ByteBuffer.wrap(ack.toByteArray()), true);
                }
            }

            for (Douyin.Message message : response.getMessagesListList()) {
                dispatch(message.getMethod(), message.getPayload());
            }
        } catch (Exception exception) {
            reportError("WebSocket message parse error: " + exception.getMessage());
        }
    }

    private void dispatch(String method, ByteString payload) {
        try {
            switch (method) {
                case "WebcastChatMessage" -> parseChat(payload);
                case "WebcastGiftMessage" -> parseGift(payload);
                case "WebcastLikeMessage" -> parseLike(payload);
                case "WebcastMemberMessage" -> parseMember(payload);
                case "WebcastSocialMessage" -> parseSocial(payload);
                case "WebcastRoomUserSeqMessage" -> parseRoomUserSeq(payload);
                case "WebcastFansclubMessage" -> parseFansclub(payload);
                case "WebcastControlMessage" -> parseControl(payload);
                case "WebcastEmojiChatMessage" -> parseEmojiChat(payload);
                case "WebcastRoomStatsMessage" -> parseRoomStats(payload);
                case "WebcastRoomMessage" -> parseRoom(payload);
                case "WebcastRoomRankMessage" -> parseRoomRank(payload);
                case "WebcastRoomStreamAdaptationMessage" -> parseRoomStreamAdaptation(payload);
                default -> {
                    // Unknown message types are ignored, matching the Python implementation.
                }
            }
        } catch (Exception ignored) {
            // A malformed or changed event schema should not terminate the live stream.
        }
    }

    private void parseChat(ByteString payload) throws Exception {
        Douyin.ChatMessage message = Douyin.ChatMessage.parseFrom(payload);
        Douyin.User user = message.getUser();
        emit(new LiveEvent(LiveEvent.Type.CHAT, message.getCommon().getRoomId(),
                Long.toUnsignedString(user.getId()), user.getNickName(),
                message.getContent(), "", 0));
    }

    private void parseGift(ByteString payload) throws Exception {
        Douyin.GiftMessage message = Douyin.GiftMessage.parseFrom(payload);
        Douyin.User user = message.getUser();
        emit(new LiveEvent(LiveEvent.Type.GIFT, message.getCommon().getRoomId(),
                Long.toUnsignedString(user.getId()), user.getNickName(), "",
                message.getGift().getName(), message.getComboCount()));
    }

    private void parseLike(ByteString payload) throws Exception {
        Douyin.LikeMessage message = Douyin.LikeMessage.parseFrom(payload);
        Douyin.User user = message.getUser();
        emit(new LiveEvent(LiveEvent.Type.LIKE, message.getCommon().getRoomId(),
                Long.toUnsignedString(user.getId()), user.getNickName(), "",
                "", message.getCount()));
    }

    private void parseMember(ByteString payload) throws Exception {
        Douyin.MemberMessage message = Douyin.MemberMessage.parseFrom(payload);
        Douyin.User user = message.getUser();
        String gender = switch (user.getGender()) {
            case 0 -> "女";
            case 1 -> "男";
            default -> "未知";
        };
        emit(new LiveEvent(LiveEvent.Type.MEMBER, message.getCommon().getRoomId(),
                Long.toUnsignedString(user.getId()), user.getNickName(), gender, "", 0));
    }

    private void parseSocial(ByteString payload) throws Exception {
        Douyin.SocialMessage message = Douyin.SocialMessage.parseFrom(payload);
        Douyin.User user = message.getUser();
        emit(new LiveEvent(LiveEvent.Type.SOCIAL, message.getCommon().getRoomId(),
                Long.toUnsignedString(user.getId()), user.getNickName(), "", "", 0));
    }

    private void parseRoomUserSeq(ByteString payload) throws Exception {
        Douyin.RoomUserSeqMessage message = Douyin.RoomUserSeqMessage.parseFrom(payload);
        emit(new LiveEvent(LiveEvent.Type.ROOM_USER_SEQ, message.getCommon().getRoomId(),
                "", "", message.getTotalPvForAnchor(), "", message.getTotal()));
    }

    private void parseFansclub(ByteString payload) throws Exception {
        Douyin.FansclubMessage message = Douyin.FansclubMessage.parseFrom(payload);
        emit(new LiveEvent(LiveEvent.Type.FANSCLUB, message.getCommonInfo().getRoomId(),
                "", "", message.getContent(), "", 0));
    }

    private void parseControl(ByteString payload) throws Exception {
        Douyin.ControlMessage message = Douyin.ControlMessage.parseFrom(payload);
        boolean ended = message.getStatus() == 3;
        emit(new LiveEvent(LiveEvent.Type.CONTROL, message.getCommon().getRoomId(),
                "", "", ended ? "直播间已结束" : Integer.toString(message.getStatus()), "", 0));
        if (ended) {
            stop();
        }
    }

    private void parseEmojiChat(ByteString payload) throws Exception {
        Douyin.EmojiChatMessage message = Douyin.EmojiChatMessage.parseFrom(payload);
        Douyin.User user = message.getUser();
        String content = "emojiId=" + Long.toUnsignedString(message.getEmojiId())
                + ", defaultContent=" + message.getDefaultContent()
                + ", common=" + message.getCommon();
        emit(new LiveEvent(LiveEvent.Type.EMOJI_CHAT, message.getCommon().getRoomId(),
                Long.toUnsignedString(user.getId()), user.getNickName(), content, "", 0));
    }

    private void parseRoomStats(ByteString payload) throws Exception {
        Douyin.RoomStatsMessage message = Douyin.RoomStatsMessage.parseFrom(payload);
        emit(new LiveEvent(LiveEvent.Type.ROOM_STATS, message.getCommon().getRoomId(),
                "", "", message.getDisplayLong(), "", message.getTotal()));
    }

    private void parseRoom(ByteString payload) throws Exception {
        Douyin.RoomMessage message = Douyin.RoomMessage.parseFrom(payload);
        emit(new LiveEvent(LiveEvent.Type.ROOM, message.getCommon().getRoomId(),
                "", "", Long.toUnsignedString(message.getCommon().getRoomId()), "", 0));
    }

    private void parseRoomRank(ByteString payload) throws Exception {
        Douyin.RoomRankMessage message = Douyin.RoomRankMessage.parseFrom(payload);
        emit(new LiveEvent(LiveEvent.Type.ROOM_RANK, message.getCommon().getRoomId(),
                "", "", message.getRanksListList().toString(), "", 0));
    }

    private void parseRoomStreamAdaptation(ByteString payload) throws Exception {
        Douyin.RoomStreamAdaptationMessage message =
                Douyin.RoomStreamAdaptationMessage.parseFrom(payload);
        emit(new LiveEvent(LiveEvent.Type.ROOM_STREAM_ADAPTATION,
                message.getCommon().getRoomId(), "", "",
                Integer.toString(message.getAdaptationType()), "", 0));
    }

    private void startHeartbeat(WebSocket socket) {
        stopHeartbeat();
        byte[] heartbeat = Douyin.PushFrame.newBuilder()
                .setPayloadType("hb")
                .build()
                .toByteArray();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "douyin-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor = executor;
        executor.scheduleAtFixedRate(() -> {
            try {
                if (!socket.isOutputClosed()) {
                    socket.sendPing(ByteBuffer.wrap(heartbeat)).join();
                }
            } catch (Exception exception) {
                reportError("Heartbeat error: " + exception.getMessage());
                stopHeartbeat();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private String findCookie(URI uri, String name) {
        for (HttpCookie cookie : cookieManager.getCookieStore().get(uri)) {
            if (name.equalsIgnoreCase(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void emit(LiveEvent event) {
        try {
            eventHandler.accept(event);
        } catch (RuntimeException exception) {
            reportError("Event handler error: " + exception.getMessage());
        }
    }

    private void reportError(String message) {
        try {
            errorHandler.accept(message);
        } catch (RuntimeException ignored) {
            // Never let an error callback terminate the WebSocket listener.
        }
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzip.transferTo(output);
            return output.toByteArray();
        }
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }

    private static String encodeQuery(Map<String, String> params) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!result.isEmpty()) {
                result.append('&');
            }
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static String unescapeJson(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }

    private static void requireSuccess(int statusCode, String operation) throws IOException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException(operation + " request failed: HTTP " + statusCode);
        }
    }

    @Override
    public void close() {
        stop();
    }

    public record RoomStatus(boolean live, int roomStatus, String userId, String nickname) {
    }

    private final class Listener implements WebSocket.Listener {
        private final ByteArrayOutputStream partialMessage = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket socket) {
            webSocket = socket;
            socket.request(1);
            startHeartbeat(socket);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            try {
                partialMessage.write(chunk);
            } catch (IOException exception) {
                reportError("Unable to collect WebSocket frame: " + exception.getMessage());
            }
            if (last) {
                byte[] message = partialMessage.toByteArray();
                partialMessage.reset();
                handleBinaryMessage(message);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            stopHeartbeat();
            try {
                getRoomStatus();
            } catch (Exception exception) {
                if (!stopping.get()) {
                    reportError("Unable to query room status after close: " + exception.getMessage());
                }
            }
            connectionClosed.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            reportError("WebSocket error: " + error.getMessage());
            stopHeartbeat();
            webSocket = null;
            connectionClosed.countDown();
        }
    }
}
