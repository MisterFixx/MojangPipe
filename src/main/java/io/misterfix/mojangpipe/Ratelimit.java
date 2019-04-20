package io.misterfix.mojangpipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class Ratelimit {
    private static List<String> requestsInProgress = Collections.synchronizedList(new ArrayList<>());

    static void checkAndAdd(String identifier) throws InterruptedException{
        if(requestsInProgress.contains(identifier)){
            while(requestsInProgress.contains(identifier)){
                Thread.sleep(8);
            }
        }
        requestsInProgress.add(identifier);
    }

    static void remove(String identifier){
        requestsInProgress.remove(identifier);
    }

    static void add(String identifier){
        requestsInProgress.add(identifier);
    }

    static List<String> getRequestsInProgress(){
        return requestsInProgress;
    }
}
