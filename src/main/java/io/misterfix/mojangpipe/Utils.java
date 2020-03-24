package io.misterfix.mojangpipe;

import java.text.DecimalFormat;
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
}
