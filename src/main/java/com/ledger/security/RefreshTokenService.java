package com.ledger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.config.LedgerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh-token store. Uses Redis when available, otherwise an in-memory map
 * (test/fallback profile). Rotation + reuse detection behave the same in both.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final String TOKEN_KEY_PREFIX = "rt:";
    private static final String FAMILY_KEY_PREFIX = "rtf:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final boolean useRedis;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, TokenEntry> memory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> memoryFamilies = new ConcurrentHashMap<>();

    private record TokenEntry(Long userId, long expiresAtEpochSeconds, String familyId) {
    }

    public RefreshTokenService(RedisTemplate<String, Object> redisTemplate,
                               ObjectMapper objectMapper,
                               LedgerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = properties.security().refreshTokenTtl();
        this.useRedis = "redis".equalsIgnoreCase(properties.security().refreshTokenStore());
        log.info("Refresh-token store: {}", useRedis ? "redis" : "in-memory");
    }

    public String issue(Long userId) {
        return issueInFamily(randomId(16), userId);
    }

    private String issueInFamily(String familyId, Long userId) {
        String tokenId = randomId(24);
        long expiresAt = System.currentTimeMillis() / 1000 + ttl.toSeconds();
        TokenEntry entry = new TokenEntry(userId, expiresAt, familyId);
        if (useRedis) {
            redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + tokenId, toJson(entry), ttl);
            redisTemplate.opsForSet().add(FAMILY_KEY_PREFIX + familyId, tokenId);
            redisTemplate.expire(FAMILY_KEY_PREFIX + familyId, ttl);
        } else {
            memory.put(tokenId, entry);
            memoryFamilies.computeIfAbsent(familyId, k -> ConcurrentHashMap.newKeySet()).add(tokenId);
        }
        return familyId + "." + tokenId;
    }

    /** Rotates the token: validates, revokes the old one and issues a new one in the same family. */
    public String rotate(String token) {
        RefreshTokenRecord current = validate(token);
        revokeOne(current.familyId(), current.tokenId());
        return issueInFamily(current.familyId(), current.userId());
    }

    public void revoke(String token) {
        RefreshTokenRecord record = validate(token);
        revokeOne(record.familyId(), record.tokenId());
    }

    /** Validates a token. On missing/revoked token the whole family is revoked (reuse detection). */
    public RefreshTokenRecord validate(String token) {
        String[] parts;
        try {
            parts = RefreshTokenRecord.split(token);
        } catch (IllegalArgumentException e) {
            throw new RefreshTokenReuseException(null);
        }
        String familyId = parts[0];
        String tokenId = parts[1];

        if (useRedis) {
            String raw = (String) redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + tokenId);
            if (raw == null) {
                Long familySize = redisTemplate.opsForSet().size(FAMILY_KEY_PREFIX + familyId);
                if (familySize != null && familySize > 0) {
                    revokeFamily(familyId);
                    log.warn("Refresh-token reuse detected for family {}; family revoked", familyId);
                }
                throw new RefreshTokenReuseException(familyId);
            }
            TokenEntry entry = fromJson(raw);
            return new RefreshTokenRecord(familyId, tokenId, entry.userId(), entry.expiresAtEpochSeconds());
        }

        TokenEntry entry = memory.get(tokenId);
        if (entry == null) {
            if (memoryFamilies.containsKey(familyId)) {
                revokeFamily(familyId);
                log.warn("Refresh-token reuse detected for family {}; family revoked", familyId);
            }
            throw new RefreshTokenReuseException(familyId);
        }
        return new RefreshTokenRecord(familyId, tokenId, entry.userId(), entry.expiresAtEpochSeconds());
    }

    private void revokeOne(String familyId, String tokenId) {
        if (useRedis) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + tokenId);
            redisTemplate.opsForSet().remove(FAMILY_KEY_PREFIX + familyId, tokenId);
        } else {
            memory.remove(tokenId);
            Set<String> family = memoryFamilies.get(familyId);
            if (family != null) {
                family.remove(tokenId);
            }
        }
    }

    private void revokeFamily(String familyId) {
        if (useRedis) {
            Set<Object> members = redisTemplate.opsForSet().members(FAMILY_KEY_PREFIX + familyId);
            if (members != null) {
                for (Object m : members) {
                    redisTemplate.delete(TOKEN_KEY_PREFIX + m);
                }
            }
            redisTemplate.delete(FAMILY_KEY_PREFIX + familyId);
        } else {
            Set<String> family = memoryFamilies.remove(familyId);
            if (family != null) {
                family.forEach(memory::remove);
            }
        }
    }

    private String toJson(TokenEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize refresh token payload", e);
        }
    }

    private TokenEntry fromJson(String raw) {
        try {
            return objectMapper.readValue(raw, TokenEntry.class);
        } catch (Exception e) {
            throw new RefreshTokenReuseException(null);
        }
    }

    private String randomId(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
