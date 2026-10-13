package ludo.binge.LudoBingeServer.online;

import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.core.Authentication;
import ludo.binge.LudoBingeServer.identity.IdentityService;
import ludo.binge.LudoBingeServer.identity.IdentityService.Actor;
import ludo.binge.LudoBingeServer.room.RoomService;
import ludo.binge.LudoBingeServer.game.MatchService;
import ludo.binge.LudoBingeServer.shared.Store;
import static ludo.binge.LudoBingeServer.shared.ApiError.require;
import static ludo.binge.LudoBingeServer.shared.Store.*;

@RestController
@RequestMapping("/api/v1")
public class OnlineController {
 private final Store db;private final RoomService rooms;private final MatchService matches;private final MatchmakingService queue;private final IdentityService identity;
 private final String turnUrls,turnSecret;
 private final ExecutorService streams=Executors.newVirtualThreadPerTaskExecutor();
 private final Map<UUID,SseEmitter> connections=new ConcurrentHashMap<>();
 public static final Set<String> MESSAGES=Set.of("Good luck!","Nice move!","Well played!","One more game?","Thanks!","Oops!","😂","👏","🔥","😮","🎉","🍀");
 public OnlineController(Store db,RoomService rooms,MatchService matches,MatchmakingService queue,IdentityService identity,
  @Value("${ludo.turn.urls:}")String turnUrls,@Value("${ludo.turn.secret:}")String turnSecret){this.db=db;this.rooms=rooms;this.matches=matches;this.queue=queue;this.identity=identity;this.turnUrls=turnUrls;this.turnSecret=turnSecret;}
 private UUID user(Authentication auth){return ((Actor)auth.getPrincipal()).userId();}
 public record Search(@Min(2) @Max(6) int count){}
 @PostMapping("/matchmaking/tickets") Object search(Authentication auth,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Search body){UUID u=user(auth);db.limit("queue:"+u,12,60);return db.mutate(u,key,"queue",body,()->queue.enter(u,body.count()));}
 @PostMapping("/matchmaking/cancel") Object cancel(Authentication auth,@RequestHeader("Idempotency-Key") UUID key){UUID u=user(auth);return db.mutate(u,key,"queue-cancel",Map.of(),()->queue.cancel(u));}
 @PostMapping("/matchmaking/accept") Object accept(Authentication auth,@RequestHeader("Idempotency-Key") UUID key){UUID u=user(auth);return db.mutate(u,key,"queue-accept",Map.of(),()->queue.accept(u));}
 @GetMapping("/matchmaking/tickets") Object ticket(Authentication auth){return queue.ticket(user(auth));}
 @PostMapping("/rooms/requests/cancel") Object cancelRequest(Authentication auth){db.update("UPDATE join_requests SET status='CANCELLED' WHERE user_id=? AND status='PENDING'",user(auth));return Map.of("cancelled",true);}
 public record Configure(@Min(2) @Max(6) int count,long revision){}
 @PostMapping("/rooms/{room}/configure") Object configure(Authentication auth,@PathVariable UUID room,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Configure body){UUID u=user(auth);return db.mutate(u,key,"configure:"+room,body,()->rooms.configure(u,room,body.count(),body.revision()));}

 /** Complete snapshots are the replay boundary: reconnect never depends on a lost incremental event. */
 private Object snapshot(UUID u,UUID requestedRoom){return db.readSnapshot(()->{
  var result=new LinkedHashMap<String,Object>();result.put("user",identity.profile(u));result.put("queue",queue.ticket(u));
  var allocation=db.rows("SELECT resource_id FROM user_allocations WHERE user_id=? AND kind='ROOM'",u);
  UUID room=allocation.isEmpty()?requestedRoom:uuid(allocation.getFirst().get("resource_id"));
  if(room!=null&&!db.rows("SELECT 1 FROM room_members m JOIN rooms r ON r.id=m.room_id WHERE m.room_id=? AND m.user_id=? AND r.phase<>'CLOSED'",room,u).isEmpty()){
   result.put("room",rooms.snapshot(u,room));result.put("match",matches.snapshot(u,room));
   result.put("messages",db.rows("SELECT m.id,m.user_id AS \"userId\",u.display_name AS \"displayName\",m.body,m.created_at FROM room_messages m JOIN users u ON u.id=m.user_id WHERE m.room_id=? AND m.created_at>now()-interval '5 minutes' ORDER BY m.id DESC LIMIT 30",room));
   result.put("voicePeers",db.rows("SELECT user_id AS \"userId\" FROM voice_members WHERE room_id=? AND expires_at>now()",room));
   result.put("signals",db.rows("SELECT id,sender,payload FROM voice_signals WHERE room_id=? AND recipient=? AND created_at>now()-interval '30 seconds' ORDER BY id",room,u).stream().map(s->Map.of("id",s.get("id"),"sender",s.get("sender"),"payload",db.decode(s.get("payload"),Object.class))).toList());
  }
  result.put("pendingRequests",db.rows("SELECT room_id AS \"roomId\",status FROM join_requests WHERE user_id=? AND status='PENDING' AND expires_at>now() ORDER BY created_at DESC LIMIT 1",u));
  result.put("serverTimeMs",System.currentTimeMillis());return result;
 });}
 @GetMapping("/sync") Object sync(Authentication auth,@RequestParam(required=false) UUID room){return snapshot(user(auth),room);}
 @GetMapping(value="/live",produces="text/event-stream") SseEmitter live(Authentication auth,@RequestParam(required=false) UUID room){
  Actor actor=(Actor)auth.getPrincipal();UUID u=actor.userId();db.limit("live:"+u,30,60);
  SseEmitter emitter=new SseEmitter(60_000L);SseEmitter old=connections.put(u,emitter);if(old!=null)old.complete();
  emitter.onCompletion(()->connections.remove(u,emitter));emitter.onTimeout(()->connections.remove(u,emitter));emitter.onError(e->connections.remove(u,emitter));
  streams.submit(()->{try{UUID retainedRoom=room;long end=System.currentTimeMillis()+55_000;while(connections.get(u)==emitter&&System.currentTimeMillis()<end){
   if(!identity.valid(actor))break;
   Object value=snapshot(u,retainedRoom);
   if(value instanceof Map<?,?> map && map.get("room") instanceof Map<?,?> activeRoom)retainedRoom=uuid(activeRoom.get("id"));
   emitter.send(SseEmitter.event().name("snapshot").data(db.encode(value)));Thread.sleep(400);
  }emitter.complete();}catch(Exception e){connections.remove(u,emitter);emitter.completeWithError(e);}});return emitter;
 }
 private void communicable(UUID user,UUID room){rooms.member(user,room);require(!db.rows("SELECT 1 FROM rooms WHERE id=? AND phase IN ('LOBBY','COUNTDOWN','PLAYING','FINISHED')",room).isEmpty(),409,"ROOM_CLOSED");}
 public record Message(@NotBlank @Size(max=80) String body){}
 @PostMapping("/rooms/{room}/messages") Object message(Authentication auth,@PathVariable UUID room,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Message body){UUID u=user(auth);db.limit("chat:"+u,8,10);return db.mutate(u,key,"message:"+room,body,()->{communicable(u,room);require(MESSAGES.contains(body.body()),400,"UNKNOWN_MESSAGE");db.update("INSERT INTO room_messages(room_id,user_id,kind,body) VALUES (?,?,'QUICK',?)",room,u,body.body());return Map.of("sent",true);});}
 @PostMapping("/rooms/{room}/voice/join") Object voiceJoin(Authentication auth,@PathVariable UUID room){UUID u=user(auth);communicable(u,room);require(!turnUrls.isBlank()&&!turnSecret.isBlank(),503,"VOICE_NOT_CONFIGURED");
  db.update("INSERT INTO voice_members VALUES (?,?,now()+interval '20 seconds') ON CONFLICT(room_id,user_id) DO UPDATE SET expires_at=excluded.expires_at",room,u);
  try{String username=(System.currentTimeMillis()/1000+3600)+":"+u;Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(turnSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA1"));String credential=Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
   return Map.of("iceServers",List.of(Map.of("urls",Arrays.asList(turnUrls.split(",")),"username",username,"credential",credential)));
  }catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}
 }
 @PostMapping("/rooms/{room}/voice/heartbeat") Object voiceHeartbeat(Authentication auth,@PathVariable UUID room){UUID u=user(auth);communicable(u,room);db.update("UPDATE voice_members SET expires_at=now()+interval '20 seconds' WHERE room_id=? AND user_id=?",room,u);return Map.of("ok",true);}
 @PostMapping("/rooms/{room}/voice/leave") Object voiceLeave(Authentication auth,@PathVariable UUID room){db.update("DELETE FROM voice_members WHERE room_id=? AND user_id=?",room,user(auth));return Map.of("left",true);}
 public record Signal(@NotNull UUID recipient,@NotBlank @Size(max=12) String type,@NotBlank @Size(max=16000) String data,String mid,int line){}
 @PostMapping("/rooms/{room}/voice/signal") Object signal(Authentication auth,@PathVariable UUID room,@Valid @RequestBody Signal signal){UUID u=user(auth);db.limit("signal:"+u,200,10);communicable(u,room);rooms.member(signal.recipient(),room);require(!u.equals(signal.recipient()),400,"INVALID_RECIPIENT");require(Set.of("offer","answer","candidate").contains(signal.type()),400,"INVALID_SIGNAL");
  require(db.jdbc.queryForObject("SELECT count(*) FROM voice_members WHERE room_id=? AND user_id IN (?,?) AND expires_at>now()",Integer.class,room,u,signal.recipient())==2,409,"VOICE_PEER_OFFLINE");
  db.update("INSERT INTO voice_signals(room_id,sender,recipient,payload) VALUES (?,?,?,?::jsonb)",room,u,signal.recipient(),db.encode(signal));return Map.of("sent",true);
 }
 @PreDestroy void close(){connections.values().forEach(SseEmitter::complete);streams.shutdownNow();}
}
