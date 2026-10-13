package ludo.binge.LudoBingeServer.realtime;

import java.util.UUID;

public interface RealtimeNotifier {
    void broadcastMatchUpdate(UUID matchId, Object snapshot);
    void broadcastRoomUpdate(UUID roomId, Object snapshot);
}
