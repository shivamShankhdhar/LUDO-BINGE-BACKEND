package ludo.binge.LudoBingeServer.online;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import ludo.binge.LudoBingeServer.shared.Store;

@Component
public class OnlineCleanup {
 private final Store db;private final boolean enabled;
 public OnlineCleanup(Store db,@Value("${ludo.workers.enabled:true}")boolean enabled){this.db=db;this.enabled=enabled;}
 @Scheduled(fixedDelay=60000)
 public void clean(){if(!enabled)return;db.tx(()->{
  db.update("DELETE FROM voice_signals WHERE created_at<now()-interval '1 minute'");
  db.update("DELETE FROM voice_members WHERE expires_at<now()");
  db.update("DELETE FROM room_messages WHERE created_at<now()-interval '5 minutes'");
  db.update("UPDATE join_requests SET status='EXPIRED' WHERE status='PENDING' AND expires_at<now()");
  db.update("UPDATE refresh_sessions SET retry_response=NULL,retry_key=NULL,retry_until=NULL WHERE retry_until<now()");
  for(var room:db.rows("SELECT id FROM rooms WHERE phase='LOBBY' AND updated_at<now()-interval '24 hours' FOR UPDATE SKIP LOCKED")){
   db.update("UPDATE rooms SET phase='CLOSED',revision=revision+1 WHERE id=?",room.get("id"));
   db.update("DELETE FROM user_allocations WHERE resource_id=?",room.get("id"));db.update("DELETE FROM room_invites WHERE room_id=?",room.get("id"));db.update("DELETE FROM voice_members WHERE room_id=?",room.get("id"));
  }
  db.update("DELETE FROM api_receipts WHERE created_at<now()-interval '7 days'");
  db.update("DELETE FROM outbox WHERE created_at<now()-interval '7 days'");
  db.update("DELETE FROM refresh_sessions WHERE expires_at<now()-interval '1 day'");
  return null;
 });}
}
