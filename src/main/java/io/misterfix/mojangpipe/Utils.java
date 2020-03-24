package io.misterfix.mojangpipe;

import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.Base64;
import java.util.Map;

class Utils {
	static String getOptimalInterface(Map<String, String> map) {
		String minKey = "0";
		long minValue = Long.MAX_VALUE;
		for (Map.Entry<String, String> entry : map.entrySet()) {
			long value = Long.parseLong(entry.getValue());
			if (value < minValue) {
				minValue = value;
				minKey = entry.getKey();
			}
		}
		return minKey;
	}
	static String readableFileSize(long size) {
		if (size <= 0) {
			return "0";
		}
		String[] units = {"B", "kB", "MB", "GB", "TB"};
		int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
		return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
	}
	static long now(){
		return System.currentTimeMillis();
	}
	static String getTextures(String json) {
		JSONObject responseJson = new JSONObject();
		JSONObject profile = new JSONObject(json);
		String data = profile.getJSONArray("properties").getJSONObject(0).getString("value");
		String base64 = new String(Base64.getDecoder().decode(data));
		JSONObject textures = new JSONObject(base64).getJSONObject("textures");
		responseJson.put("uuid", profile.getString("id"));
		responseJson.put("name", profile.getString("name"));
		if (!textures.isNull("SKIN")) {
			responseJson.put("skin", textures.getJSONObject("SKIN").getString("url"));
		}
		if (!textures.isNull("CAPE")) {
			responseJson.put("cape", textures.getJSONObject("CAPE").getString("url"));
		}
		if (!profile.isNull("legacy")) {
			responseJson.put("legacy", true);
		}
		return responseJson.toString();
	}
}
