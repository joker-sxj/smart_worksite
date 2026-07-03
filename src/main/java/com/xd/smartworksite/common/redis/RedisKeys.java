package com.xd.smartworksite.common.redis;

public final class RedisKeys {

    private static final String PREFIX = "smart-worksite";

    private RedisKeys() {
    }

    public static String cache(String name) {
        return PREFIX + ":cache:" + name;
    }

    public static String lock(String name) {
        return PREFIX + ":lock:" + name;
    }

    public static String queue(String name) {
        return PREFIX + ":queue:" + name;
    }
}
