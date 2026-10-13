package ludo.binge.LudoBingeServer.online;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import ludo.binge.LudoBingeServer.shared.Store;
import ludo.binge.LudoBingeServer.room.RoomService;
import ludo.binge.LudoBingeServer.game.domain.MatchConfiguration;
import static ludo.binge.LudoBingeServer.shared.Store.*;
import static ludo.binge.LudoBingeServer.shared.ApiError.require;

/** All allocation transitions share one DB advisory lock, including across server instances. */
@Service
public class MatchmakingService {
 private final Store db; private final RoomService rooms; private final boolean workers;
 public MatchmakingService(Store db,RoomService rooms,@Value("${ludo.workers.enabled:true}") boolean workers){this.db=db;this.rooms=rooms;this.workers=workers;}
 private void lock(){db.jdbc.queryForList("SELECT pg_advisory_xact_lock(89002,1)");}
 public Object enter(UUID user,int count){return db.tx(()->{
  lock();require(db.rows("SELECT 1 FROM user_allocations WHERE user_id=?",user).isEmpty(),409,"ALREADY_ALLOCATED");
  require(db.rows("SELECT 1 FROM join_requests WHERE user_id=? AND status='PENDING' AND expires_at>now()",user).isEmpty(),409,"JOIN_PENDING");
  MatchConfiguration.forPlayers(count);UUID ticket=id();
  db.update("INSERT INTO queue_tickets(id,user_id,capacity,region,ruleset,expires_at) VALUES (?,?,?,'global',?,now()+interval '90 seconds')",ticket,user,count,MatchConfiguration.RULESET_VERSION);
  db.update("INSERT INTO user_allocations VALUES (?,'QUEUE',?)",user,ticket);
  return ticket(user);
 });}
 public Map<String,Object> ticket(UUID user){
  var rows=db.rows("SELECT * FROM queue_tickets WHERE user_id=? ORDER BY created_at DESC LIMIT 1",user);
  if(rows.isEmpty())return Map.of();var t=new LinkedHashMap<>(rows.getFirst());
  if(t.get("proposal_id")!=null){
   t.put("proposal",db.one("SELECT * FROM proposals WHERE id=?",t.get("proposal_id")));
   t.put("players",db.rows("SELECT u.id AS \"userId\",u.display_name AS \"displayName\",u.avatar_url AS \"avatarUrl\",q.status FROM queue_tickets q JOIN users u ON u.id=q.user_id WHERE q.proposal_id=? ORDER BY q.created_at,q.id",t.get("proposal_id")));
  }else t.put("players",db.rows("SELECT u.id AS \"userId\",u.display_name AS \"displayName\",u.avatar_url AS \"avatarUrl\" FROM queue_tickets q JOIN users u ON u.id=q.user_id WHERE q.status='SEARCHING' AND q.expires_at>now() AND u.connected_until>now() AND q.capacity=? AND q.user_id<>? AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.user_id=? AND b.blocked_id=q.user_id) OR (b.blocked_id=? AND b.user_id=q.user_id)) ORDER BY q.created_at LIMIT 8",t.get("capacity"),user,user,user));
  return t;
 }
 public Object cancel(UUID user){return db.tx(()->{lock();var t=ticket(user);if(t.isEmpty())return Map.of("cancelled",true);
  String status=t.get("status").toString();require(!status.equals("MATCHED"),409,"MATCH_ALREADY_STARTED");
  if(Set.of("RESERVED","ACCEPTED").contains(status))release(uuid(t.get("proposal_id")),user);
  db.update("UPDATE queue_tickets SET status='CANCELLED' WHERE id=? AND status<>'MATCHED'",t.get("id"));
  db.update("DELETE FROM user_allocations WHERE user_id=? AND kind='QUEUE'",user);return Map.of("cancelled",true);
 });}
 public Object accept(UUID user){return accept(user,null);}
 public Object accept(UUID user,UUID expectedProposal){return db.tx(()->{lock();var t=ticket(user);require(!t.isEmpty(),409,"NO_SEARCH");
  require(expectedProposal==null||expectedProposal.equals(t.get("proposal_id")),409,"RESERVATION_EXPIRED");
  // A retry after the final participant accepted must return the completed allocation.
  if ("MATCHED".equals(t.get("status"))) return t;
  require(Set.of("RESERVED","ACCEPTED").contains(t.get("status")),409,"RESERVATION_EXPIRED");
  UUID proposal=uuid(t.get("proposal_id"));require(!db.rows("SELECT 1 FROM proposals WHERE id=? AND status='PENDING' AND expires_at>now()",proposal).isEmpty(),409,"RESERVATION_EXPIRED");
  db.update("UPDATE queue_tickets SET status='ACCEPTED' WHERE id=?",t.get("id"));
  var group=db.rows("SELECT * FROM queue_tickets WHERE proposal_id=? ORDER BY created_at,id",proposal);
  if(group.stream().allMatch(q->q.get("status").equals("ACCEPTED"))){
   UUID host=uuid(group.getFirst().get("user_id"));
   for(var q:group)db.update("DELETE FROM user_allocations WHERE user_id=? AND kind='QUEUE'",q.get("user_id"));
   rooms.create(host,(int)t.get("capacity"));UUID room=uuid(db.one("SELECT resource_id FROM user_allocations WHERE user_id=?",host).get("resource_id"));
   for(int i=1;i<group.size();i++)rooms.seat(room,uuid(group.get(i).get("user_id")),i);
   db.update("UPDATE room_members SET ready=true WHERE room_id=?",room);
   db.update("UPDATE rooms SET public_room=true,phase='COUNTDOWN',countdown_at=now()+interval '3 seconds',revision=revision+1 WHERE id=?",room);
   db.update("UPDATE queue_tickets SET status='MATCHED',room_id=? WHERE proposal_id=?",room,proposal);
   db.update("UPDATE proposals SET status='MATCHED' WHERE id=?",proposal);
  }return ticket(user);
 });}
 private void release(UUID proposal,UUID declined){
  var group=db.rows("SELECT * FROM queue_tickets WHERE proposal_id=? AND status IN ('RESERVED','ACCEPTED')",proposal);
  for(var q:group){boolean retain=q.get("status").equals("ACCEPTED")&&!q.get("user_id").equals(declined);
   db.update("UPDATE queue_tickets SET status=?,proposal_id=NULL,expires_at=now()+interval '90 seconds' WHERE id=?",retain?"SEARCHING":"EXPIRED",q.get("id"));
   if(!retain)db.update("DELETE FROM user_allocations WHERE user_id=? AND kind='QUEUE'",q.get("user_id"));
  }db.update("UPDATE proposals SET status='EXPIRED' WHERE id=?",proposal);
 }
 @Scheduled(fixedDelay=500)
 public void tick(){if(!workers)return;db.tx(()->{lock();
  for(var p:db.rows("SELECT id FROM proposals WHERE status='PENDING' AND expires_at<=now()"))release(uuid(p.get("id")),null);
  for(var q:db.rows("SELECT q.id,q.user_id FROM queue_tickets q JOIN users u ON u.id=q.user_id WHERE q.status='SEARCHING' AND (q.expires_at<=now() OR u.connected_until<now())")){
   db.update("UPDATE queue_tickets SET status='EXPIRED' WHERE id=?",q.get("id"));db.update("DELETE FROM user_allocations WHERE user_id=? AND kind='QUEUE'",q.get("user_id"));
  }
  for(int count=2;count<=6;count++){
   var candidates=db.rows("SELECT q.* FROM queue_tickets q JOIN users u ON u.id=q.user_id WHERE q.status='SEARCHING' AND q.capacity=? AND q.expires_at>now() AND u.connected_until>now() ORDER BY q.created_at,q.id LIMIT 100",count);
   var group=new ArrayList<Map<String,Object>>();
   for(var candidate:candidates){UUID u=uuid(candidate.get("user_id"));if(group.stream().anyMatch(g->rooms.blocked(u,uuid(g.get("user_id")))))continue;group.add(candidate);if(group.size()==count)break;}
   if(group.size()==count){UUID proposal=id();db.update("INSERT INTO proposals(id,capacity,expires_at) VALUES (?,?,now()+interval '30 seconds')",proposal,count);
    for(var q:group)db.update("UPDATE queue_tickets SET status='RESERVED',proposal_id=? WHERE id=?",proposal,q.get("id"));
   }
  }return null;
 });}
}
