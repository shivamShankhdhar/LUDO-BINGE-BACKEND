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

@Component
public class OnlineWebSocketHandler extends TextWebSocketHandler implements RealtimeNotifier {
    private static final Logger log = LoggerFactory.getLogger(OnlineWebSocketHandler.class);

    private final Store db;
    private final IdentityService identity;
    private final MatchService matchService;

    // Track active sessions per user
    private final Map<UUID, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    // Track sessions subscribed to specific rooms or matches
    private final Map<String, Set<WebSocketSession>> roomSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> matchSubscriptions = new ConcurrentHashMap<>();

    public OnlineWebSocketHandler(Store db, IdentityService identity, @Lazy MatchService matchService) {
        this.db = db;
        this.identity = identity;
        this.matchService = matchService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Actor actor = (Actor) session.getAttributes().get("actor");
        if (actor == null) {
            try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (Exception ignored) {}
            return;
        }

        userSessions.computeIfAbsent(actor.userId(), k -> ConcurrentHashMap.newKeySet()).add(session);
        // Renew presence in DB
        db.update("UPDATE users SET connected_until=now()+interval '2 minutes' WHERE id=?", actor.userId());

        send(session, Map.of(
            "type", "CONNECTED",
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
            String type = Objects.toString(payload.get("type"), "");

            switch (type) {
                case "SUBSCRIBE_ROOM" -> {
                    String roomId = Objects.toString(payload.get("roomId"), "");
                    if (!roomId.isBlank()) {
                        roomSubscriptions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
                        send(session, Map.of("type", "ROOM_SUBSCRIBED", "roomId", roomId));
                    }
                }
                case "SUBSCRIBE_MATCH" -> {
                    String matchId = Objects.toString(payload.get("matchId"), "");
                    if (!matchId.isBlank()) {
                        matchSubscriptions.computeIfAbsent(matchId, k -> ConcurrentHashMap.newKeySet()).add(session);
                        send(session, Map.of("type", "MATCH_SUBSCRIBED", "matchId", matchId));
                    }
                }
                case "ROLL" -> {
                    UUID matchId = uuid(payload.get("matchId"));
                    long revision = ((Number) payload.get("revision")).longValue();
                    UUID key = payload.containsKey("key") ? uuid(payload.get("key")) : id();
                    Object updated = matchService.command(actor.userId(), matchId, key, revision, "ROLL", null);
                    broadcastMatchUpdate(matchId, updated);
                }
                case "MOVE" -> {
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
                case "REACTION" -> {
                    String roomId = Objects.toString(payload.get("roomId"), "");
                    String body = Objects.toString(payload.get("body"), "");
                    if (!roomId.isBlank() && !body.isBlank()) {
                        var userRow = db.one("SELECT display_name FROM users WHERE id=?", actor.userId());
                        String senderName = userRow != null ? Objects.toString(userRow.get("display_name"), "Player") : "Player";
                        Map<String, Object> reactionEvent = Map.of(
                            "type", "REACTION",
                            "roomId", roomId,
                            "senderId", actor.userId().toString(),
                            "senderName", senderName,
                            "body", body,
                            "timestamp", System.currentTimeMillis()
                        );
                        broadcastToRoom(roomId, reactionEvent);
                    }
                }
                case "VOICE_SIGNAL" -> {
                    // Instant WebRTC signaling directly to recipient session
                    String recipientStr = Objects.toString(payload.get("recipient"), "");
                    if (!recipientStr.isBlank()) {
                        UUID recipient = uuid(recipientStr);
                        Map<String, Object> signalMsg = Map.of(
                            "type", "VOICE_SIGNAL",
                            "sender", actor.userId().toString(),
                            "signal", payload.get("signal")
                        );
                        sendToUser(recipient, signalMsg);
                    }
                }
                case "PING" -> {
                    send(session, Map.of(
                        "type", "PONG",
                        "serverTimeMs", System.currentTimeMillis()
                    ));
                    db.update("UPDATE users SET connected_until=now()+interval '2 minutes' WHERE id=?", actor.userId());
                }
                default -> log.debug("Unknown websocket message type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Error handling websocket message: {}", e.getMessage());
            send(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Actor actor = (Actor) session.getAttributes().get("actor");
        if (actor != null) {
            Set<WebSocketSession> sessions = userSessions.get(actor.userId());
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) userSessions.remove(actor.userId());
            }
        }
        roomSubscriptions.values().forEach(set -> set.remove(session));
        matchSubscriptions.values().forEach(set -> set.remove(session));
    }

    @Override
    public void broadcastMatchUpdate(UUID matchId, Object snapshot) {
        String key = matchId.toString();
        Set<WebSocketSession> subscribers = matchSubscriptions.get(key);
        if (subscribers != null && !subscribers.isEmpty()) {
            Map<String, Object> msg = Map.of(
                "type", "MATCH_UPDATE",
                "matchId", key,
                "data", snapshot
            );
            String encoded = db.encode(msg);
            for (WebSocketSession s : subscribers) {
                if (s.isOpen()) {
                    try { s.sendMessage(new TextMessage(encoded)); } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override
    public void broadcastRoomUpdate(UUID roomId, Object snapshot) {
        String key = roomId.toString();
        Set<WebSocketSession> subscribers = roomSubscriptions.get(key);
        if (subscribers != null && !subscribers.isEmpty()) {
            Map<String, Object> msg = Map.of(
                "type", "ROOM_UPDATE",
                "roomId", key,
                "data", snapshot
            );
            String encoded = db.encode(msg);
            for (WebSocketSession s : subscribers) {
                if (s.isOpen()) {
                    try { s.sendMessage(new TextMessage(encoded)); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void broadcastToRoom(String roomId, Object payload) {
        Set<WebSocketSession> subscribers = roomSubscriptions.get(roomId);
        if (subscribers != null && !subscribers.isEmpty()) {
            String encoded = db.encode(payload);
            for (WebSocketSession s : subscribers) {
                if (s.isOpen()) {
                    try { s.sendMessage(new TextMessage(encoded)); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void sendToUser(UUID recipient, Object payload) {
        Set<WebSocketSession> sessions = userSessions.get(recipient);
        if (sessions != null && !sessions.isEmpty()) {
            String encoded = db.encode(payload);
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    try { s.sendMessage(new TextMessage(encoded)); } catch (Exception ignored) {}
                }
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
