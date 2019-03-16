package io.misterfix.mojangpipe;

import okhttp3.OkHttpClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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

    static OkHttpClient.Builder getUnsafeOkHttpClient(){
        try{
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager(){
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType){}
                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType){ }
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager)trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
            return builder;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String getOptimalProxy(Map<String, Integer> map, Set<String> keys) {
        String minKey = null;
        int minValue = Integer.MAX_VALUE;
        for(String key : keys) {
            int value = map.get(key);
            if(value < minValue) {
                minValue = value;
                minKey = key;
            }
        }
        return minKey;
    }
}
