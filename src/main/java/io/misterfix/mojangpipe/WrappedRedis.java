package io.misterfix.mojangpipe;

import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.List;

public class WrappedRedis implements AutoCloseable {
	private static final List<WrappedRedis> databases = new ArrayList<>(6);
	private final RedisCommands<String, String> redis;
	private boolean busy;
	
	WrappedRedis(RedisCommands<String, String> redis) {
		this.redis = redis;
		databases.add(this);
	}
	RedisCommands<String, String> get() {
		return redis;
	}
	static WrappedRedis get(int db) {
		WrappedRedis redis = databases.get(db);
		synchronized (redis) {
			if (redis.busy) {
				try {
					redis.wait();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
			redis.busy = true;
		}
		return redis;
	}
	@Override
	public synchronized void close() {
		busy = false;
		notify();
	}
}
