package io.misterfix.mojangpipe;

import io.lettuce.core.api.sync.RedisCommands;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class Redis {
    private static final Map<Integer, Boolean> connectionBusy = IntStream.range(0, 6).boxed().collect(Collectors.toConcurrentMap(Function.identity(), i -> false, (a, b) -> b, ConcurrentHashMap::new));

    static void init(){
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        //Putting in default values as a lazy way to avoid NullPointerExceptions
        redis.flushdb();
        //List of ports on which Squid is running.
        //Each port corresponds to a different ext. IP address through which a request could be made.
        redis.hmset("proxies", Map.of(
                "3129", "0",
                "3130", "0",
                "3131", "0",
                "3132", "0",
                "3133", "0",
                "3134", "0",
                "3315", "0",
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
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        connectionBusy.replace(0, true);
        int stat = Integer.parseInt(redis.hget("stats", "profile_from_mem")) + Integer.parseInt(redis.hget("stats", "uuid_from_mem")) + Integer.parseInt(redis.hget("stats", "names_from_mem")) + Integer.parseInt(redis.hget("stats", "name_profile_from_mem")) + Integer.parseInt(redis.hget("stats", "served_from_invalid_cache"));
        connectionBusy.replace(0, false);
        return stat;
    }

    static int getRequestsFromApi(){
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        connectionBusy.replace(0, true);
        int stat = Integer.parseInt(redis.hget("stats", "profile_from_api")) + Integer.parseInt(redis.hget("stats", "uuid_from_api")) + Integer.parseInt(redis.hget("stats", "names_from_api")) + Integer.parseInt(redis.hget("stats", "name_profile_from_api"));
        connectionBusy.replace(0, false);
        return stat;
    }

    static int getOutgoingRequests(){
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        AtomicInteger outgoingRequests = new AtomicInteger();
        connectionBusy.replace(0, true);
        redis.hgetall("statusCodes").forEach((code, count) -> outgoingRequests.getAndAdd(Integer.parseInt(count)));
        connectionBusy.replace(0, false);
        return outgoingRequests.get();
    }

    static int getProfileRequestsCount(){
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        connectionBusy.replace(0, true);
        int stat = Integer.parseInt(redis.hget("stats", "profile_from_api")) + Integer.parseInt(redis.hget("stats", "profile_from_mem"));
        connectionBusy.replace(0, false);
        return stat;
    }

    static int getNameRequestsCount(){
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        connectionBusy.replace(0, true);
        int stat = Integer.parseInt(redis.hget("stats", "uuid_from_api")) + Integer.parseInt(redis.hget("stats", "uuid_from_mem"));
        connectionBusy.replace(0, false);
        return stat;
    }

    static int getNamesRequestsCount(){
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        connectionBusy.replace(0, true);
        int stat = Integer.parseInt(redis.hget("stats", "names_from_api")) + Integer.parseInt(redis.hget("stats", "names_from_mem"));
        connectionBusy.replace(0, false);
        return stat;
    }

    static int getNameProfileRequestsCount(){
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        connectionBusy.replace(0, true);
        int stat = Integer.parseInt(redis.hget("stats", "name_profile_from_api")) + Integer.parseInt(redis.hget("stats", "name_profile_from_mem"));
        connectionBusy.replace(0, false);
        return stat;
    }

    static String get429Percentage(){
        DecimalFormat formatter = new DecimalFormat(".###");
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        connectionBusy.replace(0, true);
        String redisQuery = redis.hget("statusCodes", "429 Too Many Requests");
        connectionBusy.replace(0, false);
        if(redisQuery == null){
            return "0.000";
        }
        int ratelimitsHit = Integer.parseInt(redisQuery);
        int totalRequests = getOutgoingRequests();
        return formatter.format(((double) ratelimitsHit / totalRequests) * 100);
    }

    static int dbsize(int db){
        RedisCommands<String, String> redis = MojangPipe.getRedis(db);
        connectionBusy.replace(db, true);
        int size = redis.dbsize().intValue();
        connectionBusy.replace(db, false);
        return size;
    }

    private static void logInvalidRequest(String identifier, long time){
        int cacheTime = MojangPipe.getInvalidLifetime()*60;
        Map<String, String> values = Map.of("time", Long.toString(time));

        RedisCommands<String, String> redis = MojangPipe.getRedis(5);
        connectionBusy.replace(5, true);
        redis.hmset(identifier, values);
        redis.expire(identifier, cacheTime);
        connectionBusy.replace(5, false);
    }

    static void logStatusMessage(String statusMessage){
        RedisCommands<String, String> redis = MojangPipe.getRedis(0);
        connectionBusy.replace(0, true);
        redis.hincrby("statusCodes", statusMessage, 1);
        connectionBusy.replace(0, false);
    }

    static void incrStats(String stat){
        MojangPipe.getThreadPool().execute(()->{
            RedisCommands<String, String> redis = MojangPipe.getRedis(0);
            connectionBusy.replace(0, true);
            redis.hincrby("stats", stat, 1);
            connectionBusy.replace(0, false);
        });
    }

    //=================NORMAL OPERATIONS METHODS=================//
    static long getLastRequest(String identifier, int db){
        RedisCommands<String, String> redis = MojangPipe.getRedis(db);
        connectionBusy.replace(db, true);
        String result = redis.hmget(identifier, "time").get(0).getValueOrElse("0");
        connectionBusy.replace(db, false);
        return Long.parseLong(result);
    }

    static String getJson(String identifier, int db){
        RedisCommands<String, String> redis = MojangPipe.getRedis(db);
        connectionBusy.replace(db, true);
        String result = redis.hmget(identifier, "json").get(0).getValueOrElse("");
        connectionBusy.replace(db, false);
        return Objects.requireNonNullElse(result, "");
    }

    static void putJson(String identifier, long time, String json, int db){
        int cacheTime = MojangPipe.getCacheLifetime()*60;
        Map<String, String> values = Map.of(
                "time", String.valueOf(time),
                "json", json
        );

        RedisCommands<String, String> redis = MojangPipe.getRedis(db);
        connectionBusy.replace(db, true);
        redis.hmset(identifier, values);
        redis.expire(identifier, cacheTime);
        connectionBusy.replace(db, false);
    }

    static void handleStatusCode(int code, String identifier){
        if(code == 204){
            Redis.logInvalidRequest(identifier, System.currentTimeMillis());
        } else {
            MojangPipe.newProxy();
        }
    }

    static boolean isConnectionBusy(int db){
        return connectionBusy.get(db);
    }
    @SuppressWarnings("SameParameterValue")
    static void setConnectionBusy(int db, boolean busy){
        connectionBusy.replace(db, busy);
    }
}