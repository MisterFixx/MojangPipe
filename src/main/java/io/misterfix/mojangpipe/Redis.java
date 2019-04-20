package io.misterfix.mojangpipe;

import io.lettuce.core.api.sync.RedisCommands;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

class Redis {
    static void init(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        //Flushing the database and putting in default values as a lazy way to avoid NullPointerExceptions
        redis.flushall();
        //List of ports on which Squid is running.
        //Each port corresponds to a different ext. IP address through which a request could be made.
        redis.hmset("proxies", Map.of(
                "3129", "0",
                "3130", "0",
                "3131", "0",
                "3132", "0",
                "3133", "0",
                "3134", "0",
                "3135", "0",
                "3136", "0"
        ));
        //Statistics...
        redis.hmset("stats", Map.of(
                "profile_from_mem", "0",
                "profile_from_api", "0",
                "uuid_from_mem", "0",
                "uuid_from_api", "0",
                "names_from_mem", "0",
                "names_from_api", "0",
                "name_profile_from_mem", "0",
                "name_profile_from_api", "0",
                "served_from_invalid_cache", "0"
        ));
        //Had to do this because Redis wouldn't accept an empty map.
        redis.hmset("statusCodes", Map.of("200 OK", "0"));
    }

    //=====================STATISTICS METHODS=====================//
    static int getRequestsFromMemory(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        return Integer.parseInt(redis.hget("stats", "profile_from_mem")) + Integer.parseInt(redis.hget("stats", "uuid_from_mem")) + Integer.parseInt(redis.hget("stats", "names_from_mem")) + Integer.parseInt(redis.hget("stats", "name_profile_from_mem")) + Integer.parseInt(redis.hget("stats", "served_from_invalid_cache"));
    }

    static int getRequestsFromApi(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        return Integer.parseInt(redis.hget("stats", "profile_from_api")) + Integer.parseInt(redis.hget("stats", "uuid_from_api")) + Integer.parseInt(redis.hget("stats", "names_from_api")) + Integer.parseInt(redis.hget("stats", "name_profile_from_api"));
    }

    static int getOutgoingRequests(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        AtomicInteger outgoingRequests = new AtomicInteger();
        redis.hgetall("statusCodes").forEach((code, count) -> outgoingRequests.getAndAdd(Integer.parseInt(count)));
        return outgoingRequests.get();
    }

    static int getProfileRequestsCount(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        return Integer.parseInt(redis.hget("stats", "profile_from_api")) + Integer.parseInt(redis.hget("stats", "profile_from_mem"));
    }

    static int getNameRequestsCount(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        return Integer.parseInt(redis.hget("stats", "uuid_from_api")) + Integer.parseInt(redis.hget("stats", "uuid_from_mem"));
    }

    static int getNamesRequestsCount(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        return Integer.parseInt(redis.hget("stats", "names_from_api")) + Integer.parseInt(redis.hget("stats", "names_from_mem"));
    }

    static int getNameProfileRequestsCount(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        return Integer.parseInt(redis.hget("stats", "name_profile_from_api")) + Integer.parseInt(redis.hget("stats", "name_profile_from_mem"));
    }

    static String get429Percentage(){
        DecimalFormat formatter = new DecimalFormat(".###");
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        String redisQuery = redis.hget("statusCodes", "429 429");
        if(redisQuery == null){
            return "0.000";
        }
        int ratelimitsHit = Integer.parseInt(redisQuery);
        int totalRequests = getOutgoingRequests();
        return formatter.format(((double) ratelimitsHit / totalRequests) * 100);
    }

    static int dbsize(int db){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(db);
        return redis.dbsize().intValue();
    }

    private static void logInvalidRequest(String identifier, long time){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(5);
        redis.hmset(identifier, Map.of("time", Long.toString(time)));
    }

    static void logStatusMessage(String statusMessage){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        redis.hincrby("statusCodes", statusMessage, 1);
    }

    static void incrStats(String stat){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        redis.hincrby("stats", stat, 1);
    }

    //=================NORMAL OPERATIONS METHODS=================//
    static long getLastRequest(String identifier, int dbIndex){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(dbIndex);
        String result = redis.hmget(identifier, "time").get(0).getValueOrElse("0");
        return Long.valueOf(result);
    }

    static String getJson(String identifier, int dbIndex){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(dbIndex);
        String result = redis.hmget(identifier, "json").get(0).getValueOrElse("");
        return Objects.requireNonNullElse(result, "");
    }

    static void putJson(String identifier, long time, String json, int dbIndex){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        String timeStr = String.valueOf(time);
        redis.select(dbIndex);
        redis.hmset(identifier, Map.of("time", timeStr, "json", json));
    }

    static void handleStatusCode(int code, String identifier){
        if(code == 204){
            Redis.logInvalidRequest(identifier, System.currentTimeMillis());
        } else {
            MojangPipe.newProxy();
        }
    }
}