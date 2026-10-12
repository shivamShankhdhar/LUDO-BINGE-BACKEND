package ludo.binge.LudoBingeServer.room;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import ludo.binge.LudoBingeServer.shared.*;
import ludo.binge.LudoBingeServer.game.domain.MatchConfiguration;
import static ludo.binge.LudoBingeServer.shared.Store.*;
import static ludo.binge.LudoBingeServer.shared.ApiError.require;
@Service
public class RoomService {
 public final Store db;private final String inviteBase;
 public RoomService(Store db,@Value("${ludo.invite.base-url:}")String inviteBase){this.db=db;this.inviteBase=inviteBase;}
 public Map<String,Object> lock(UUID room){return db.one("SELECT * FROM rooms WHERE id=? FOR UPDATE",room);}
 public void member(UUID user,UUID room){require(!db.rows("SELECT 1 FROM room_members WHERE room_id=? AND user_id=?",room,user).isEmpty(),403,"NOT_A_MEMBER");}
 public void host(UUID user,Map<String,Object> room){require(room.get("host_id").equals(user),403,"HOST_REQUIRED");}
 public void lobby(Map<String,Object> room){require(room.get("phase").equals("LOBBY"),409,"LOBBY_CLOSED");}
 public void revision(Map<String,Object> room,long expected){require(((Number)room.get("revision")).longValue()==expected,409,"STALE_ROOM_REVISION");}
 public boolean blocked(UUID a,UUID b){return !db.rows("SELECT 1 FROM blocks WHERE (user_id=? AND blocked_id=?) OR (user_id=? AND blocked_id=?)",a,b,b,a).isEmpty();}
 public Object snapshot(UUID user,UUID room){member(user,room);var r=new LinkedHashMap<>(db.one("SELECT id,host_id,capacity,board,ruleset,phase,revision,countdown_at FROM rooms WHERE id=?",room));
  r.put("members",db.rows("SELECT u.id AS \"userId\",u.display_name AS \"displayName\",u.avatar_url AS \"avatarUrl\",u.avatar_version AS \"avatarVersion\",m.seat,m.ready,(u.connected_until>now()) AS connected FROM room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY m.seat",room));
  if(r.get("host_id").equals(user))r.put("joinRequests",db.rows("SELECT j.id,j.user_id,u.display_name,u.avatar_url,j.expires_at FROM join_requests j JOIN users u ON u.id=j.user_id WHERE room_id=? AND j.status='PENDING' AND j.expires_at>now()",room));return r;
 }
 public void changed(UUID room){db.update("UPDATE rooms SET revision=revision+1,updated_at=now() WHERE id=?",room);db.event(room,null,"ROOM_UPDATED",Map.of("roomId",room));}
 public Object create(UUID user,int count){var c=MatchConfiguration.forPlayers(count);UUID room=id();db.update("INSERT INTO rooms(id,host_id,capacity,board,ruleset) VALUES (?,?,?,?,?)",room,user,count,c.board().name(),c.rulesetVersion());seat(room,user,0);changed(room);return Map.of("room",snapshot(user,room),"invite",invite(user,room));}
 public void seat(UUID room,UUID user,int seat){db.update("INSERT INTO user_allocations(user_id,kind,resource_id) VALUES (?,'ROOM',?)",user,room);db.update("INSERT INTO room_members(room_id,user_id,seat) VALUES (?,?,?)",room,user,seat);}
 public Object invite(UUID user,UUID room){var r=lock(room);host(user,r);lobby(r);String token=token(),code=token().replaceAll("[^A-Za-z0-9]","").substring(0,8).toUpperCase(Locale.ROOT);
  db.update("INSERT INTO room_invites(room_id,token_hash,code,expires_at) VALUES (?,?,?,now()+interval '24 hours') ON CONFLICT(room_id) DO UPDATE SET token_hash=excluded.token_hash,code=excluded.code,expires_at=excluded.expires_at",room,hash(token),code);
  var result=new LinkedHashMap<String,Object>();result.put("code",code);result.put("token",token);result.put("shareUrl",inviteBase.isBlank()?null:inviteBase.replaceAll("/$","")+"/r/"+token);return result;
 }
 public Map<String,Object> resolve(String token){return db.one("SELECT r.id AS room_id,r.capacity,r.board FROM room_invites i JOIN rooms r ON r.id=i.room_id WHERE (i.token_hash=? OR i.code=?) AND i.expires_at>now() AND r.phase='LOBBY'",hash(token),token.toUpperCase(Locale.ROOT));}
 public Object join(UUID user,UUID room,String token){require(resolve(token).get("room_id").equals(room),403,"INVALID_INVITE");var r=lock(room);lobby(r);require(!blocked(user,uuid(r.get("host_id"))),403,"BLOCKED");
  require(db.rows("SELECT 1 FROM user_allocations WHERE user_id=?",user).isEmpty(),409,"ALREADY_ALLOCATED");
  db.update("UPDATE join_requests SET status='EXPIRED' WHERE room_id=? AND user_id=? AND status='PENDING' AND expires_at<=now()",room,user);
  var old=db.rows("SELECT id FROM join_requests WHERE room_id=? AND user_id=? AND status='PENDING'",room,user);if(!old.isEmpty())return old.getFirst();
  require(db.jdbc.queryForObject("SELECT count(*) FROM join_requests WHERE room_id=? AND status='PENDING'",Integer.class,room)<30,429,"TOO_MANY_REQUESTS");
  UUID request=id();db.update("INSERT INTO join_requests(id,room_id,user_id,expires_at) VALUES (?,?,?,now()+interval '60 seconds')",request,room,user);db.event(null,uuid(r.get("host_id")),"JOIN_REQUESTED",Map.of("roomId",room,"requestId",request));return Map.of("requestId",request);
 }
 public Object decide(UUID host,UUID room,UUID request,boolean accept,long revision){var r=lock(room);host(host,r);lobby(r);revision(r,revision);
  var req=db.one("SELECT * FROM join_requests WHERE id=? AND room_id=? AND status='PENDING' AND expires_at>now() FOR UPDATE",request,room);UUID user=uuid(req.get("user_id"));
  if(accept){var members=db.rows("SELECT user_id,seat FROM room_members WHERE room_id=? ORDER BY seat",room);require(members.size()<(int)r.get("capacity"),409,"ROOM_FULL");for(var m:members)require(!blocked(user,uuid(m.get("user_id"))),403,"BLOCKED");int seat=0;var occupied=members.stream().map(m->(int)m.get("seat")).toList();while(occupied.contains(seat))seat++;seat(room,user,seat);}
  db.update("UPDATE join_requests SET status=? WHERE id=?",accept?"ACCEPTED":"DECLINED",request);changed(room);db.event(null,user,"JOIN_DECIDED",Map.of("roomId",room,"accepted",accept));return snapshot(host,room);
 }
 public Object configure(UUID user,UUID room,int count,long revision){var r=lock(room);host(user,r);lobby(r);revision(r,revision);var config=MatchConfiguration.forPlayers(count);
  var members=db.rows("SELECT user_id,seat FROM room_members WHERE room_id=? ORDER BY seat",room);require(members.size()<=count,409,"REMOVE_MEMBERS_FIRST");
  // Delete/reinsert under the room lock to compact seats without violating unique indexes.
  db.update("DELETE FROM room_members WHERE room_id=?",room);for(int i=0;i<members.size();i++)db.update("INSERT INTO room_members(room_id,user_id,seat) VALUES (?,?,?)",room,members.get(i).get("user_id"),i);
  db.update("UPDATE rooms SET capacity=?,board=? WHERE id=?",count,config.board().name(),room);changed(room);return snapshot(user,room);
 }
 public Object ready(UUID user,UUID room,boolean ready,long revision){var r=lock(room);lobby(r);member(user,room);revision(r,revision);db.update("UPDATE room_members SET ready=? WHERE room_id=? AND user_id=?",ready,room,user);changed(room);return snapshot(user,room);}
 public Object start(UUID user,UUID room,long revision){var r=lock(room);host(user,r);lobby(r);revision(r,revision);
  int eligible=db.jdbc.queryForObject("SELECT count(*) FROM room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? AND m.ready AND u.connected_until>now()",Integer.class,room);
  require(eligible==(int)r.get("capacity"),409,"PLAYERS_NOT_READY");db.update("UPDATE rooms SET phase='COUNTDOWN',countdown_at=now()+interval '3 seconds' WHERE id=?",room);changed(room);return snapshot(user,room);
 }
 public Object leave(UUID user,UUID room,UUID target,long revision){var r=lock(room);member(user,room);lobby(r);revision(r,revision);if(!user.equals(target))host(user,r);member(target,room);
  db.update("DELETE FROM room_members WHERE room_id=? AND user_id=?",room,target);db.update("DELETE FROM user_allocations WHERE user_id=? AND resource_id=?",target,room);db.update("INSERT INTO media_revocations(room_id,user_id) VALUES (?,?)",room,target);
  var remaining=db.rows("SELECT user_id FROM room_members WHERE room_id=? ORDER BY joined_at,seat",room);
  if(remaining.isEmpty()){db.update("UPDATE rooms SET phase='CLOSED' WHERE id=?",room);db.update("DELETE FROM room_invites WHERE room_id=?",room);}
  else if(r.get("host_id").equals(target))db.update("UPDATE rooms SET host_id=? WHERE id=?",remaining.getFirst().get("user_id"),room);
  db.update("UPDATE room_members SET ready=false WHERE room_id=?",room);changed(room);return Map.of("left",target);
 }
 public Object active(UUID user){var rows=db.rows("SELECT kind,resource_id FROM user_allocations WHERE user_id=?",user);return rows.isEmpty()?Map.of("active",false):rows.getFirst();}
}
