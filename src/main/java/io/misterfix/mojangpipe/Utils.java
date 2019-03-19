package io.misterfix.mojangpipe;

import okhttp3.ConnectionPool;
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

    private static int getOptimalProxy(Map<Integer, Integer> map, Set<Integer> keys){
        int minKey = 0;
        int minValue = Integer.MAX_VALUE;
        for (int key : keys) {
            int value = map.get(key);
            if(value < minValue) {
                minValue = value;
                minKey = key;
            }
        }
        return minKey;
    }

    static OkHttpClient getClient(Map<Integer, Integer> proxies, ConnectionPool connectionPool){
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        clientBuilder.connectionPool(connectionPool);
        int proxy = getOptimalProxy(proxies, proxies.keySet());
        clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxy)));
        MojangPipe.getProxies().put(proxy, MojangPipe.getProxies().get(proxy) + 1);
        return clientBuilder.build();
    }
}
