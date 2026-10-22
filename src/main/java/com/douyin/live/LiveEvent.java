package com.douyin.live;

import java.util.Objects;

public record LiveEvent(
        Type type,
        long roomId,
        String userId,
        String userName,
        String content,
        String giftName,
        long count) {

    public enum Type {
        CHAT,
        GIFT,
        LIKE,
        MEMBER,
        SOCIAL,
        ROOM_USER_SEQ,
        FANSCLUB,
        CONTROL,
        EMOJI_CHAT,
        ROOM_STATS,
        ROOM,
        ROOM_RANK,
        ROOM_STREAM_ADAPTATION,
        ROOM_STATUS
    }

    public LiveEvent {
        Objects.requireNonNull(type, "type");
        userId = nullToEmpty(userId);
        userName = nullToEmpty(userName);
        content = nullToEmpty(content);
        giftName = nullToEmpty(giftName);
    }

    public static LiveEvent of(Type type, long roomId) {
        return new LiveEvent(type, roomId, "", "", "", "", 0);
    }

    public LiveEvent withUser(String id, String name) {
        return new LiveEvent(type, roomId, id, name, content, giftName, count);
    }

    public LiveEvent withContent(String value) {
        return new LiveEvent(type, roomId, userId, userName, value, giftName, count);
    }

    public LiveEvent withGift(String value, long giftCount) {
        return new LiveEvent(type, roomId, userId, userName, content, value, giftCount);
    }

    public LiveEvent withCount(long value) {
        return new LiveEvent(type, roomId, userId, userName, content, giftName, value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
