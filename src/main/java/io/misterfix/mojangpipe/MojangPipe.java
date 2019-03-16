package io.misterfix.mojangpipe;

import com.beust.jcommander.Parameter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import spark.Spark;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MojangPipe {
    @Parameter(names = {"--port"}, description = "The port on which the pipe service will run. Default 2580")
    private static final int port = 2580;

    private static Map<String, String> sessionProfiles = new ConcurrentHashMap<>(), apiUuidProfiles = new ConcurrentHashMap<>(), apiNamesProfiles = new ConcurrentHashMap<>();
    private static Map<String, Long> sessionRequests = new ConcurrentHashMap<>(), apiUuidRequests = new ConcurrentHashMap<>(), apiNamesRequests = new ConcurrentHashMap<>();
    private static Map<String, Object> stats = new ConcurrentHashMap<>();
    private static Map<String, Integer> apiStatusCodes = new ConcurrentHashMap<>();
    private static Map<Integer, Integer> proxies = new ConcurrentHashMap<Integer, Integer>() {{
        put(3128, 0);
        put(3129, 0);
        put(3130, 0);
        put(3131, 0);
        put(3132, 0);
    }};

    private static final ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(10);

    public static void main(String[] args) {
        Spark.port(port);

        Spark.get("/sessionserver/:uuid", (request, response) -> {
            String uuid = request.params(":uuid");
            String json = "";

            if(sessionRequests.containsKey(uuid) && (System.currentTimeMillis() - sessionRequests.get(uuid)) < 600000){
                json = sessionProfiles.get(uuid);
                String finalJson = json;
                threadPool.execute(()->{
                    stats.put("profile_from_mem", ((int)stats.getOrDefault("profile_from_mem", 0))+1);
                    stats.put("bytes_served", ((long)stats.getOrDefault("bytes_served", 0L))+ finalJson.length());
                    System.out.println("Served profile for UUID "+uuid+" (from memory)");
                });
            }
            else{
                Request apiRequest = new Request.Builder().url("https://sessionserver.mojang.com/session/minecraft/profile/"+uuid).build();
                OkHttpClient client = Utils.getClient(proxies);
                Response apiResponse = client.newCall(apiRequest).execute();

                int responseCode = apiResponse.code();
                response.status(responseCode);

                threadPool.execute(()->{
                    stats.put("profile_from_api", ((int)stats.getOrDefault("profile_from_api", 0))+1);
                    apiStatusCodes.put(responseCode+" "+apiResponse.message(), apiStatusCodes.getOrDefault(responseCode+" "+apiResponse.message(), 0)+1);
                    System.out.println("Served profile for UUID "+uuid+" ("+apiResponse.code()+")");
                });

                if(apiResponse.body() != null && responseCode == 200){
                    json = apiResponse.body().string();
                    String finalJson1 = json;
                    threadPool.execute(()->{
                        sessionProfiles.put(uuid, finalJson1);
                        sessionRequests.put(uuid, System.currentTimeMillis());
                        stats.put("bytes_served", ((long)stats.getOrDefault("bytes_served", 0L))+ finalJson1.length());
                    });
                }
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/api/name/:name", (request, response) -> {
            String name = request.params(":name");
            String json = "";

            if(apiUuidRequests.containsKey(name) && (System.currentTimeMillis() - apiUuidRequests.get(name)) < 600000){
                json = apiUuidProfiles.get(name);
                String finalJson = json;
                threadPool.execute(()->{
                    stats.put("uuid_from_mem", ((int)stats.getOrDefault("uuid_from_mem", 0))+1);
                    stats.put("bytes_served", ((long)stats.getOrDefault("bytes_served", 0L))+ finalJson.length());
                    System.out.println("Served UUID lookup for username "+name+" (from memory)");
                });
            }
            else{
                Request apiRequest = new Request.Builder().url("https://api.mojang.com/users/profiles/minecraft/"+name).build();
                OkHttpClient client = Utils.getClient(proxies);
                Response apiResponse = client.newCall(apiRequest).execute();

                int responseCode = apiResponse.code();
                response.status(responseCode);

                threadPool.execute(()->{
                    stats.put("uuid_from_api", ((int)stats.getOrDefault("uuid_from_api", 0))+1);
                    apiStatusCodes.put(responseCode+" "+apiResponse.message(), apiStatusCodes.getOrDefault(responseCode+" "+apiResponse.message(), 0)+1);
                    System.out.println("Served UUID lookup for username "+name+" ("+apiResponse.code()+")");
                });

                if(apiResponse.body() != null && responseCode == 200){
                    json = apiResponse.body().string();
                    String finalJson1 = json;
                    threadPool.execute(()->{
                        apiUuidProfiles.put(name, finalJson1);
                        apiUuidRequests.put(name, System.currentTimeMillis());
                        stats.put("bytes_served", ((long)stats.getOrDefault("bytes_served", 0L))+ finalJson1.length());
                    });
                }
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/api/names/:uuid", (request, response) -> {
            String uuid = request.params(":uuid");
            String json = "";

            if(apiNamesRequests.containsKey(uuid) && (System.currentTimeMillis() - apiNamesRequests.get(uuid)) < 600000){
                json = apiNamesProfiles.get(uuid);
                String finalJson = json;
                threadPool.execute(()->{
                    stats.put("names_from_mem", ((int)stats.getOrDefault("names_from_mem", 0))+1);
                    stats.put("bytes_served", ((long)stats.getOrDefault("bytes_served", 0L))+finalJson.length());
                    System.out.println("Served names list for UUID "+uuid+" (from memory)");
                });
            }
            else{
                Request apiRequest = new Request.Builder().url("https://api.mojang.com/user/profiles/"+uuid+"/names").build();
                OkHttpClient client = Utils.getClient(proxies);
                Response apiResponse = client.newCall(apiRequest).execute();

                int responseCode = apiResponse.code();
                response.status(responseCode);

                threadPool.execute(()->{
                    stats.put("names_from_api", ((int)stats.getOrDefault("names_from_api", 0))+1);
                    apiStatusCodes.put(responseCode+" "+apiResponse.message(), apiStatusCodes.getOrDefault(responseCode+" "+apiResponse.message(), 0)+1);
                    System.out.println("Served names list for UUID "+uuid+" ("+apiResponse.code()+")");
                });

                if(apiResponse.body() != null && responseCode == 200){
                    json = apiResponse.body().string();
                    String finalJson1 = json;
                    threadPool.execute(()->{
                        apiNamesProfiles.put(uuid, finalJson1);
                        apiNamesRequests.put(uuid, System.currentTimeMillis());
                        stats.put("bytes_served", ((long)stats.getOrDefault("bytes_served", 0L))+finalJson1.length());
                    });
                }
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/stats", ((request, response) -> {
            StringBuilder responseCodeBreakdown = new StringBuilder();
            StringBuilder proxyUsageBreakdown = new StringBuilder();
            apiStatusCodes.forEach((code, count)-> responseCodeBreakdown.append("            <tr><td>").append(code).append(":</td><td> ").append(count).append("</td></tr>\n"));
            proxies.forEach((proxy, count) -> proxyUsageBreakdown.append("            <tr><td>").append(proxy).append(":</td><td> ").append(count).append("</td></tr>\n"));
            return "<html>\n" +
                "    <head>\n" +
                "        <title>Mojang pipe report</title>\n" +
                "    </head>\n" +
                "    <body>\n" +
                "        <table>\n" +
                "            <tr><td>Time</td><td> "+new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date())+"</td></tr>\n" +
                "            <tr><td>Bytes served</td><td> "+Utils.readableFileSize((long)stats.getOrDefault("bytes_served", 0L))+"</td></tr>\n" +
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Requests served from memory&nbsp;&nbsp;&nbsp;</td><td> "+(((int)stats.getOrDefault("profile_from_mem", 0))+((int)stats.getOrDefault("uuid_from_mem", 0))+((int)stats.getOrDefault("names_from_mem", 0)))+"</td></tr>\n" +
                "            <tr><td>Requests served from API</td><td> "+(((int)stats.getOrDefault("profile_from_api", 0))+((int)stats.getOrDefault("uuid_from_api", 0))+((int)stats.getOrDefault("names_from_api", 0)))+"</td></tr>\n" +
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Profile requests</td><td> "+(((int)stats.getOrDefault("profile_from_mem", 0))+((int)stats.getOrDefault("profile_from_api", 0)))+"</td></tr>\n" +
                "            <tr><td>UUID requests</td><td> "+(((int)stats.getOrDefault("uuid_from_mem", 0))+((int)stats.getOrDefault("uuid_from_api", 0)))+"</td></tr>\n" +
                "            <tr><td>Name list requests</td><td> "+(((int)stats.getOrDefault("names_from_mem", 0))+((int)stats.getOrDefault("names_from_api", 0)))+"</td></tr>\n" +
                "            <tr><td>Total requests served</td><td> "+(((int)stats.getOrDefault("profile_from_mem", 0))+((int)stats.getOrDefault("uuid_from_mem", 0))+((int)stats.getOrDefault("names_from_mem", 0))+((int)stats.getOrDefault("profile_from_api", 0))+((int)stats.getOrDefault("uuid_from_api", 0))+((int)stats.getOrDefault("names_from_api", 0)))+"</td></tr>\n" +
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Profiles in memory</td><td> "+sessionProfiles.size()+"</td></tr>\n"+
                "            <tr><td>UUIDs in memory</td><td> "+ apiUuidProfiles.size()+"</td></tr>\n"+
                "            <tr><td>Name lists in memory</td><td> "+apiNamesProfiles.size()+"</td></tr>\n"+
                "            <tr><td>---Response codes breakdown---</td><td>-----------------------</td></tr>\n"+
                             responseCodeBreakdown.toString()+
                "            <tr><td>-----Proxy usage breakdown-----</td><td>-----------------------</td></tr>\n"+
                             proxyUsageBreakdown.toString()+
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Used memory</td><td> "+Utils.readableFileSize(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+"</td></tr>\n"+
                "        </table>\n" +
                "    </body>\n" +
                "</html>";}));

        Spark.exception(Exception.class, (e, req, res) -> e.printStackTrace());

        threadPool.scheduleAtFixedRate(()->{
            apiNamesRequests.forEach((uuid, time)->{
                if((System.currentTimeMillis() - time) > 600000){
                    apiNamesRequests.remove(uuid);
                    apiNamesProfiles.remove(uuid);
                }
            });
            apiUuidRequests.forEach((uuid, time)->{
                if((System.currentTimeMillis() - time) > 600000){
                    apiUuidRequests.remove(uuid);
                    apiUuidProfiles.remove(uuid);
                }
            });
            sessionRequests.forEach((uuid, time)->{
                if((System.currentTimeMillis() - time) > 600000){
                    sessionRequests.remove(uuid);
                    sessionProfiles.remove(uuid);
                }
            });
        }, 10, 10, TimeUnit.MINUTES);

        threadPool.scheduleAtFixedRate(()-> proxies.replaceAll((k, v)-> 0), 1, 1, TimeUnit.HOURS);

        Spark.awaitInitialization();
    }

    static Map<Integer, Integer> getProxies(){
        return proxies;
    }
}