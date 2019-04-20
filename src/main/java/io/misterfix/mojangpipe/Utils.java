package io.misterfix.mojangpipe;

import io.lettuce.core.api.sync.RedisCommands;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.text.DecimalFormat;
import java.util.Base64;
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

    static String getTextures(String json){
        JSONObject responseJson = new JSONObject();
        JSONObject profile = new JSONObject(json);
        String data = profile.getJSONArray("properties").getJSONObject(0).getString("value");
        String base64 = new String(Base64.getDecoder().decode(data));
        JSONObject textures = new JSONObject(base64).getJSONObject("textures");
        responseJson.put("uuid", profile.getString("id"));
        responseJson.put("name", profile.getString("name"));
        if(!textures.isNull("SKIN")){
            responseJson.put("skin", textures.getJSONObject("SKIN").getString("url"));
        }
        if(!textures.isNull("CAPE")){
            responseJson.put("cape", textures.getJSONObject("CAPE").getString("url"));
        }
        if(!profile.isNull("legacy")){
            responseJson.put("legacy", true);
        }
        return responseJson.toString();
    }
}
