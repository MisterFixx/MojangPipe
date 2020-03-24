package io.misterfix.mojangpipe;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import spark.Spark;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static spark.Spark.halt;

public class MojangPipe {
	private static long startTime;
	private static OkHttpClient client;
	private static final ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(20);
	private static ScheduledFuture<?> requestResetTask = null;
	private static LongAdder outgoingRequests = new LongAdder();
	private static LongAdder totalOutgoingRequests = new LongAdder();
	private static LongAdder requestsServed = new LongAdder();
	private static String currentInterface = "";
	private static final String NAME_URL = "https://api.mojang.com/profiles/minecraft";
	private static final String NAMES_URL = "https://api.mojang.com/user/profiles/";
	private static final String SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
	private static Request.Builder NAME_REQUEST = new Request.Builder().url(NAME_URL);
	private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("Application/json");

	private static int cacheLifetime = 0;
	private static int invalidLifetime = 0;
	private static int maxRequestsPerInterface = 0;

	public static void main(String[] args) {
		startTime = System.currentTimeMillis();
		Config conf = ConfigFactory.load();
		cacheLifetime = conf.getInt("profileLifetime")*60000;
		invalidLifetime = conf.getInt("invalidProfileLifetime")*60000;
		maxRequestsPerInterface = conf.getInt("maxRequestsPerInterface");
		int port = conf.getInt("port");
		String redisHost = conf.getString("redisHost");
		String redisPass = conf.getString("redisPass");
		int redisPort = conf.getInt("redisPort");
		
		for (int i = 0; i <= 5; i++) {
			RedisClient redisClient = RedisClient.create("redis://"+redisPass+"@"+redisHost+":"+redisPort+"/"+i);
			redisClient.setOptions(ClientOptions.builder().autoReconnect(true).build());
			StatefulRedisConnection<String, String> connection = redisClient.connect();
			new WrappedRedis(connection.sync()).close();
		}
		Redis.init(conf.getStringList("outgoingNetworkInterfaces"));

		client = Networking.getClient();

		Spark.port(port);
		Spark.exception(Exception.class, (e, request, response) -> {
			//This code gives me the heebie jeebies
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String sStackTrace = sw.toString(); // stack trace as a string
            System.out.println(sStackTrace);
            Pastie.paste(sStackTrace);
        });
		Spark.after((request, response)->{
			if(!request.matchedPath().equalsIgnoreCase("/stats")){
				requestsServed.increment();
			}
		});

		Spark.get("/profile/*", (request, response) -> {
			String[] route = request.splat()[0].split("/");
			if (route.length == 0) halt(204);
			String identifier = route[0];
			Ratelimit.checkAndAdd(identifier);
			long time = Utils.now();

			if (Redis.isInvalidIdentifier(identifier, time)) {
				Redis.incrStats("from_invalid_cache");
				halt(204);
			}

			int identifierType = identifier.length() == 32 ? 1 : 0;
			String json = "";
			int requestType = 0;
			if (route.length == 2) {
					if ("textures".equals(route[1])) {
						requestType = 1;
					} else if ("full".equals(route[1])) {
						requestType = 2;
					}
				}

			//Identifier is a UUID
			if (identifierType == 1) {
				CachedResponse cachedResponse = new CachedResponse(identifier, 1);
				if ((time - cachedResponse.getLastRequest()) < cacheLifetime) {
					Redis.incrStats("profile_from_mem");
					json = cachedResponse.getJson();
					cachedResponse.close();
				}
				else {
					Request apiRequest = new Request.Builder().url(SESSION_URL + identifier).build();
					Response apiResponse = getClient().newCall(apiRequest).execute();
					int responseCode = apiResponse.code();
					ResponseBody body = apiResponse.body();
					if (body != null) {
						json = body.string();
					}
					apiResponse.close();

					Redis.incrStats("profile_from_api", responseCode);
					if (responseCode == 200) {
						Redis.putJson(identifier, time, json, 1);
					}
					else {
						Redis.handleStatusCode(responseCode, identifier);
						response.status(responseCode);
					}
				}
			}
			//Identifier is a username
			else {
				CachedResponse profileCachedResponse = new CachedResponse(identifier, 4);
				if ((time - profileCachedResponse.getLastRequest()) < cacheLifetime) {
					Redis.incrStats("profile_from_mem");
					json = profileCachedResponse.getJson();
					profileCachedResponse.close();
				}
				else {
					String responseString = "";
					Request apiRequest = NAME_REQUEST.post(RequestBody.create(new JSONArray().put(identifier).toString(), JSON_MEDIA_TYPE)).build();
					Response apiResponse = getClient().newCall(apiRequest).execute();
					ResponseBody apiBody = apiResponse.body();
					int apiResponseCode = apiResponse.code();
					if (apiBody != null) {
						responseString = apiBody.string();
					}
					apiResponse.close();

					Redis.logStatusCode(apiResponseCode);
					if (apiResponseCode == 200) {
						if(responseString.equals("[]")){
							Redis.logInvalidRequest(identifier, time);
							halt(204);
						}
						String uuid = new JSONArray(responseString).getJSONObject(0).getString("id");
						Redis.putJson(identifier, time, responseString, 2);
						CachedResponse nameCachedResponse = new CachedResponse(uuid, 1);
						if ((time - nameCachedResponse.getLastRequest()) < cacheLifetime) {
							json = nameCachedResponse.getJson();
							Redis.putJson(identifier, time, json, 4);
							nameCachedResponse.close();
						}
						else {
							Request sessionRequest = new Request.Builder().url(SESSION_URL + uuid).build();
							Response sessionResponse = getClient().newCall(sessionRequest).execute();
							int responseCode = sessionResponse.code();
							ResponseBody sessionBody = sessionResponse.body();
							if (sessionBody != null) {
								json = sessionBody.string();
							}
							sessionResponse.close();

							Redis.incrStats("profile_from_api", sessionResponse.code());
							if (responseCode == 200) {
								Redis.putJson(identifier, time, json, 4);
								Redis.putJson(uuid, time, json, 1);
							}
							else {
								Redis.handleStatusCode(apiResponseCode, identifier);
								Redis.handleStatusCode(apiResponseCode, uuid);
								response.status(responseCode);
							}
						}
					}
					else {
						Redis.handleStatusCode(apiResponseCode, identifier);
					}
				}
			}
			//Add name history to profile if necessary
			if (requestType == 2) {
				if (!json.isEmpty() && json.startsWith("{")) {
					JSONObject profile = new JSONObject(json);
					String uuid = profile.getString("id");
					String names = "";
					CachedResponse cachedResponse = new CachedResponse(uuid, 3);
					if ((time - cachedResponse.getLastRequest()) < cacheLifetime) {
						Redis.incrStats("names_from_mem");
						names =  cachedResponse.getJson();
						cachedResponse.close();
					}
					else {
						Request apiRequest = new Request.Builder().url(NAMES_URL + uuid + "/names").build();
						Response apiResponse = getClient().newCall(apiRequest).execute();
						int responseCode = apiResponse.code();
						ResponseBody body = apiResponse.body();
						if (body != null) {
							names = body.string();
						}
						apiResponse.close();

						Redis.incrStats("names_from_api", responseCode);
						if (responseCode == 200) {
							Redis.putJson(uuid, time, names, 3);
						}
						else {
							Redis.handleStatusCode(responseCode, uuid);
						}
					}
					if(!names.isEmpty()){
						profile.put("name_history", new JSONArray(names));
					}
					else{
						profile.put("name_history", new JSONArray("[]"));
					}
					json = profile.toString();
				}
			}

			Ratelimit.remove(identifier);
			System.out.println("Served profile for identifier "+identifier+" ("+response.status()+")");
			if (requestType == 1 && !json.isEmpty()) {
				return Utils.getTextures(json);
			}
			else {
				return json;
			}
		});
		Spark.get("/name/:name", (request, response) -> {
			String name = request.params(":name");
			long time = Utils.now();
			String json = "";
			if (Redis.isInvalidIdentifier(name, time)) {
				Redis.incrStats("from_invalid_cache");
				Spark.halt(204);
			}
			CachedResponse cachedResponse = new CachedResponse(name, 2);
			if ((time - cachedResponse.getLastRequest()) < cacheLifetime) {
				Redis.incrStats("uuid_from_mem");
				json = cachedResponse.getJson();
				cachedResponse.close();
			}
			else {
				Request apiRequest = NAME_REQUEST.post(RequestBody.create(new JSONArray().put(name).toString(), JSON_MEDIA_TYPE)).build();
				Response apiResponse = getClient().newCall(apiRequest).execute();
				int responseCode = apiResponse.code();
				ResponseBody body = apiResponse.body();
				if (body != null) {
					json = body.string();
				}
				apiResponse.close();

				Redis.incrStats("uuid_from_api", responseCode);
				if (responseCode == 200) {
					if(json.equals("[]")){
						Redis.logInvalidRequest(name, time);
						halt(204);
					}
					json = new JSONArray(json).getJSONObject(0).toString();
					Redis.putJson(name, time, json, 2);
				}
				else {
					Redis.handleStatusCode(responseCode, name);
					response.status(responseCode);
				}
			}

			System.out.println("Served UUID for name "+name+" ("+response.status()+")");
			return json;
		});
		Spark.get("/names/:uuid", (request, response) -> {
			String uuid = request.params(":uuid");
			if (uuid.length() != 32) halt(204);

			String json = "";
			long time = Utils.now();
			if (Redis.isInvalidIdentifier(uuid, time)) {
				Redis.incrStats("from_invalid_cache");
				Spark.halt(204);
			}
			CachedResponse cachedResponse = new CachedResponse(uuid, 3);
			if ((time - cachedResponse.getLastRequest()) < cacheLifetime) {
				Redis.incrStats("names_from_mem");
				json = cachedResponse.getJson();
				cachedResponse.close();
			}
			else {
				Request apiRequest = new Request.Builder().url(NAMES_URL + uuid + "/names").build();
				Response apiResponse = getClient().newCall(apiRequest).execute();
				int responseCode = apiResponse.code();
				ResponseBody body = apiResponse.body();
				if (body != null) {
					json = body.string();
				}
				apiResponse.close();

				Redis.incrStats("names_from_api", responseCode);
				if (responseCode == 200) {
					Redis.putJson(uuid, time, json, 3);
				}
				else {
					Redis.handleStatusCode(responseCode, uuid);
					response.status(responseCode);
				}
			}
			System.out.println("Served names list for UUID "+uuid+" ("+response.status()+")");
			return json;
		});
		Spark.get("/stats", (request, response) -> {
			StringBuilder responseCodeBreakdown = new StringBuilder();
			try (WrappedRedis redis = WrappedRedis.get(0)) {
				redis.get().hgetall("statusCodes").forEach((code, count) -> responseCodeBreakdown.append("<tr><td>").append(code).append(":</td><td> ").append(count).append("</td></tr>\n"));
			}
			return "<html>\n" +
					"    <head>\n" +
					"        <title>Mojang pipe report</title>\n" +
					"    </head>\n" +
					"    <body>\n" +
					"        <table>\n" +
					"            <tr><td>Current Time</td><td> " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()) + "</td></tr>\n" +
					"            <tr><td>Time started</td><td> " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(startTime)) + "</td></tr>\n" +
					"            <tr><td>Rate limit percentage</td><td> " + Redis.getRatelimitPercentage() + "%</td></tr>\n" +
					"            <tr><td>Interface in rotation</td><td> "+ currentInterface +"</td></tr>\n" +
					"            <tr><td>Outgoing requests in rotation</td><td> "+outgoingRequests+"</td></tr>\n" +
					"            <tr><td>Requests in progress</td><td> " + Ratelimit.getRequestsInProgress() + "</td></tr>\n" +
					"            <tr><td>Connections in pool</td><td> " + Networking.getConnectionPool().connectionCount() + "</td></tr>\n" +
					"            <tr><td>Idle connections in pool</td><td> " + Networking.getConnectionPool().idleConnectionCount() + "</td></tr>\n" +
					"            <tr><td>Used memory</td><td> " + Utils.readableFileSize(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) + "</td></tr>\n" +
					"            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n" +
					"            <tr><td>Requests served from memory&nbsp;&nbsp;</td><td> " + Redis.getRequestsFromMemory() + "</td></tr>\n" +
					"            <tr><td>Requests served from API</td><td> " + Redis.getRequestsFromApi() + "</td></tr>\n" +
					"            <tr><td>Outgoing API requests</td><td> " + totalOutgoingRequests + "</td></tr>\n" +
					"            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n" +
					"            <tr><td>Profile requests</td><td> " + Redis.getProfileRequestsCount() + "</td></tr>\n" +
					"            <tr><td>Name->UUID requests</td><td> " + Redis.getNameRequestsCount() + "</td></tr>\n" +
					"            <tr><td>Name list requests</td><td> " + Redis.getNamesRequestsCount() + "</td></tr>\n" +
					"            <tr><td>Total requests served</td><td> " + requestsServed.sum() + "</td></tr>\n" +
					"            <tr><td>----------------------------------------</td><td>-----------------------</td></tr>\n" +
					"            <tr><td>Profiles in memory</td><td> " + Redis.dbsize(1) + "</td></tr>\n" +
					"            <tr><td>Name->UUIDs in memory</td><td> " + Redis.dbsize(2) + "</td></tr>\n" +
					"            <tr><td>Name lists in memory</td><td> " + Redis.dbsize(3) + "</td></tr>\n" +
					"            <tr><td>Name->profiles in memory</td><td> " + Redis.dbsize(4) + "</td></tr>\n" +
					"            <tr><td>Invalid requests in memory</td><td> " + Redis.dbsize(5) + "</td></tr>\n" +
					"            <tr><td>---Response codes breakdown---</td><td>-----------------------</td></tr>\n" +
					responseCodeBreakdown +
					"        </table>\n" +
					"    </body>\n" +
					"</html>";
		});

		threadPool.scheduleAtFixedRate(Ratelimit::removeExpired, 300, 100, TimeUnit.MILLISECONDS);
	}

	static void newInterface() {
		outgoingRequests.reset();
		client = Networking.getClient();

		if(requestResetTask != null){
			requestResetTask.cancel(true);
		}
		requestResetTask = threadPool.scheduleAtFixedRate(()-> outgoingRequests.reset(), 10, 10, TimeUnit.MINUTES);
	}
	static int getCacheLifetime() {
		return cacheLifetime;
	}
	static int getInvalidLifetime() {
		return invalidLifetime;
	}
	static ScheduledExecutorService getThreadPool() {
		return threadPool;
	}
	static long getTotalOutgoingRequests() {
		return totalOutgoingRequests.sum();
	}
	static void setCurrentInterface(String currentInterface) {
		MojangPipe.currentInterface = currentInterface;
	}
	static void incrOutgoingRequests() {
		outgoingRequests.increment();
		if (outgoingRequests.sum() > maxRequestsPerInterface){
			MojangPipe.newInterface();
			System.gc();
		}
		totalOutgoingRequests.increment();
	}
	private static OkHttpClient getClient() {
		return client;
	}
}