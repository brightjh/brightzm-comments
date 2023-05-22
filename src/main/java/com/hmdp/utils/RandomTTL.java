package com.hmdp.utils;

import java.util.Random;

public class RandomTTL {

    public static Long getTTL(Long ttl){
        Random r = new Random();
        return ttl + r.nextInt(10);
    }

}
