package io.misterfix.mojangpipe;

import com.beust.jcommander.Parameter;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import spark.Spark;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MojangPipe {
    @Parameter(names = {"--port"}, description = "The port on which the pipe service will run. Default 2580")
    private static final int port = 2580;
    @Parameter(names = {"--maxIdleConnections"}, description = "The maximum amount of idle connections to keep in the connection pool. Default 100")
    private static final int maxIdleConnections = 100;
    @Parameter(names = {"--cacheLifetime"}, description = "Cache lifetime in minutes before it expires. Default 30")
    private static final int cacheLifetime = 30;
    @Parameter(names = {"--invalidProfileLifetime"}, description = "The lifetime in minutes of requests before it expires. Default 30")
    private static final int invalidLifetime = 240;

    private static Map<String, String> sessionProfiles = new ConcurrentHashMap<>(), apiUuidProfiles = new ConcurrentHashMap<>(), apiNamesProfiles = new ConcurrentHashMap<>(), nameProfileProfiles = new ConcurrentHashMap<>();
    private static Map<String, Long> sessionRequests = new ConcurrentHashMap<>(), apiUuidRequests = new ConcurrentHashMap<>(), apiNamesRequests = new ConcurrentHashMap<>(), nameProfileRequests = new ConcurrentHashMap<>(), invalidRequests = new ConcurrentHashMap<>();
    private static Map<String, Object> stats = new ConcurrentHashMap<>();
    private static Map<String, Integer> apiStatusCodes = new ConcurrentHashMap<>();
    private static Map<Integer, Integer> proxies = new ConcurrentHashMap<Integer, Integer>() {{
        put(3129, 0);
        put(3130, 0);
        put(3131, 0);
        put(3132, 0);
        put(3133, 0);
    }};

    private static final ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(10);
    private static ConnectionPool connectionPool = new ConnectionPool(maxIdleConnections, 30, TimeUnit.SECONDS);

    public static void main(String[] args) {
        Spark.port(port);
        Spark.get("/sessionserver/:uuid", (request, response) -> {
            String uuid = request.params(":uuid");
            if(uuid.length() != 32 && uuid.length() != 36){
                response.status(400);
                return "";
            }
            long time = System.currentTimeMillis();
            String json = "";

            if(invalidRequests.containsKey(uuid) && (time - invalidRequests.get(uuid)) < (invalidLifetime * 60000)){
                response.status(204);
                threadPool.execute(() -> {
                    stats.put("profile_from_mem", ((int) stats.getOrDefault("profile_from_api", 0)) + 1);
                    System.out.println("Served profile for UUID " + uuid + " (from invalid requests cache)");
                });
            } else if(sessionRequests.containsKey(uuid) && (time - sessionRequests.get(uuid)) < (cacheLifetime * 60000)){
                json = sessionProfiles.get(uuid);
                threadPool.execute(()->{
                    stats.put("profile_from_mem", ((int)stats.getOrDefault("profile_from_mem", 0))+1);
                    System.out.println("Served profile for UUID "+uuid+" (from memory)");
                });
            } else {
                Request apiRequest = new Request.Builder().url("https://sessionserver.mojang.com/session/minecraft/profile/"+uuid).build();
                OkHttpClient client = Utils.getClient(proxies, connectionPool);
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
                    apiResponse.body().close();
                    sessionProfiles.put(uuid, json);
                    sessionRequests.put(uuid, time);
                } else if(responseCode == 204){
                    invalidRequests.put(uuid, time);
                }
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/api/name/:name", (request, response) -> {
            String name = request.params(":name");
            if(name.length() > 16){
                response.status(400);
                return "";
            }
            long time = System.currentTimeMillis();
            String json = "";

            if(invalidRequests.containsKey(name) && (time - invalidRequests.get(name)) < (invalidLifetime * 60000)){
                response.status(204);
                threadPool.execute(() -> {
                    stats.put("uuid_from_mem", ((int) stats.getOrDefault("uuid_from_mem", 0)) + 1);
                    System.out.println("Served UUID lookup for username " + name + " (from invalid requests cache)");
                });
            } else if(apiUuidRequests.containsKey(name) && (time - apiUuidRequests.get(name)) < (cacheLifetime * 60000)){
                json = apiUuidProfiles.get(name);
                threadPool.execute(()->{
                    stats.put("uuid_from_mem", ((int)stats.getOrDefault("uuid_from_mem", 0))+1);
                    System.out.println("Served UUID lookup for username "+name+" (from memory)");
                });
            }
            else{
                Request apiRequest = new Request.Builder().url("https://api.mojang.com/users/profiles/minecraft/"+name).build();
                OkHttpClient client = Utils.getClient(proxies, connectionPool);
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
                    apiResponse.body().close();
                    apiUuidProfiles.put(name, json);
                    apiUuidRequests.put(name, time);
                } else if(responseCode == 204){
                    invalidRequests.put(name, time);
                }
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/api/names/:uuid", (request, response) -> {
            String uuid = request.params(":uuid");
            if(uuid.length() != 32 && uuid.length() != 36){
                response.status(400);
                return "";
            }
            long time = System.currentTimeMillis();
            String json = "";

            if(invalidRequests.containsKey(uuid) && (time - invalidRequests.get(uuid)) < (invalidLifetime * 60000)){
                response.status(204);
                threadPool.execute(() -> {
                    stats.put("names_from_mem", ((int) stats.getOrDefault("names_from_api", 0)) + 1);
                    System.out.println("Served names list for UUID " + uuid + " (from invalid requests cache)");
                });
            } else if(apiNamesRequests.containsKey(uuid) && (time - apiNamesRequests.get(uuid)) < (cacheLifetime * 60000)){
                json = apiNamesProfiles.get(uuid);
                threadPool.execute(()->{
                    stats.put("names_from_mem", ((int)stats.getOrDefault("names_from_mem", 0))+1);
                    System.out.println("Served names list for UUID "+uuid+" (from memory)");
                });
            }
            else{
                Request apiRequest = new Request.Builder().url("https://api.mojang.com/user/profiles/"+uuid+"/names").build();
                OkHttpClient client = Utils.getClient(proxies, connectionPool);
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
                    apiResponse.body().close();
                    apiNamesProfiles.put(uuid, json);
                    apiNamesRequests.put(uuid, time);

                } else if(responseCode == 204){
                    invalidRequests.put(uuid, time);
                }
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/pipe/profile/:name", (request, response) -> {
            String name = request.params(":name");
            if(name.length() > 16){
                response.status(400);
                return "";
            }
            long time = System.currentTimeMillis();
            String json = "";

            if(invalidRequests.containsKey(name) && (time - invalidRequests.get(name)) < (invalidLifetime * 60000)){
                response.status(204);
                threadPool.execute(() -> {
                    stats.put("name_profile_from_mem", ((int) stats.getOrDefault("name_profile_from_mem", 0)) + 1);
                    System.out.println("Served profile lookup for name " + name + " (from invalid requests cache)");
                });
            } else if(nameProfileRequests.containsKey(name) && (time - nameProfileRequests.get(name)) < (cacheLifetime * 60000)){
                json = nameProfileProfiles.get(name);
                threadPool.execute(() -> {
                    stats.put("name_profile_from_mem", ((int) stats.getOrDefault("name_profile_from_mem", 0)) + 1);
                    System.out.println("Served profile lookup for name " + name + " (from memory)");
                });
            } else {
                Request apiRequest = new Request.Builder().url("https://api.mojang.com/users/profiles/minecraft/" + name).build();
                OkHttpClient client = Utils.getClient(proxies, connectionPool);
                Response apiResponse = client.newCall(apiRequest).execute();
                int apiResponseCode = apiResponse.code();

                threadPool.execute(() -> apiStatusCodes.put(apiResponseCode + " " + apiResponse.message(), apiStatusCodes.getOrDefault(apiResponseCode + " " + apiResponse.message(), 0) + 1));

                if(apiResponse.body() != null && apiResponseCode == 200){
                    String responseString = apiResponse.body().string();
                    apiResponse.body().close();
                    threadPool.execute(() -> {
                        apiUuidProfiles.put(name, responseString);
                        apiUuidRequests.put(name, time);
                    });
                    JSONObject responseJson = new JSONObject(responseString);
                    String uuid = responseJson.getString("id");

                    if(sessionRequests.containsKey(uuid) && (time - sessionRequests.get(uuid)) < (cacheLifetime * 60000)){
                        json = sessionProfiles.get(uuid);
                        threadPool.execute(() -> {
                            stats.put("uuid_from_mem", ((int) stats.getOrDefault("uuid_from_mem", 0)) + 1);
                            System.out.println("Served profile lookup for name " + name + " (partly from memory)");
                        });
                    } else {
                        Request sessionRequest = new Request.Builder().url("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid).build();
                        Response sessionResponse = client.newCall(sessionRequest).execute();
                        int responseCode = sessionResponse.code();
                        response.status(responseCode);

                        threadPool.execute(() -> {
                            stats.put("name_profile_from_api", ((int) stats.getOrDefault("name_profile_from_api", 0)) + 1);
                            apiStatusCodes.put(responseCode + " " + sessionResponse.message(), apiStatusCodes.getOrDefault(responseCode + " " + sessionResponse.message(), 0) + 1);
                            System.out.println("Served profile lookup for name " + name + " (" + sessionResponse.code() + ")");
                        });

                        if(sessionResponse.body() != null && responseCode == 200){
                            json = sessionResponse.body().string();
                            sessionResponse.body().close();
                            nameProfileProfiles.put(name, json);
                            nameProfileRequests.put(name, time);
                            sessionProfiles.put(uuid, json);
                            sessionRequests.put(uuid, time);
                        } else if(responseCode == 204){
                            invalidRequests.put(uuid, time);
                        }
                    }
                } else if(apiResponseCode == 204){
                    invalidRequests.put(name, time);
                    System.out.println("Served profile for name " + name + " (" + apiResponse.code() + ")");
                }
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/stats", (request, response) -> {
            StringBuilder responseCodeBreakdown = new StringBuilder();
            StringBuilder proxyUsageBreakdown = new StringBuilder();
            AtomicInteger outgoingRequests = new AtomicInteger();
            apiStatusCodes.forEach((code, count) -> {
                outgoingRequests.getAndAdd(count);
                responseCodeBreakdown.append("<tr><td>").append(code).append(":</td><td> ").append(count).append("</td></tr>\n");
            });
            proxies.forEach((proxy, count) -> proxyUsageBreakdown.append("<tr><td>").append(proxy).append(":</td><td> ").append(count).append("</td></tr>\n"));
            return "<html>\n" +
                "    <head>\n" +
                "        <title>Mojang pipe report</title>\n" +
                "    </head>\n" +
                "    <body>\n" +
                "        <table>\n" +
                "            <tr><td>Time</td><td> "+new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date())+"</td></tr>\n" +
                    "            <tr><td>Connections</td><td> " + connectionPool.connectionCount() + "</td></tr>\n" +
                    "            <tr><td>Idle Connections</td><td> " + connectionPool.idleConnectionCount() + "</td></tr>\n" +
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                    "            <tr><td>Requests served from memory&nbsp;&nbsp;</td><td> " + (((int) stats.getOrDefault("profile_from_mem", 0)) + ((int) stats.getOrDefault("uuid_from_mem", 0)) + ((int) stats.getOrDefault("names_from_mem", 0)) + ((int) stats.getOrDefault("uuid_from_mem", 0)) + ((int) stats.getOrDefault("name_profile_from_mem", 0))) + "</td></tr>\n" +
                    "            <tr><td>Requests served from API</td><td> " + (((int) stats.getOrDefault("profile_from_api", 0)) + ((int) stats.getOrDefault("uuid_from_api", 0)) + ((int) stats.getOrDefault("names_from_api", 0)) + ((int) stats.getOrDefault("name_profile_from_api", 0))) + "</td></tr>\n" +
                    "            <tr><td>Outgoing API requests</td><td> " + outgoingRequests.get() + "</td></tr>\n" +
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Profile requests</td><td> "+(((int)stats.getOrDefault("profile_from_mem", 0))+((int)stats.getOrDefault("profile_from_api", 0)))+"</td></tr>\n" +
                    "            <tr><td>Name requests</td><td> " + (((int) stats.getOrDefault("uuid_from_mem", 0)) + ((int) stats.getOrDefault("uuid_from_api", 0))) + "</td></tr>\n" +
                "            <tr><td>Name list requests</td><td> "+(((int)stats.getOrDefault("names_from_mem", 0))+((int)stats.getOrDefault("names_from_api", 0)))+"</td></tr>\n" +
                    "            <tr><td>Name profile requests</td><td> " + (((int) stats.getOrDefault("name_profile_from_mem", 0)) + ((int) stats.getOrDefault("name_profile_from_api", 0))) + "</td></tr>\n" +
                    "            <tr><td>Total requests served</td><td> " + (((int) stats.getOrDefault("profile_from_mem", 0)) + ((int) stats.getOrDefault("uuid_from_mem", 0)) + ((int) stats.getOrDefault("names_from_mem", 0)) + ((int) stats.getOrDefault("profile_from_api", 0)) + ((int) stats.getOrDefault("uuid_from_api", 0)) + ((int) stats.getOrDefault("names_from_api", 0)) + ((int) stats.getOrDefault("name_profile_from_mem", 0)) + ((int) stats.getOrDefault("name_profile_from_api", 0))) + "</td></tr>\n" +
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Profiles in memory</td><td> "+sessionProfiles.size()+"</td></tr>\n"+
                "            <tr><td>UUIDs in memory</td><td> "+ apiUuidProfiles.size()+"</td></tr>\n"+
                "            <tr><td>Name lists in memory</td><td> "+apiNamesProfiles.size()+"</td></tr>\n"+
                    "            <tr><td>Name profiles in memory</td><td> " + nameProfileProfiles.size() + "</td></tr>\n" +
                    "            <tr><td>Invalid requests in memory</td><td> " + invalidRequests.size() + "</td></tr>\n" +
                "            <tr><td>---Response codes breakdown---</td><td>-----------------------</td></tr>\n"+
                             responseCodeBreakdown.toString()+
                "            <tr><td>-----Proxy usage breakdown-----</td><td>-----------------------</td></tr>\n"+
                             proxyUsageBreakdown.toString()+
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Used memory</td><td> "+Utils.readableFileSize(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+"</td></tr>\n"+
                "        </table>\n" +
                "    </body>\n" +
                    "</html>";
        });

        Spark.exception(Exception.class, (e, req, res) -> e.printStackTrace());
        threadPool.scheduleAtFixedRate(()->{
            apiNamesRequests.forEach((uuid, time)->{
                if((System.currentTimeMillis() - time) > (cacheLifetime * 60000)){
                    apiNamesRequests.remove(uuid);
                    apiNamesProfiles.remove(uuid);
                }
            });
            apiUuidRequests.forEach((uuid, time)->{
                if((System.currentTimeMillis() - time) > (cacheLifetime * 60000)){
                    apiUuidRequests.remove(uuid);
                    apiUuidProfiles.remove(uuid);
                }
            });
            sessionRequests.forEach((uuid, time)->{
                if((System.currentTimeMillis() - time) > (cacheLifetime * 60000)){
                    sessionRequests.remove(uuid);
                    sessionProfiles.remove(uuid);
                }
            });
            invalidRequests.forEach((name, time) -> {
                if((System.currentTimeMillis() - time) > (invalidLifetime * 60000)){
                    invalidRequests.remove(name);
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