package io.misterfix.mojangpipe;

import io.lettuce.core.KeyValue;
import io.lettuce.core.api.sync.RedisCommands;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class Redis {
	static void init(List<String> networkInterfaces) {
		try (WrappedRedis wrapped = WrappedRedis.get(0)) {
			RedisCommands<String, String> redis = wrapped.get();

			networkInterfaces.forEach((networkInterface)-> {
				String interfaceTest = redis.hmget("interfaces", networkInterface).get(0).getValueOrElse("0");
				if(interfaceTest.equals("0")) {
					redis.hmset("interfaces", Map.of(networkInterface, "0"));
				}
			});
			redis.hmset("stats", Map.of(
					"profile_from_mem", "0",
					"profile_from_api", "0",
					"uuid_from_mem", "0",
					"uuid_from_api", "0",
					"names_from_mem", "0",
					"names_from_api", "0",
					"from_invalid_cache", "0"
			));
			redis.del("statusCodes");
			redis.hmset("statusCodes", Map.of("200", "0"));
		}
	}
	private static int getIntStats(String... fields) {
		try (WrappedRedis redis = WrappedRedis.get(0)) {
			return Arrays.stream(fields)
					.mapToInt(field -> Integer.parseInt(redis.get().hget("stats", field)))
					.sum();
		}
	}
	static int getRequestsFromMemory() {
		return getIntStats("profile_from_mem", "uuid_from_mem", "names_from_mem", "from_invalid_cache");
	}
	static int getRequestsFromApi() {
		return getIntStats("profile_from_api", "uuid_from_api", "names_from_api");
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
	static String getRatelimitPercentage() {
		String requests_429;
		String requests_403;
		try (WrappedRedis redis = WrappedRedis.get(0)) {
			List<KeyValue<String, String>> test = redis.get().hmget("statusCodes", "403", "429");
			requests_403 = test.get(0).getValueOrElse("0");
			requests_429 = test.get(1).getValueOrElse("0");
		}
		int ratelimitsHit = Integer.parseInt(requests_429)+Integer.parseInt(requests_403);
		long totalRequests = MojangPipe.getTotalOutgoingRequests();
		return new DecimalFormat(".###").format(((double) ratelimitsHit / totalRequests) * 100);
	}
	static int dbsize(int db) {
		try (WrappedRedis redis = WrappedRedis.get(db)) {
			return redis.get().dbsize().intValue();
		}
	}
	static void logInvalidRequest(String identifier, long time) {
		try (WrappedRedis redis = WrappedRedis.get(5)) {
			redis.get().hmset(identifier, Map.of("time", Long.toString(time)));
			redis.get().expire(identifier, MojangPipe.getInvalidLifetime() * 60L);
		}
	}
	static void logStatusCode(int statusMessage) {
		MojangPipe.getThreadPool().execute(() -> {
			try (WrappedRedis redis = WrappedRedis.get(0)) {
				redis.get().hincrby("statusCodes", String.valueOf(statusMessage), 1);
			}
		});
	}
	static void incrStats(String stat) {
		MojangPipe.getThreadPool().execute(() -> {
			try (WrappedRedis redis = WrappedRedis.get(0)) {
				redis.get().hincrby("stats", stat, 1);
			}
		});
	}
	static void incrStats(String stat, int code) {
		MojangPipe.getThreadPool().execute(() -> {
			try (WrappedRedis redis = WrappedRedis.get(0)) {
				redis.get().hincrby("stats", stat, 1);
				redis.get().hincrby("statusCodes", String.valueOf(code), 1);
			}
		});
	}
	static void putJson(String identifier, long time, String json, int db) {
		try (WrappedRedis redis = WrappedRedis.get(db)) {
			redis.get().hmset(identifier, Map.of(
					"time", String.valueOf(time),
					"json", json
			));
			MojangPipe.getThreadPool().execute(()-> redis.get().expire(identifier, MojangPipe.getCacheLifetime()/1000));
		}
	}
	static boolean isInvalidIdentifier(String identifier, long time){
		try (WrappedRedis redis = WrappedRedis.get(5)) {
			long lastRequest = Long.parseLong(redis.get().hmget(identifier, "time").get(0).getValueOrElse("0"));
			return (time - lastRequest) < MojangPipe.getInvalidLifetime();
		}
	}
	static void handleStatusCode(int code, String identifier) {
		if(code == 429 || code == 403){
			MojangPipe.newInterface();
		}
		else if(code == 204){
			logInvalidRequest(identifier, System.currentTimeMillis());
		}
	}
}