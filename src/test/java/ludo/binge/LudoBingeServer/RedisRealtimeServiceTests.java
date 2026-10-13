package ludo.binge.LudoBingeServer;

import ludo.binge.LudoBingeServer.realtime.RedisRealtimeService;
import ludo.binge.LudoBingeServer.shared.Store;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisRealtimeServiceTests {

    @Test
    void managesPresenceAndPubSubGracefully() {
        Store store = new Store(null, tools.jackson.databind.json.JsonMapper.builder().build(), null);
        @SuppressWarnings("unchecked")
        ObjectProvider connectionFactoryProvider = mock(ObjectProvider.class);
        when(connectionFactoryProvider.getIfAvailable()).thenReturn(null);

        RedisRealtimeService service = new RedisRealtimeService(store, false, connectionFactoryProvider);
        assertFalse(service.isRedisActive());

        UUID user1 = UUID.randomUUID();
        UUID room1 = UUID.randomUUID();
        UUID match1 = UUID.randomUUID();

        // 1. Presence tracking
        assertFalse(service.isOnline(user1));
        service.setPresence(user1, "IN_ROOM", room1);
        assertTrue(service.isOnline(user1));

        service.removePresence(user1);
        assertFalse(service.isOnline(user1));

        // 2. Room & Match state caching
        service.cacheRoomState(room1, Map.of("status", "ACTIVE", "capacity", 4));
        service.cacheMatchSnapshot(match1, Map.of("phase", "WAITING_ROLL", "activeSeat", 0));

        // 3. Pub/Sub listener delivery
        AtomicReference<String> receivedPayload = new AtomicReference<>();
        service.subscribe("ludo:channel:room:" + room1, receivedPayload::set);

        service.publishRoomEvent(room1.toString(), Map.of("type", "TEST_EVENT", "value", 42));
        assertNotNull(receivedPayload.get());
        assertTrue(receivedPayload.get().contains("TEST_EVENT"));
    }
}
