package io.misterfix.mojangpipe;

import io.lettuce.core.api.sync.RedisCommands;
import okhttp3.OkHttpClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.Set;

class Utils {
    static String readableFileSize(long size) {
        if(size <= 0) return "0";
        final String[] units = new String[] { "B", "kB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(size)/Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size/Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private static int getOptimalProxy(Map<String, String> map, Set<String> keys){
        int minKey = 0;
        long minValue = Long.MAX_VALUE;
        for (String key : keys) {
            long value = Long.valueOf(map.get(key));
            if(value < minValue) {
                minValue = value;
                minKey = Integer.valueOf(key);
            }
        }
        return minKey;
    }

    static OkHttpClient getClient(){
        RedisCommands<String, String> redis = MojangPipe.getRedis();
        redis.select(0);
        Map<String, String> proxies = redis.hgetall("proxies");
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        int proxy = getOptimalProxy(proxies, proxies.keySet());
        clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxy)));
        redis.hmset("proxies", Map.of(String.valueOf(proxy), Long.toString(System.currentTimeMillis())));
        return clientBuilder.build();
    }
}
