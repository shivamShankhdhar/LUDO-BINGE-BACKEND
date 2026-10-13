package ludo.binge.LudoBingeServer.realtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import ludo.binge.LudoBingeServer.identity.IdentityService;
import ludo.binge.LudoBingeServer.identity.IdentityService.Actor;
import ludo.binge.LudoBingeServer.shared.*;
import ludo.binge.LudoBingeServer.online.*;
import static ludo.binge.LudoBingeServer.shared.Store.*;
import static ludo.binge.LudoBingeServer.shared.ApiError.require;

/** Authenticated, acknowledged commands and personalized snapshots published after commit. */
@Component
public class OnlineWebSocketHandler extends TextWebSocketHandler implements RealtimeNotifier {
 private final Store db;private final IdentityService identity;private final OnlineController online;private final OnlineSocketActions actions;private final RedisRealtimeService realtime;
 private final Map<String,Client> clients=new ConcurrentHashMap<>();
 private final ScheduledExecutorService dispatch=Executors.newSingleThreadScheduledExecutor();
 private final ExecutorService writers=Executors.newVirtualThreadPerTaskExecutor();
 private final AtomicBoolean pending=new AtomicBoolean();
 private static final String CHANGES="ludo:channel:committed";
 private static final class Client {
  final WebSocketSession session;final Actor actor;final AtomicBoolean pushing=new AtomicBoolean();final AtomicBoolean dirty=new AtomicBoolean();UUID room;String fingerprint="";long sequence=0;
  Client(WebSocketSession session,Actor actor){this.session=new ConcurrentWebSocketSessionDecorator(session,5000,512*1024);this.actor=actor;}
 }
 public OnlineWebSocketHandler(Store db,IdentityService identity,@Lazy OnlineController online,@Lazy OnlineSocketActions actions,RedisRealtimeService realtime){this.db=db;this.identity=identity;this.online=online;this.actions=actions;this.realtime=realtime;}
 @PostConstruct void listen(){
  realtime.subscribe(CHANGES,ignored->scheduleSnapshots());
  db.onCommittedChange(()->{if(!dispatch.isShutdown())dispatch.execute(()->realtime.publish(CHANGES,"changed"));});
 }
 private void scheduleSnapshots(){
  if(dispatch.isShutdown()||!pending.compareAndSet(false,true))return;
  dispatch.schedule(()->{pending.set(false);for(var client:clients.values())push(client);},20,TimeUnit.MILLISECONDS);
 }
 private void push(Client client){
  client.dirty.set(true);if(!client.pushing.compareAndSet(false,true))return;
  writers.submit(()->{try{while(client.dirty.getAndSet(false))snapshot(client,false);}catch(Exception e){close(client);}finally{client.pushing.set(false);if(client.dirty.get()&&client.session.isOpen())push(client);}});
 }
 @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{
  require(session.getAttributes().get("actor") instanceof Actor,401,"AUTHENTICATION_REQUIRED");
  Actor actor=(Actor)session.getAttributes().get("actor");db.limit("socket:"+actor.userId(),30,60);
  session.setTextMessageSizeLimit(32768);var client=new Client(session,actor);clients.put(session.getId(),client);
  presence(actor);send(client,Map.of("type","CONNECTED","protocolVersion",2));snapshot(client,true);
 }
 private void presence(Actor actor){db.update("UPDATE users SET connected_until=now()+interval '30 seconds' WHERE id=?",actor.userId());}
 @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){
  Client client=clients.get(session.getId());if(client==null)return;String requestId="";
  try{
   require(identity.valid(client.actor),401,"AUTHENTICATION_REQUIRED");
   var frame=db.decode(message.getPayload(),Map.class);String type=Objects.toString(frame.get("type"),"");requestId=Objects.toString(frame.get("requestId"),"");
   require(requestId.length()<=64,400,"INVALID_REQUEST");db.limit("socket-frame:"+client.actor.userId(),600,60);
   switch(type){
    case "PING" -> {presence(client.actor);send(client,Map.of("type","PONG","serverTimeMs",System.currentTimeMillis()));}
    case "SYNC" -> {
     synchronized(client){String room=Objects.toString(frame.get("room"),"");client.room=room.isBlank()?null:uuid(room);snapshot(client,true);}
     send(client,Map.of("type","ACK","requestId",requestId,"data",Map.of("synced",true)));
    }
    case "REQUEST" -> {
     require(!requestId.isBlank()&&frame.get("body") instanceof Map,400,"INVALID_REQUEST");
     UUID key=uuid(frame.get("key"));String path=Objects.toString(frame.get("path"),"");
     Object result=actions.execute(client.actor,path,(Map<?,?>)frame.get("body"),key);
     send(client,Map.of("type","ACK","requestId",requestId,"data",result));snapshot(client,true);
    }
    // Compatibility subscriptions are authorized; personalized snapshots never share host-only data.
    case "SUBSCRIBE_ROOM" -> {
     UUID room=uuid(frame.get("roomId"));require(!db.rows("SELECT 1 FROM room_members WHERE room_id=? AND user_id=?",room,client.actor.userId()).isEmpty(),403,"NOT_A_MEMBER");
     synchronized(client){client.room=room;snapshot(client,true);}
    }
    case "SUBSCRIBE_MATCH" -> {
     UUID match=uuid(frame.get("matchId"));require(!db.rows("SELECT 1 FROM match_players WHERE match_id=? AND user_id=?",match,client.actor.userId()).isEmpty(),403,"NOT_A_PLAYER");snapshot(client,true);
    }
    default -> throw new ApiError(400,"UNKNOWN_MESSAGE");
   }
  }catch(ApiError e){error(client,requestId,e.status,e.code);if(e.status==401)close(client);}
   catch(IllegalArgumentException|NullPointerException e){error(client,requestId,400,"INVALID_REQUEST");}
   catch(Exception e){error(client,requestId,503,"SERVICE_UNAVAILABLE");}
 }
 private void error(Client c,String id,int status,String code){try{send(c,Map.of("type","ERROR","requestId",id,"status",status,"code",code));}catch(Exception ignored){close(c);}}
 private void snapshot(Client client,boolean force)throws Exception{
  synchronized(client){
   if(!client.session.isOpen())return;require(identity.valid(client.actor),401,"AUTHENTICATION_REQUIRED");
   var value=new LinkedHashMap<String,Object>((Map<String,Object>)online.snapshot(client.actor.userId(),client.room));
   if(value.get("room") instanceof Map<?,?> room)client.room=uuid(room.get("id"));
   var comparison=new LinkedHashMap<>(value);comparison.remove("serverTimeMs");
   // Match views contain their own observation timestamp, which is not a state change.
   if(comparison.get("match") instanceof Map<?,?> m){var match=new LinkedHashMap<>(m);match.remove("serverTimeMs");comparison.put("match",match);}
   String fingerprint=db.encode(comparison);if(!force&&fingerprint.equals(client.fingerprint))return;
   client.fingerprint=fingerprint;
   send(client,Map.of("type","SNAPSHOT","sequence",++client.sequence,"data",value));
  }
 }
 private void send(Client client,Object value)throws Exception{if(client.session.isOpen())client.session.sendMessage(new TextMessage(db.encode(value)));}
 private void close(Client client){clients.remove(client.session.getId());try{client.session.close(CloseStatus.NORMAL);}catch(Exception ignored){}}
 @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){clients.remove(session.getId());}
 @Override public void handleTransportError(WebSocketSession session,Throwable error){var client=clients.remove(session.getId());if(client!=null)close(client);}
 // Older MatchService integrations can request a refresh; committed Store changes own publication.
 public void broadcastMatchUpdate(UUID match,Object snapshot){}
 public void broadcastRoomUpdate(UUID room,Object snapshot){}
 @PreDestroy void stop(){clients.values().forEach(this::close);dispatch.shutdownNow();writers.shutdownNow();}
}
