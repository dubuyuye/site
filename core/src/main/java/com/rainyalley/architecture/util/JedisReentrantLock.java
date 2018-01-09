package com.rainyalley.architecture.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisCommands;

import java.util.Random;


/**
 * @author bin.zhang
 */
public class JedisReentrantLock implements Lock {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    private String lockKey;

    private JedisCommands jedis;

    private Random random = new Random();

    private ObjectMapper om = new ObjectMapper();

    public JedisReentrantLock(String lockKey, JedisCommands jedis) {
        this.lockKey = lockKey;
        this.jedis = jedis;
    }


    private String currentAsker(){
        return String.valueOf(Thread.currentThread().getId());
    }

    private boolean hasLock(LockVal lv){
        if (currentAsker().equals(lv.lockOwner)) {
            return true;
        }

        return false;
    }

    private boolean resolve(String result){
        if("OK".equals(result)){
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean hasLock() {
        try {
            String lockValTxt = jedis.get(lockKey);
            if(StringUtils.isBlank(lockValTxt)){
                return false;
            }
            LockVal lockVal = om.readValue(lockValTxt, LockVal.class);

            return hasLock(lockVal);
        } catch (Exception e) {
            logger.error("hasLock",e);
            return false;
        }
    }

    @Override
    public boolean tryLock(long lockMs) {

        try {
            String lockValTxt = jedis.get(lockKey);
            if(StringUtils.isBlank(lockValTxt)){
                LockVal lv = new LockVal(System.currentTimeMillis(), lockMs, currentAsker(), 1);
                String lvTxt = om.writeValueAsString(lv);
                String result = jedis.set(lockKey, lvTxt, "nx", "px", lockMs);
                logger.debug("tryLock acquire   >>> {} -> {} -> {}", lockKey, result, lvTxt);
                return resolve(result);
            }

            LockVal lockVal = om.readValue(lockValTxt, LockVal.class);
            //拥有锁
            if(hasLock(lockVal)){
                lockVal.setAcquireMs(System.currentTimeMillis());
                lockVal.setCount(lockVal.getCount() + 1);
                String lvTxt = om.writeValueAsString(lockVal);
                String result = jedis.set(lockKey, lvTxt, "xx", "px", lockMs);
                logger.debug("tryLock reentrant >>> {} -> {} -> {} -> {}", lockKey, result, lockValTxt, lvTxt);
                return resolve(result);
            } else {
                //当前线程不拥有锁
                //且锁已被其他线程占有
                //无法获得锁
                //必须等待锁被释放后才能获得锁
                //释放锁的两种方式，1：拥有者主动释放，2：锁超时被自动清除
                return false;
            }

        } catch (Exception e) {
            logger.error("tryLock", e);
            return false;
        }
    }

    @Override
    public boolean lock(long lockMs, long waitMS) {
        long lockBeginMs = System.currentTimeMillis();
        for (;;) {
            boolean trySuccess = tryLock(lockMs);
            if(trySuccess){
                return true;
            }

            if(System.currentTimeMillis() - lockBeginMs >= waitMS){
                return false;
            }

            //20毫秒~50毫秒之间随机睡眠，错开并发
            try {
                Thread.sleep(random.nextInt(30) + 20);
            } catch (InterruptedException e) {
                //
            }
        }
    }

    @Override
    public boolean unLock() {
        try {
            String lockValTxt = jedis.get(lockKey);
            if(StringUtils.isBlank(lockValTxt)){
                return true;
            }

            LockVal lockVal = om.readValue(lockValTxt, LockVal.class);
            //拥有锁
            if(hasLock(lockVal)){
                if(lockVal.getCount() > 1){
                    lockVal.setAcquireMs(System.currentTimeMillis());
                    lockVal.setCount(lockVal.getCount() - 1);
                    String lvTxt = om.writeValueAsString(lockVal);
                    String result = jedis.set(lockKey, lvTxt, "xx", "ex", lockVal.expireMs);
                    logger.debug("unLock  reentrant >>> {} -> {} -> {} -> {}", lockKey, result, lockValTxt, lvTxt);
                    return resolve(result);
                } else {
                    Long num = jedis.del(lockKey);
                    logger.debug("unLock  delete    >>> {} -> {}", lockKey, num, lockValTxt);
                    return true;
                }
            } else {
                return true;
            }
        } catch (Exception e) {
            logger.error("unLock",e);
            return false;
        }
    }



    private static class LockVal {
        private long acquireMs;
        private long expireMs;
        private String lockOwner;
        private int count;

        public LockVal() {
        }

        public LockVal(long acquireMs, long expireMs, String lockOwner, int count) {
            this.acquireMs = acquireMs;
            this.expireMs = expireMs;
            this.lockOwner = lockOwner;
            this.count = count;
        }

        public long getAcquireMs() {
            return acquireMs;
        }

        public void setAcquireMs(long acquireMs) {
            this.acquireMs = acquireMs;
        }

        public long getExpireMs() {
            return expireMs;
        }

        public void setExpireMs(long expireMs) {
            this.expireMs = expireMs;
        }

        public String getLockOwner() {
            return lockOwner;
        }

        public void setLockOwner(String lockOwner) {
            this.lockOwner = lockOwner;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("LockVal{");
            sb.append("acquireMs=").append(acquireMs);
            sb.append(", expireMs=").append(expireMs);
            sb.append(", lockOwner='").append(lockOwner).append('\'');
            sb.append(", count=").append(count);
            sb.append('}');
            return sb.toString();
        }
    }
}
