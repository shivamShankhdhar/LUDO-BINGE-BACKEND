package ludo.binge.LudoBingeServer.room;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import ludo.binge.LudoBingeServer.identity.IdentityService.Actor;
import ludo.binge.LudoBingeServer.shared.Store;
import static ludo.binge.LudoBingeServer.shared.ApiError.require;

@RestController
@RequestMapping("/api/v1")
public class RoomController {
 private final RoomService rooms;
 private final Store db;
 public RoomController(RoomService rooms,Store db){this.rooms=rooms;this.db=db;}
 private UUID user(Authentication auth){return ((Actor)auth.getPrincipal()).userId();}
 public record Create(@Min(2) @Max(6) int count){}
 public record Join(@NotBlank @Size(max=128) String code){}
 public record Ready(long revision,boolean ready){}
 public record Revision(long revision){}
 public record Decision(long revision,boolean accept){}
 @PostMapping("/presence") Object presence(Authentication auth){db.update("UPDATE users SET connected_until=now()+interval '20 seconds' WHERE id=?",user(auth));return Map.of("connected",true);}
 @GetMapping("/rooms/active") Object active(Authentication auth){return rooms.active(user(auth));}
 @GetMapping("/rooms/{room}") Object get(Authentication auth,@PathVariable UUID room){return db.tx(()->rooms.snapshot(user(auth),room));}
 @PostMapping("/rooms") Object create(Authentication auth,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Create body){return db.mutate(user(auth),key,"create",body,()->rooms.create(user(auth),body.count()));}
 @PostMapping("/rooms/join") Object join(Authentication auth,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Join body){return db.mutate(user(auth),key,"join",body,()->{UUID room=Store.uuid(rooms.resolve(body.code()).get("room_id"));return Map.of("roomId",room,"request",rooms.join(user(auth),room,body.code()));});}
 @GetMapping("/rooms/{room}/request") Object request(Authentication auth,@PathVariable UUID room){return db.one("SELECT CASE WHEN status='PENDING' AND expires_at<=now() THEN 'EXPIRED' ELSE status END AS status FROM join_requests WHERE room_id=? AND user_id=? ORDER BY created_at DESC LIMIT 1",room,user(auth));}
 @PostMapping("/rooms/{room}/requests/{request}") Object decide(Authentication auth,@PathVariable UUID room,@PathVariable UUID request,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Decision body){return db.mutate(user(auth),key,"decide:"+room+":"+request,body,()->rooms.decide(user(auth),room,request,body.accept(),body.revision()));}
 @PostMapping("/rooms/{room}/ready") Object ready(Authentication auth,@PathVariable UUID room,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Ready body){return db.mutate(user(auth),key,"ready:"+room,body,()->rooms.ready(user(auth),room,body.ready(),body.revision()));}
 @PostMapping("/rooms/{room}/start") Object start(Authentication auth,@PathVariable UUID room,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Revision body){return db.mutate(user(auth),key,"start:"+room,body,()->rooms.start(user(auth),room,body.revision()));}
 @PostMapping("/rooms/{room}/leave") Object leave(Authentication auth,@PathVariable UUID room,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Revision body){return db.mutate(user(auth),key,"leave:"+room,body,()->rooms.leave(user(auth),room,user(auth),body.revision()));}
 @PostMapping("/rooms/{room}/invite") Object invite(Authentication auth,@PathVariable UUID room,@RequestHeader("Idempotency-Key") UUID key){return db.mutate(user(auth),key,"invite:"+room,Map.of(),()->rooms.invite(user(auth),room));}
 @PostMapping("/rooms/public") Object publicRoom(Authentication auth,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Create body){
  UUID user=user(auth);
  return db.mutate(user,key,"public",body,()->{
   db.jdbc.queryForList("SELECT pg_advisory_xact_lock(89001,?)",body.count());
   require(db.rows("SELECT 1 FROM user_allocations WHERE user_id=?",user).isEmpty(),409,"ALREADY_ALLOCATED");
   var candidates=db.rows("SELECT r.id FROM rooms r WHERE public_room AND phase='LOBBY' AND capacity=? AND (SELECT count(*) FROM room_members m WHERE m.room_id=r.id)<capacity AND EXISTS(SELECT 1 FROM room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=r.id AND u.connected_until>now()) ORDER BY created_at LIMIT 20 FOR UPDATE",body.count());
   for(var candidate:candidates){UUID room=Store.uuid(candidate.get("id"));var members=db.rows("SELECT user_id,seat FROM room_members WHERE room_id=?",room);if(members.stream().anyMatch(m->rooms.blocked(user,Store.uuid(m.get("user_id")))))continue;int seat=0;var seats=members.stream().map(m->(int)m.get("seat")).toList();while(seats.contains(seat))seat++;rooms.seat(room,user,seat);rooms.changed(room);return Map.of("room",rooms.snapshot(user,room));}
   Object result=rooms.create(user,body.count());var allocation=db.one("SELECT resource_id FROM user_allocations WHERE user_id=?",user);db.update("UPDATE rooms SET public_room=true WHERE id=?",allocation.get("resource_id"));return result;
  });
 }
}
