/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.datavines.server.utils;

import io.datavines.core.enums.Status;
import io.datavines.core.exception.DataVinesServerException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory login failure / lockout tracker (single-node).
 */
@Component
public class LoginAttemptGuard {

    private static final int CAPTCHA_THRESHOLD = 3;
    private static final int LOCK_THRESHOLD = 5;
    private static final long LOCK_MILLIS = 15 * 60 * 1000L;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void assertNotLocked(String username, String ip) {
        Attempt byUser = get(usernameKey(username));
        Attempt byIp = get(ipKey(ip));
        long now = System.currentTimeMillis();
        if (byUser.isLocked(now) || byIp.isLocked(now)) {
            throw new DataVinesServerException(Status.LOGIN_ACCOUNT_LOCKED);
        }
    }

    public boolean needCaptcha(String username, String ip) {
        long now = System.currentTimeMillis();
        Attempt byUser = get(usernameKey(username));
        Attempt byIp = get(ipKey(ip));
        return byUser.failCount >= CAPTCHA_THRESHOLD || byIp.failCount >= CAPTCHA_THRESHOLD
                || byUser.isLocked(now) || byIp.isLocked(now);
    }

    public void onSuccess(String username, String ip) {
        if (StringUtils.isNotBlank(username)) {
            attempts.remove(usernameKey(username));
        }
        if (StringUtils.isNotBlank(ip)) {
            attempts.remove(ipKey(ip));
        }
    }

    public void onFailure(String username, String ip) {
        bump(usernameKey(username));
        bump(ipKey(ip));
    }

    public Map<String, Object> status(String username, String ip) {
        Attempt byUser = get(usernameKey(username));
        Attempt byIp = get(ipKey(ip));
        long now = System.currentTimeMillis();
        boolean locked = byUser.isLocked(now) || byIp.isLocked(now);
        long unlockAt = Math.max(byUser.lockUntil, byIp.lockUntil);
        Map<String, Object> map = new HashMap<>();
        map.put("needCaptcha", needCaptcha(username, ip));
        map.put("locked", locked);
        map.put("remainSeconds", locked && unlockAt > now ? (unlockAt - now) / 1000 : 0L);
        return map;
    }

    private void bump(String key) {
        if (StringUtils.isBlank(key) || "user:".equals(key) || "ip:".equals(key)) {
            return;
        }
        Attempt attempt = attempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (attempt) {
            long now = System.currentTimeMillis();
            if (attempt.isLocked(now)) {
                return;
            }
            if (attempt.lockUntil > 0 && attempt.lockUntil <= now) {
                attempt.failCount = 0;
                attempt.lockUntil = 0;
            }
            attempt.failCount++;
            if (attempt.failCount >= LOCK_THRESHOLD) {
                attempt.lockUntil = now + LOCK_MILLIS;
            }
        }
    }

    private Attempt get(String key) {
        Attempt attempt = attempts.get(key);
        return attempt == null ? Attempt.EMPTY : attempt;
    }

    private static String usernameKey(String username) {
        return "user:" + StringUtils.trimToEmpty(username).toLowerCase();
    }

    private static String ipKey(String ip) {
        return "ip:" + StringUtils.trimToEmpty(ip);
    }

    private static final class Attempt {
        static final Attempt EMPTY = new Attempt();
        int failCount;
        long lockUntil;

        boolean isLocked(long now) {
            return lockUntil > now;
        }
    }
}
