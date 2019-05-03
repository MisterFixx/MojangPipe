package io.misterfix.mojangpipe;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;
import spark.Spark;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static spark.Spark.halt;

public class MojangPipe {
    private static long startTime;
    private static OkHttpClient client;
    private static RedisCommands<String, String> redis;
    private static final ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(2);
    private static final String API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    public static void main(String[] args) {
        startTime = System.currentTimeMillis();
        OptionParser curParser = new OptionParser();
        curParser.allowsUnrecognizedOptions();
        OptionSpec<Integer> optPort = curParser.accepts("port").withRequiredArg().ofType(Integer.class).defaultsTo(2580);
        OptionSpec<Integer> optCacheLifetime = curParser.accepts("cacheLifetime").withRequiredArg().ofType(Integer.class).defaultsTo(30);
        OptionSpec<Integer> optInvalidLifetime = curParser.accepts("invalidProfileLifetime").withRequiredArg().ofType(Integer.class).defaultsTo(240);
        OptionSpec<String> optRedisHost = curParser.accepts("redisHost").withRequiredArg().ofType(String.class);
        OptionSpec<Integer> optRedisPort = curParser.accepts("redisPort").withRequiredArg().ofType(Integer.class).defaultsTo(6379);
        OptionSpec<String> optRedisPass = curParser.accepts("redisPass").withRequiredArg().ofType(String.class).defaultsTo("P4azzw0rd");
        OptionSet options = curParser.parse(args);
        int cacheLifetime = options.valueOf(optCacheLifetime);
        int invalidLifetime = options.valueOf(optInvalidLifetime);

        RedisClient redisClient = RedisClient.create("redis://"+options.valueOf(optRedisPass)+"@"+options.valueOf(optRedisHost)+":"+options.valueOf(optRedisPort)+"/0");
        redisClient.setOptions(ClientOptions.builder().autoReconnect(true).build());
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        redis = connection.sync();
        Redis.init();
        client = Utils.getClient();

        Spark.port(options.valueOf(optPort));
        Spark.get("/sessionserver/*", (request, response) -> {
            if(request.splat().length == 0) halt(400);
            String[] route = request.splat()[0].split("/");
            if(route.length < 1) halt(400);

            String uuid = route[0];
            if(uuid.length() != 32) halt(400);
            Ratelimit.checkAndAdd(uuid);
            long time = System.currentTimeMillis();
            boolean texturesOnly = route.length == 2 && route[1].equalsIgnoreCase("textures");
            String json = "";

            if((time - Redis.getLastRequest(uuid, 5)) < (invalidLifetime * 60000)){
                response.status(204);
                Redis.incrStats("served_from_invalid_cache");
                System.out.println("Served profile for UUID "+uuid+" (from invalid requests cache)");
            }
            else if((time - Redis.getLastRequest(uuid,1)) < (cacheLifetime * 60000)){
                json = Redis.getJson(uuid, 1);
                Redis.incrStats("profile_from_mem");
                System.out.println("Served profile for UUID "+uuid+" (from memory)");
            }
            else {
                Request apiRequest = new Request.Builder().url(SESSION_URL+uuid).build();
                Response apiResponse = client.newCall(apiRequest).execute();
                int responseCode = apiResponse.code();
                ResponseBody body = apiResponse.body();

                Redis.incrStats("profile_from_api");
                Redis.logStatusMessage(responseCode+" "+apiResponse.message());
                System.out.println("Served profile for UUID "+uuid+" ("+responseCode+")");
                if(body != null && responseCode == 200){
                    json = body.string();
                    Redis.putJson(uuid, time, json, 1);
                }
                else{
                    Redis.handleStatusCode(responseCode, uuid);
                    response.status(responseCode);
                }
                apiResponse.close();
            }

            response.type("Application/json");
            Ratelimit.remove(uuid);
            if(texturesOnly && !json.isEmpty()) return Utils.getTextures(json);
            else return json;
        });

        Spark.get("/api/name/:name", (request, response) -> {
            String name = request.params(":name");
            if(name.length() > 17) halt(400);
            long time = System.currentTimeMillis();
            String json = "";

            if((time - Redis.getLastRequest(name, 5)) < (invalidLifetime * 60000)){
                response.status(204);
                Redis.incrStats("served_from_invalid_cache");
                System.out.println("Served UUID lookup for username " + name + " (from invalid requests cache)");
            }
            else if((time - Redis.getLastRequest(name, 2)) < (cacheLifetime * 60000)){
                json = Redis.getJson(name, 2);
                Redis.incrStats("uuid_from_mem");
                System.out.println("Served UUID lookup for username "+name+" (from memory)");
            }
            else{
                Request apiRequest = new Request.Builder().url(API_URL+name).build();
                Response apiResponse = client.newCall(apiRequest).execute();
                int responseCode = apiResponse.code();
                ResponseBody body = apiResponse.body();

                Redis.incrStats("uuid_from_api");
                Redis.logStatusMessage(responseCode+" "+apiResponse.message());
                System.out.println("Served UUID lookup for username "+name+" ("+responseCode+")");
                if(body != null && responseCode == 200){
                    json = body.string();
                    Redis.putJson(name, time, json, 2);
                }
                else{
                    Redis.handleStatusCode(responseCode, name);
                    response.status(responseCode);
                }
                apiResponse.close();
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/api/names/:uuid", (request, response) -> {
            String uuid = request.params(":uuid");
            if(uuid.length() != 32) halt(400);
            long time = System.currentTimeMillis();
            String json = "";

            if((time - Redis.getLastRequest(uuid, 5)) < (invalidLifetime * 60000)){
                response.status(204);
                Redis.incrStats("served_from_invalid_cache");
                System.out.println("Served names list for UUID " + uuid + " (from invalid requests cache)");
            }
            else if((time - Redis.getLastRequest(uuid, 3)) < (cacheLifetime * 60000)){
                json = Redis.getJson(uuid, 3);
                Redis.incrStats("names_from_mem");
                System.out.println("Served names list for UUID "+uuid+" (from memory)");
            }
            else{
                Request apiRequest = new Request.Builder().url("https://api.mojang.com/user/profiles/"+uuid+"/names").build();
                Response apiResponse = client.newCall(apiRequest).execute();
                int responseCode = apiResponse.code();
                ResponseBody body = apiResponse.body();

                Redis.incrStats("names_from_api");
                Redis.logStatusMessage(responseCode+" "+apiResponse.message());
                System.out.println("Served names list for UUID "+uuid+" ("+responseCode+")");
                if(body != null && responseCode == 200){
                    json = body.string();
                    Redis.putJson(uuid, time, json, 3);
                }
                else {
                    Redis.handleStatusCode(responseCode, uuid);
                    response.status(responseCode);
                }
                apiResponse.close();
            }

            response.type("Application/json");
            return json;
        });

        Spark.get("/pipe/profile/*", (request, response) -> {
            if(request.splat().length == 0) halt(400);
            String[] route = request.splat()[0].split("/");
            if(route.length < 1) halt(400);

            String name = route[0];
            if(name.length() > 17) halt(400);
            Ratelimit.checkAndAdd(name);
            long time = System.currentTimeMillis();
            boolean texturesOnly = route.length == 2 && route[1].equalsIgnoreCase("textures");
            String json = "";

            if((time - Redis.getLastRequest(name, 5)) < (invalidLifetime * 60000)){
                response.status(204);
                Redis.incrStats("served_from_invalid_cache");
                System.out.println("Served profile for name " + name + " (from invalid requests cache)");
            }
            else if((time - Redis.getLastRequest(name, 4)) < (cacheLifetime * 60000)){
                json = Redis.getJson(name, 4);
                Redis.incrStats("name_profile_from_mem");
                System.out.println("Served profile for name " + name + " (from memory)");
            }
            else {
                Request apiRequest = new Request.Builder().url(API_URL+name).build();
                Response apiResponse = client.newCall(apiRequest).execute();
                ResponseBody apiBody = apiResponse.body();
                int apiResponseCode = apiResponse.code();

                Redis.logStatusMessage(apiResponseCode+" "+apiResponse.message());
                if(apiBody != null && apiResponseCode == 200){
                    String responseString = apiBody.string();
                    Redis.putJson(name, time, responseString, 2);
                    String uuid = new JSONObject(responseString).getString("id");
                    Ratelimit.add(uuid);

                    if((time - Redis.getLastRequest(uuid, 1)) < (cacheLifetime * 60000)){
                        json = Redis.getJson(uuid, 1);
                        Redis.putJson(name, time, json, 4);
                        Redis.incrStats("uuid_from_mem");
                        System.out.println("Served profile for name "+name+" (partly from memory)");
                    }
                    else {
                        Request sessionRequest = new Request.Builder().url(SESSION_URL+uuid).build();
                        Response sessionResponse = client.newCall(sessionRequest).execute();
                        int responseCode = sessionResponse.code();
                        ResponseBody sessionBody = sessionResponse.body();

                        Redis.incrStats("name_profile_from_api");
                        Redis.logStatusMessage(sessionResponse.code()+" "+sessionResponse.message());
                        System.out.println("Served profile for name "+name+" ("+responseCode+")");
                        if(sessionBody != null && responseCode == 200){
                            json = sessionBody.string();
                            Redis.putJson(name, time, json, 4);
                            Redis.putJson(uuid, time, json, 1);
                        }
                        else{
                            Redis.handleStatusCode(apiResponseCode, name);
                            Redis.handleStatusCode(apiResponseCode, uuid);
                            response.status(responseCode);
                        }
                        sessionResponse.close();
                    }
                    Ratelimit.remove(uuid);
                }
                else{
                    Redis.handleStatusCode(apiResponseCode, name);
                    System.out.println("Served profile for name " + name + " (" + apiResponse.code() + ")");
                }
                apiResponse.close();
            }

            response.type("Application/json");
            Ratelimit.remove(name);
            if(texturesOnly && !json.isEmpty()) return Utils.getTextures(json);
            else return json;
        });

        Spark.get("/stats", (request, response) -> {
            StringBuilder responseCodeBreakdown = new StringBuilder();
            redis.select(0);
            redis.hgetall("statusCodes").forEach((code, count)-> responseCodeBreakdown.append("<tr><td>").append(code).append(":</td><td> ").append(count).append("</td></tr>\n"));
            return "<html>\n" +
                "    <head>\n" +
                "        <title>Mojang pipe report</title>\n" +
                "    </head>\n" +
                "    <body>\n" +
                "        <table>\n" +
                "            <tr><td>Current Time</td><td> "+new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date())+"</td></tr>\n" +
                "            <tr><td>Time started</td><td> "+new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(startTime))+"</td></tr>\n" +
                "            <tr><td>429 hit rate</td><td> "+Redis.get429Percentage()+"%</td></tr>\n"+
                "            <tr><td>Proxy in rotation</td><td> "+((InetSocketAddress) client.proxy().address()).getPort()+"</td></tr>\n"+
                "            <tr><td>Requests in progress</td><td> "+Ratelimit.getRequestsInProgress().size()+"</td></tr>\n"+
                "            <tr><td>Used memory</td><td> "+Utils.readableFileSize(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+"</td></tr>\n"+
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Requests served from memory&nbsp;&nbsp;</td><td> "+Redis.getRequestsFromMemory()+"</td></tr>\n" +
                "            <tr><td>Requests served from API</td><td> "+Redis.getRequestsFromApi()+"</td></tr>\n" +
                "            <tr><td>Outgoing API requests</td><td> "+Redis.getOutgoingRequests()+"</td></tr>\n" +
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Profile requests</td><td> "+Redis.getProfileRequestsCount()+"</td></tr>\n" +
                "            <tr><td>Name requests</td><td> "+Redis.getNameRequestsCount()+"</td></tr>\n" +
                "            <tr><td>Name list requests</td><td> "+Redis.getNamesRequestsCount()+"</td></tr>\n" +
                "            <tr><td>Name profile requests</td><td> "+Redis.getNameProfileRequestsCount()+"</td></tr>\n" +
                "            <tr><td>Total requests served</td><td> "+(Redis.getProfileRequestsCount()+Redis.getNameRequestsCount()+Redis.getNamesRequestsCount()+Redis.getNameProfileRequestsCount())+"</td></tr>\n" +
                "            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n"+
                "            <tr><td>Profiles in memory</td><td> "+Redis.dbsize(1)+"</td></tr>\n"+
                "            <tr><td>UUIDs in memory</td><td> "+Redis.dbsize(2)+"</td></tr>\n"+
                "            <tr><td>Name lists in memory</td><td> "+Redis.dbsize(3)+"</td></tr>\n"+
                "            <tr><td>Name profiles in memory</td><td> "+Redis.dbsize(4)+"</td></tr>\n" +
                "            <tr><td>Invalid requests in memory</td><td> "+Redis.dbsize(5)+"</td></tr>\n" +
                "            <tr><td>---Response codes breakdown---</td><td>-----------------------</td></tr>\n"+
                             responseCodeBreakdown.toString()+
                "        </table>\n" +
                "    </body>\n" +
                "</html>";
        });
        Spark.after("/*", ((request, response) -> response.header("Server", "MojangPipe/2.3")));
        Spark.exception(Exception.class, (e, req, res) -> e.printStackTrace());
        Spark.awaitInitialization();

        threadPool.scheduleAtFixedRate(()->{
            Ratelimit.getRequestsInProgress().forEach((key, value)->{
                if((System.currentTimeMillis() - value) > 1000){
                    Ratelimit.remove(key);
                }
            });
            newProxy();
        }, 5, 5, TimeUnit.MINUTES);
    }

    static RedisCommands<String, String> getRedis(){
        while(Redis.isConnectionBusy()){
            try {
                Thread.sleep(1);
            } catch(InterruptedException e){
                e.printStackTrace();
            }
        }
        return redis;
    }
    static void newProxy(){
        client = Utils.getClient();
    }
}