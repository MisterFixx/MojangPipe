package io.misterfix.mojangpipe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class Ratelimit {
    private static Map<String, Long> requestsInProgress = new ConcurrentHashMap<>();

    static void checkAndAdd(String identifier) throws InterruptedException{
        if(requestsInProgress.containsKey(identifier)){
            while(requestsInProgress.containsKey(identifier)){
                Thread.sleep(5);
            }
        }
        requestsInProgress.put(identifier, System.currentTimeMillis());
    }

    static void remove(String identifier){
        requestsInProgress.remove(identifier);
    }

    static void add(String identifier){
        requestsInProgress.put(identifier, System.currentTimeMillis());
    }

    static Map<String, Long> getRequestsInProgress(){
        return requestsInProgress;
    }
}
