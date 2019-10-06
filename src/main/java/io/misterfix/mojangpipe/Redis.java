package io.misterfix.mojangpipe;

import io.lettuce.core.api.sync.RedisCommands;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Map;

public class Redis {
	static void init() {
		try (WrappedRedis wrapped = WrappedRedis.get(0)) {
			RedisCommands<String, String> redis = wrapped.get();
			
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
	}
	
	//=====================STATISTICS METHODS=====================//
	private static int getIntStats(String... fields) {
		try (WrappedRedis redis = WrappedRedis.get(0)) {
			return Arrays.stream(fields)
					.mapToInt(field -> Integer.parseInt(redis.get().hget("stats", field)))
					.sum();
		}
	}
	
	static int getRequestsFromMemory() {
		return getIntStats("profile_from_mem", "uuid_from_mem", "names_from_mem", "name_profile_from_mem", "served_from_invalid_cache");
	}
	
	static int getRequestsFromApi() {
		return getIntStats("profile_from_api", "uuid_from_api", "names_from_api", "name_profile_from_api");
	}
	
	static int getOutgoingRequests() {
		try (WrappedRedis redis = WrappedRedis.get(0)) {
			return redis.get().hgetall("statusCodes").values().stream().mapToInt(Integer::parseInt).sum();
		}
	}
	
	static int getProfileRequestsCount() {
		return getIntStats("profile_from_api", "profile_from_mem");
	}
	
	static int getNameRequestsCount() {
		return getIntStats("uuid_from_api", "uuid_from_mem");
	}
	
	static int getNamesRequestsCount() {
		return getIntStats("names_from_api", "names_from_mem");
	}
	
	static int getNameProfileRequestsCount() {
		return getIntStats("name_profile_from_api", "name_profile_from_mem");
	}
	
	static String get429Percentage() {
		String redisQuery;
		try (WrappedRedis redis = WrappedRedis.get(0)) {
			redisQuery = redis.get().hget("statusCodes", "429 Too Many Requests");
		}
		if (redisQuery == null) {
			return "0.000";
		}
		int ratelimitsHit = Integer.parseInt(redisQuery);
		int totalRequests = getOutgoingRequests();
		return new DecimalFormat(".###").format(((double) ratelimitsHit / totalRequests) * 100);
	}
	
	static int dbsize(int db) {
		try (WrappedRedis redis = WrappedRedis.get(db)) {
			return redis.get().dbsize().intValue();
		}
	}
	
	private static void logInvalidRequest(String identifier, long time) {
		try (WrappedRedis redis = WrappedRedis.get(5)) {
			redis.get().hmset(identifier, Map.of("time", Long.toString(time)));
			redis.get().expire(identifier, MojangPipe.getInvalidLifetime() * 60L);
		}
	}
	
	static void logStatusMessage(String statusMessage) {
		try (WrappedRedis redis = WrappedRedis.get(0)) {
			redis.get().hincrby("statusCodes", statusMessage, 1);
		}
	}
	
	static void incrStats(String stat) {
		MojangPipe.getThreadPool().execute(() -> {
			try (WrappedRedis redis = WrappedRedis.get(0)) {
				redis.get().hincrby("stats", stat, 1);
			}
		});
	}
	
	//=================NORMAL OPERATIONS METHODS=================//
	static long getLastRequest(String identifier, int db) {
		try (WrappedRedis redis = WrappedRedis.get(db)) {
			return Long.parseLong(redis.get().hmget(identifier, "time").get(0).getValueOrElse("0"));
		}
	}
	
	static String getJson(String identifier, int db) {
		try (WrappedRedis redis = WrappedRedis.get(db)) {
			return redis.get().hmget(identifier, "json").get(0).getValueOrElse("");
		}
	}
	
	static void putJson(String identifier, long time, String json, int db) {
		try (WrappedRedis redis = WrappedRedis.get(db)) {
			redis.get().hmset(identifier, Map.of(
					"time", String.valueOf(time),
					"json", json
			));
			redis.get().expire(identifier, MojangPipe.getCacheLifetime() * 60L);
		}
	}
	
	static void handleStatusCode(int code, String identifier) {
		if (code == 204) {
			logInvalidRequest(identifier, System.currentTimeMillis());
		} else {
			MojangPipe.newProxy();
		}
	}
}