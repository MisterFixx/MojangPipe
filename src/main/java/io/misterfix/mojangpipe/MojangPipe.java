package io.misterfix.mojangpipe;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import okhttp3.OkHttpClient;
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
	private static DataFetcher dataFetcher = null;

	private static int cacheLifetime = 0;
	private static int invalidLifetime = 0;
	private static int maxRequestsPerInterface = 0;
	private static int port = 0;
	
	public static void main(String[] args) {
		startTime = System.currentTimeMillis();
		Config conf = ConfigFactory.load();
		cacheLifetime = conf.getInt("profileLifetime")*60000;
		invalidLifetime = conf.getInt("invalidProfileLifetime")*60000;
		maxRequestsPerInterface = conf.getInt("maxRequestsPerInterface");
		port = conf.getInt("port");
		String redisHost = conf.getString("redisHost");
		String redisPass = conf.getString("redisPass");
		int redisPort = conf.getInt("redisPort");
		
		for (int i = 0; i <= 5; i++) {
			RedisClient redisClient = RedisClient.create("redis://"+redisPass+"@"+redisHost+":"+redisPort+"/"+i);
			redisClient.setOptions(ClientOptions.builder().autoReconnect(true).build());
			StatefulRedisConnection<String, String> connection = redisClient.connect();
			new WrappedRedis(connection.sync()).close();
		}
		//Redis.init(conf.getStringList("outgoingNetworkInterfaces"));

		client = Networking.getClient();
		dataFetcher = new DataFetcher(cacheLifetime);

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
			String[] route = request.splat();
			if (route.length == 0) halt(204);
			String identifier = route[0];
			Ratelimit.checkAndAdd(identifier);

			if (Redis.isInvalidIdentifier(identifier, Utils.now())) {
				Redis.incrStats("from_invalid_cache");
				halt(204);
			}

			int identifierType = identifier.length() == 32 ? 1 : 0;
			String json;
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
				json = dataFetcher.getProfileByUuid(identifier, response, false);
			}
			//Identifier is a username
			else {
				json = dataFetcher.getProfileByName(identifier, response, false);
			}
			//Add name history to profile if necessary
			if (requestType == 2) {
				//not quite sure what's going on here
				if (!json.isEmpty() && json.startsWith("{")) {
					JSONObject profile = new JSONObject(json);
					String uuid = profile.getString("id");
					String names = dataFetcher.getNameHistory(uuid, response,false,false);
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
				return dataFetcher.getTextures(json);
			}
			else {
				return json;
			}
		});
		Spark.get("/name/:name", (request, response) -> {
			String name = request.params(":name");
			String json = dataFetcher.getUuidByName(name, response);
			System.out.println("Served UUID for name "+name+" ("+response.status()+")");
			return json;
		});
		Spark.get("/names/:uuid", (request, response) -> {
			String uuid = request.params(":uuid");
			if (uuid.length() != 32) halt(204);
			String json = dataFetcher.getNameHistory(uuid, response, true, true);
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
	static OkHttpClient getClient() {
		return client;
	}
}