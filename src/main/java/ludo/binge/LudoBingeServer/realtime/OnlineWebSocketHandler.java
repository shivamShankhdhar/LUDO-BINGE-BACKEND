package ludo.binge.LudoBingeServer.realtime;

import ludo.binge.LudoBingeServer.identity.IdentityService;
import ludo.binge.LudoBingeServer.identity.IdentityService.Actor;
import ludo.binge.LudoBingeServer.shared.Store;
import ludo.binge.LudoBingeServer.game.MatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ludo.binge.LudoBingeServer.shared.Store.uuid;
import static ludo.binge.LudoBingeServer.shared.Store.id;

/**
 * Authoritative WebSocket & Real-Time Event Gateway.
 * Bridges full-duplex socket connections with Redis state management,
 * WebRTC low-latency audio signaling, and real-time game broadcasts.
 */
@Component
public class OnlineWebSocketHandler extends TextWebSocketHandler implements RealtimeNotifier {
    private static final Logger log = LoggerFactory.getLogger(OnlineWebSocketHandler.class);

    private final Store db;
    private final IdentityService identity;
    private final MatchService matchService;
    private final RedisRealtimeService realtime;

    // Local sessions
    private final Map<UUID, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> roomSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> matchSubscriptions = new ConcurrentHashMap<>();
    private final Set<String> subscribedRedisTopics = ConcurrentHashMap.newKeySet();

    public OnlineWebSocketHandler(
            Store db,
            IdentityService identity,
            @Lazy MatchService matchService,
            RedisRealtimeService realtime
    ) {
        this.db = db;
        this.identity = identity;
        this.matchService = matchService;
        this.realtime = realtime;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Actor actor = (Actor) session.getAttributes().get("actor");
        if (actor == null) {
            try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (Exception ignored) {}
            return;
        }

        userSessions.computeIfAbsent(actor.userId(), k -> ConcurrentHashMap.newKeySet()).add(session);
        realtime.setPresence(actor.userId(), "ONLINE", null);

        // Subscribe to direct user events in Redis
        ensureRedisUserSubscription(actor.userId());

        // Renew presence in DB
        db.update("UPDATE users SET connected_until=now()+interval '2 minutes' WHERE id=?", actor.userId());

        send(session, Map.of(
            "type", "CONNECTED",
            "event", "connected",
            "userId", actor.userId().toString(),
            "serverTimeMs", System.currentTimeMillis()
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Actor actor = (Actor) session.getAttributes().get("actor");
        if (actor == null || !identity.valid(actor)) {
            try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (Exception ignored) {}
            return;
        }

        try {
            Map<?, ?> payload = db.decode(message.getPayload(), Map.class);
            // Support both "type" and Socket.IO-style "event" / "action"
            String type = Objects.toString(payload.get("type"), "");
            if (type.isBlank()) {
                type = Objects.toString(payload.get("event"), "").toUpperCase();
            }

            switch (type) {
                case "SUBSCRIBE_ROOM", "JOIN_ROOM" -> {
                    String roomId = Objects.toString(payload.get("roomId"), "");
                    if (!roomId.isBlank()) {
                        roomSubscriptions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
                        ensureRedisRoomSubscription(roomId);
                        realtime.setPresence(actor.userId(), "IN_ROOM", uuid(roomId));
                        send(session, Map.of("type", "ROOM_SUBSCRIBED", "event", "room_subscribed", "roomId", roomId));
                    }
                }
                case "SUBSCRIBE_MATCH", "JOIN_MATCH" -> {
                    String matchId = Objects.toString(payload.get("matchId"), "");
                    if (!matchId.isBlank()) {
                        matchSubscriptions.computeIfAbsent(matchId, k -> ConcurrentHashMap.newKeySet()).add(session);
                        ensureRedisMatchSubscription(matchId);
                        send(session, Map.of("type", "MATCH_SUBSCRIBED", "event", "match_subscribed", "matchId", matchId));
                    }
                }
                case "ROLL", "ROLL_DICE" -> {
                    UUID matchId = uuid(payload.get("matchId"));
                    long revision = ((Number) payload.get("revision")).longValue();
                    UUID key = payload.containsKey("key") ? uuid(payload.get("key")) : id();
                    Object updated = matchService.command(actor.userId(), matchId, key, revision, "ROLL", null);
                    broadcastMatchUpdate(matchId, updated);
                }
                case "MOVE", "MOVE_PAWN" -> {
                    UUID matchId = uuid(payload.get("matchId"));
                    long revision = ((Number) payload.get("revision")).longValue();
                    int pawn = ((Number) payload.get("pawn")).intValue();
                    UUID key = payload.containsKey("key") ? uuid(payload.get("key")) : id();
                    Object updated = matchService.command(actor.userId(), matchId, key, revision, "MOVE", pawn);
                    broadcastMatchUpdate(matchId, updated);
                }
                case "FORFEIT" -> {
                    UUID matchId = uuid(payload.get("matchId"));
                    long revision = ((Number) payload.get("revision")).longValue();
                    UUID key = payload.containsKey("key") ? uuid(payload.get("key")) : id();
                    Object updated = matchService.command(actor.userId(), matchId, key, revision, "FORFEIT", null);
                    broadcastMatchUpdate(matchId, updated);
                }
                case "REACTION", "SEND_REACTION" -> {
                    String roomId = Objects.toString(payload.get("roomId"), "");
                    String body = Objects.toString(payload.get("body"), "");
                    if (!roomId.isBlank() && !body.isBlank()) {
                        var userRow = db.one("SELECT display_name FROM users WHERE id=?", actor.userId());
                        String senderName = userRow != null ? Objects.toString(userRow.get("display_name"), "Player") : "Player";
                        Map<String, Object> reactionEvent = Map.of(
                            "type", "REACTION",
                            "event", "reaction",
                            "roomId", roomId,
                            "senderId", actor.userId().toString(),
                            "senderName", senderName,
                            "body", body,
                            "timestamp", System.currentTimeMillis()
                        );
                        // Broadcast via Redis Pub/Sub so all clustered nodes receive it
                        realtime.publishRoomEvent(roomId, reactionEvent);
                    }
                }
                case "VOICE_SIGNAL", "SIGNAL" -> {
                    String recipientStr = Objects.toString(payload.get("recipient"), "");
                    if (!recipientStr.isBlank()) {
                        UUID recipient = uuid(recipientStr);
                        Map<String, Object> signalMsg = Map.of(
                            "type", "VOICE_SIGNAL",
                            "event", "voice_signal",
                            "sender", actor.userId().toString(),
                            "signal", payload.get("signal")
                        );
                        // Publish through Redis Pub/Sub directly to recipient user channel
                        realtime.publishUserEvent(recipient, signalMsg);
                    }
                }
                case "PING", "HEARTBEAT" -> {
                    realtime.setPresence(actor.userId(), "ACTIVE", null);
                    send(session, Map.of(
                        "type", "PONG",
                        "event", "pong",
                        "serverTimeMs", System.currentTimeMillis()
                    ));
                    db.update("UPDATE users SET connected_until=now()+interval '2 minutes' WHERE id=?", actor.userId());
                }
                default -> log.debug("Unknown websocket message type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Error handling websocket message: {}", e.getMessage());
            send(session, Map.of("type", "ERROR", "event", "error", "message", e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Actor actor = (Actor) session.getAttributes().get("actor");
        if (actor != null) {
            Set<WebSocketSession> sessions = userSessions.get(actor.userId());
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    userSessions.remove(actor.userId());
                    realtime.removePresence(actor.userId());
                }
            }
        }
        roomSubscriptions.values().forEach(set -> set.remove(session));
        matchSubscriptions.values().forEach(set -> set.remove(session));
    }

    @Override
    public void broadcastMatchUpdate(UUID matchId, Object snapshot) {
        String key = matchId.toString();
        // Cache snapshot in Redis
        realtime.cacheMatchSnapshot(matchId, snapshot);

        Map<String, Object> msg = Map.of(
            "type", "MATCH_UPDATE",
            "event", "match_update",
            "matchId", key,
            "data", snapshot
        );
        // Publish via Redis Pub/Sub
        realtime.publishMatchEvent(key, msg);
    }

    @Override
    public void broadcastRoomUpdate(UUID roomId, Object snapshot) {
        String key = roomId.toString();
        realtime.cacheRoomState(roomId, snapshot);

        Map<String, Object> msg = Map.of(
            "type", "ROOM_UPDATE",
            "event", "room_update",
            "roomId", key,
            "data", snapshot
        );
        realtime.publishRoomEvent(key, msg);
    }

    private void ensureRedisRoomSubscription(String roomId) {
        String topic = "ludo:channel:room:" + roomId;
        if (subscribedRedisTopics.add(topic)) {
            realtime.subscribe(topic, jsonMessage -> {
                Set<WebSocketSession> subscribers = roomSubscriptions.get(roomId);
                if (subscribers != null) {
                    broadcastLocalText(subscribers, jsonMessage);
                }
            });
        }
    }

    private void ensureRedisMatchSubscription(String matchId) {
        String topic = "ludo:channel:match:" + matchId;
        if (subscribedRedisTopics.add(topic)) {
            realtime.subscribe(topic, jsonMessage -> {
                Set<WebSocketSession> subscribers = matchSubscriptions.get(matchId);
                if (subscribers != null) {
                    broadcastLocalText(subscribers, jsonMessage);
                }
            });
        }
    }

    private void ensureRedisUserSubscription(UUID userId) {
        String topic = "ludo:channel:user:" + userId;
        if (subscribedRedisTopics.add(topic)) {
            realtime.subscribe(topic, jsonMessage -> {
                Set<WebSocketSession> sessions = userSessions.get(userId);
                if (sessions != null) {
                    broadcastLocalText(sessions, jsonMessage);
                }
            });
        }
    }

    private void broadcastLocalText(Set<WebSocketSession> sessions, String rawJson) {
        TextMessage text = new TextMessage(rawJson);
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(text);
                } catch (Exception ignored) {}
            }
        }
    }

    private void send(WebSocketSession session, Object payload) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(db.encode(payload)));
            } catch (IOException e) {
                log.warn("Failed to send websocket message: {}", e.getMessage());
            }
        }
    }
}
