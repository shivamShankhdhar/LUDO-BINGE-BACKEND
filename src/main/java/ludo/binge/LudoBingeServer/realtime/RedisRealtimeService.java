package ludo.binge.LudoBingeServer.realtime;

import ludo.binge.LudoBingeServer.shared.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * High-performance real-time state and messaging engine.
 * Powered by Redis with automatic fallback to local memory when Redis is absent.
 */
@Service
public class RedisRealtimeService {
    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeService.class);

    private static final String TOPIC_PREFIX_ROOM = "ludo:channel:room:";
    private static final String TOPIC_PREFIX_MATCH = "ludo:channel:match:";
    private static final String TOPIC_PREFIX_USER = "ludo:channel:user:";
    private static final String KEY_PREFIX_PRESENCE = "ludo:presence:";
    private static final String KEY_ONLINE_PLAYERS = "ludo:online:players";
    private static final String KEY_PREFIX_ROOM = "ludo:room:";
    private static final String KEY_PREFIX_MATCH = "ludo:match:";

    private final Store store;
    private final boolean redisEnabled;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;

    // Fast in-memory state fallbacks and local subscriber dispatchers
    private final Map<UUID, Map<String, Object>> memoryPresence = new ConcurrentHashMap<>();
    private final Map<String, String> memoryCache = new ConcurrentHashMap<>();
    private final Map<String, Set<Consumer<String>>> localTopicListeners = new ConcurrentHashMap<>();

    public RedisRealtimeService(
            Store store,
            @Value("${ludo.redis.enabled:false}") boolean redisEnabled,
            org.springframework.beans.factory.ObjectProvider<RedisConnectionFactory> connectionFactoryProvider
    ) {
        this.store = store;
        StringRedisTemplate template = null;
        RedisMessageListenerContainer container = null;
        boolean active = false;

        if (redisEnabled) {
            RedisConnectionFactory factory = connectionFactoryProvider.getIfAvailable();
            if (factory != null) {
                try {
                    template = new StringRedisTemplate(factory);
                    // Quick ping test
                    try(var connection=template.getConnectionFactory().getConnection()){connection.ping();}
                    container = new RedisMessageListenerContainer();
                    container.setConnectionFactory(factory);
                    container.afterPropertiesSet();
                    container.start();
                    active = true;
                    log.info("Redis real-time state & pub/sub connected successfully.");
                } catch (Exception e) {
                    if(container!=null)try{container.destroy();}catch(Exception cleanup){e.addSuppressed(cleanup);}
                    throw new IllegalStateException("Redis is enabled but unavailable; refusing isolated realtime operation",e);
                }
            }
        }

        if(redisEnabled && !active)throw new IllegalStateException("Redis is enabled but no connection factory is available");
        this.redisEnabled = active;
        this.redisTemplate = template;
        this.listenerContainer = container;
    }

    /**
     * Mark player online status with TTL heartbeat.
     */
    public void setPresence(UUID userId, String status, UUID roomId) {
        long now = System.currentTimeMillis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId.toString());
        data.put("status", status);
        data.put("roomId", roomId != null ? roomId.toString() : "");
        data.put("updatedAt", now);

        if (redisEnabled && redisTemplate != null) {
            try {
                String key = KEY_PREFIX_PRESENCE + userId;
                redisTemplate.opsForValue().set(key, store.encode(data), Duration.ofSeconds(45));
                redisTemplate.opsForSet().add(KEY_ONLINE_PLAYERS, userId.toString());
            } catch (Exception e) {
                log.debug("Redis setPresence fallback: {}", e.getMessage());
                memoryPresence.put(userId, data);
            }
        } else {
            memoryPresence.put(userId, data);
        }
    }

    /**
     * Remove player presence upon disconnect or session termination.
     */
    public void removePresence(UUID userId) {
        if (redisEnabled && redisTemplate != null) {
            try {
                redisTemplate.delete(KEY_PREFIX_PRESENCE + userId);
                redisTemplate.opsForSet().remove(KEY_ONLINE_PLAYERS, userId.toString());
            } catch (Exception e) {
                log.debug("Redis removePresence fallback: {}", e.getMessage());
                memoryPresence.remove(userId);
            }
        } else {
            memoryPresence.remove(userId);
        }
    }

    /**
     * Returns true if player is currently online.
     */
    public boolean isOnline(UUID userId) {
        if (redisEnabled && redisTemplate != null) {
            try {
                return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX_PRESENCE + userId));
            } catch (Exception ignored) {}
        }
        return memoryPresence.containsKey(userId);
    }

    /**
     * Cache instantaneous room state for sub-millisecond retrieval.
     */
    public void cacheRoomState(UUID roomId, Object roomState) {
        String json = store.encode(roomState);
        if (redisEnabled && redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(KEY_PREFIX_ROOM + roomId, json, Duration.ofHours(2));
            } catch (Exception ignored) {}
        }
        memoryCache.put(KEY_PREFIX_ROOM + roomId, json);
    }

    /**
     * Cache instantaneous match snapshot for reconnects and roll/move responses.
     */
    public void cacheMatchSnapshot(UUID matchId, Object snapshot) {
        String json = store.encode(snapshot);
        if (redisEnabled && redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(KEY_PREFIX_MATCH + matchId, json, Duration.ofHours(2));
            } catch (Exception ignored) {}
        }
        memoryCache.put(KEY_PREFIX_MATCH + matchId, json);
    }

    /**
     * Publish an event to all subscribers of a room channel.
     */
    public void publishRoomEvent(String roomId, Object payload) {
        publish(TOPIC_PREFIX_ROOM + roomId, store.encode(payload));
    }

    /**
     * Publish an event to all subscribers of a match channel.
     */
    public void publishMatchEvent(String matchId, Object payload) {
        publish(TOPIC_PREFIX_MATCH + matchId, store.encode(payload));
    }

    /**
     * Publish an event directly to a specific user (e.g. WebRTC signaling).
     */
    public void publishUserEvent(UUID userId, Object payload) {
        publish(TOPIC_PREFIX_USER + userId, store.encode(payload));
    }

    public void publish(String topic, String message) {
        if (redisEnabled && redisTemplate != null) {
            try {
                redisTemplate.convertAndSend(topic, message);
            } catch (Exception e) {
                log.debug("Redis publish failed, dispatching locally: {}", e.getMessage());
                dispatchLocal(topic, message);
            }
        } else {
            dispatchLocal(topic, message);
        }
    }

    /**
     * Subscribe a local message consumer to a topic.
     */
    public void subscribe(String topic, Consumer<String> listener) {
        localTopicListeners.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(listener);

        if (redisEnabled && listenerContainer != null) {
            try {
                MessageListenerAdapter adapter = new MessageListenerAdapter((org.springframework.data.redis.connection.MessageListener) (message, pattern) -> {
                    String body = new String(message.getBody());
                    listener.accept(body);
                });
                listenerContainer.addMessageListener(adapter, new ChannelTopic(topic));
            } catch (Exception e) {
                log.debug("Redis subscribe fallback: {}", e.getMessage());
            }
        }
    }

    private void dispatchLocal(String topic, String message) {
        Set<Consumer<String>> listeners = localTopicListeners.get(topic);
        if (listeners != null) {
            for (Consumer<String> l : listeners) {
                try {
                    l.accept(message);
                } catch (Exception ignored) {}
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void close() throws Exception {if(listenerContainer!=null)listenerContainer.destroy();}

    public boolean isRedisActive() {
        return redisEnabled;
    }
}
