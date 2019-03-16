package io.misterfix.mojangpipe;

import okhttp3.OkHttpClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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

    //I need this because of some weird issue on my VPS, it just spits exceptions and doesn't work without this
    private static OkHttpClient.Builder getUnsafeOkHttpClient(){
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

    static OkHttpClient getClient(Map<Integer, Integer> proxies){
        OkHttpClient.Builder clientBuilder = getUnsafeOkHttpClient();
        int proxy = getOptimalProxy(proxies, proxies.keySet());
        clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxy)));
        MojangPipe.getProxies().put(proxy, MojangPipe.getProxies().get(proxy) + 1);
        return clientBuilder.build();
    }
}
