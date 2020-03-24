package io.misterfix.mojangpipe;

import java.util.HashMap;
import java.util.Map;

class Ratelimit {
	private static final Map<String, Long> requestsInProgress = new HashMap<>();
	
	static void checkAndAdd(String identifier) throws InterruptedException {
		synchronized (requestsInProgress) {
			while (requestsInProgress.containsKey(identifier)) {
				requestsInProgress.wait();
			}
			requestsInProgress.put(identifier, System.currentTimeMillis());
		}
	}
	static void remove(String identifier) {
		synchronized (requestsInProgress) {
			requestsInProgress.remove(identifier);
			requestsInProgress.notifyAll();
		}
	}
	static int getRequestsInProgress() {
		synchronized (requestsInProgress) {
			return requestsInProgress.size();
		}
	}
	static void removeExpired() {
		synchronized (requestsInProgress) {
			long time = System.currentTimeMillis();
			requestsInProgress.values().removeIf(value -> time - value > 300);
		}
	}
}
