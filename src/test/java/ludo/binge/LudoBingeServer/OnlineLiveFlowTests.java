package ludo.binge.LudoBingeServer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ludo.binge.LudoBingeServer.shared.Store;
import ludo.binge.LudoBingeServer.online.MatchmakingService;
import ludo.binge.LudoBingeServer.game.MatchService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Predicate;
import static ludo.binge.LudoBingeServer.shared.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authenticated HTTP/SSE integration against an isolated disposable PostgreSQL database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "spring.config.import=", "ludo.workers.enabled=false",
 "ludo.turn.urls=turn:turn.example.test:3478", "ludo.turn.secret=isolated-test-secret"
})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners=OnlineLiveFlowTests.Cleanup.class,
 mergeMode=org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class OnlineLiveFlowTests {
 public static class Cleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
  @Override public int getOrder(){return Integer.MIN_VALUE;}
  @Override public void afterTestClass(org.springframework.test.context.TestContext context)throws Exception{postgres.close();}
 }
 static final EmbeddedPostgres postgres;
 static { try { postgres=EmbeddedPostgres.builder().setPort(0).start(); }
 catch(IOException e){throw new ExceptionInInitializerError(e);} }
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
  registry.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));
  registry.add("spring.datasource.username",()->"postgres");
  registry.add("spring.datasource.password",()->"postgres");
 }
 @LocalServerPort int port;
 @Autowired Store db;
 record Player(UUID id,String token){}
 Player player(String name){
  UUID u=id();String access=token();
  db.update("INSERT INTO users(id,google_sub,display_name,connected_until) VALUES (?,?,?,now()+interval '5 minutes')",u,u.toString(),name);
  db.update("INSERT INTO refresh_sessions(id,user_id,family,token_hash,access_hash,access_expires,expires_at) VALUES (?,?,?,?,?,now()+interval '15 minutes',now()+interval '1 day')",id(),u,id(),hash(token()),hash(access));
  return new Player(u,access);
 }
 Map<?,?> map(Object value){return (Map<?,?>)value;}
 Map<?,?> call(Player player,String path,Object body)throws Exception{
  var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path))
   .timeout(java.time.Duration.ofSeconds(5)).header("Authorization","Bearer "+player.token());
  if(body!=null)builder.header("Content-Type","application/json").header("Idempotency-Key",id().toString()).POST(HttpRequest.BodyPublishers.ofString(db.encode(body)));
  try(var client=HttpClient.newHttpClient()){
   var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode(),response.body());return db.decode(response.body(),Map.class);
  }
 }
 class Live implements AutoCloseable{
  final HttpURLConnection connection;final BufferedReader reader;
  Live(Player player)throws Exception{
   connection=(HttpURLConnection)URI.create("http://localhost:"+port+"/api/v1/live").toURL().openConnection();
   connection.setRequestProperty("Authorization","Bearer "+player.token());connection.setReadTimeout(5000);
   assertEquals(200,connection.getResponseCode());assertEquals("no",connection.getHeaderField("X-Accel-Buffering"));
   reader=new BufferedReader(new InputStreamReader(connection.getInputStream()));
  }
  Map<?,?> until(Predicate<Map<?,?>> predicate)throws Exception{
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
   while(System.nanoTime()<deadline){String line=reader.readLine();assertNotNull(line,"Live stream ended unexpectedly");
    if(line.startsWith("data:")){var snapshot=db.decode(line.substring(5).trim(),Map.class);if(predicate.test(snapshot))return snapshot;}
   }
   throw new AssertionError("Expected live transition was not delivered");
  }
  public void close()throws IOException{reader.close();connection.disconnect();}
 }
 @Test void privateHostSeesRequestAndGuestReceivesApprovalWithoutPolling()throws Exception{
  var host=player("Host");var guest=player("Guest");
  var created=call(host,"/rooms",Map.of("count",2));var room=map(created.get("room"));String roomId=room.get("id").toString();
  try(var hostLive=new Live(host);var guestLive=new Live(guest)){
   call(guest,"/rooms/join",Map.of("code",map(created.get("invite")).get("code")));
   var snapshot=hostLive.until(s->s.get("room") instanceof Map<?,?> r && r.get("joinRequests") instanceof List<?> requests&&!requests.isEmpty());
   var hostRoom=map(snapshot.get("room"));var request=map(((List<?>)hostRoom.get("joinRequests")).getFirst());
   assertEquals("Guest",request.get("display_name"));
   call(host,"/rooms/"+roomId+"/requests/"+request.get("id"),Map.of("revision",hostRoom.get("revision"),"accept",true));
   var joined=guestLive.until(s->s.get("room") instanceof Map<?,?> r && roomId.equals(r.get("id")));
   assertEquals(2,((List<?>)map(joined.get("room")).get("members")).size());
  }
  try(var reconnect=new Live(guest)){
   assertEquals(roomId,map(reconnect.until(s->s.containsKey("room")).get("room")).get("id"));
  }
 }
 @Test void publicPlayersReceiveRosterCountdownAndSameLiveMatch()throws Exception{
  var a=player("Alpha");var b=player("Beta");
  call(a,"/matchmaking/tickets",Map.of("count",2));call(b,"/matchmaking/tickets",Map.of("count",2));
  // Scheduled workers are disabled in this fixture; explicitly run the production worker.
  new MatchmakingService(db,new ludo.binge.LudoBingeServer.room.RoomService(db,""),true).tick();
  try(var aLive=new Live(a);var bLive=new Live(b)){
   var snapshot=aLive.until(s->s.get("queue") instanceof Map<?,?> q&&"RESERVED".equals(q.get("status")));
   var ticket=map(snapshot.get("queue"));var players=(List<?>)ticket.get("players");
   assertTrue(players.stream().map(this::map).anyMatch(p->"Beta".equals(p.get("displayName"))));
   var accept=Map.of("proposalId",map(ticket.get("proposal")).get("id"));
   call(a,"/matchmaking/accept",accept);call(b,"/matchmaking/accept",accept);call(a,"/matchmaking/accept",accept);
   var countdown=aLive.until(s->s.get("room") instanceof Map<?,?> r&&"COUNTDOWN".equals(r.get("phase")));
   String roomId=map(countdown.get("room")).get("id").toString();
   db.update("UPDATE rooms SET countdown_at=now()-interval '1 second' WHERE id=?",UUID.fromString(roomId));
   new MatchService(db,true).tick();
   var first=aLive.until(s->s.get("match") instanceof Map<?,?> m&&Boolean.TRUE.equals(m.get("active")));
   var second=bLive.until(s->s.get("match") instanceof Map<?,?> m&&Boolean.TRUE.equals(m.get("active")));
   assertEquals(map(first.get("match")).get("matchId"),map(second.get("match")).get("matchId"));
   var match=map(first.get("match"));
   call(a,"/matches/"+match.get("matchId")+"/commands",Map.of("action","ROLL","revision",match.get("revision")));
   Predicate<Map<?,?>> moved=s->s.get("match") instanceof Map<?,?> m&&((Number)m.get("revision")).longValue()>0;
   var aMove=map(aLive.until(moved).get("match"));var bMove=map(bLive.until(moved).get("match"));
   assertEquals(aMove.get("revision"),bMove.get("revision"));
   assertEquals(aMove.get("state"),bMove.get("state"));
   assertEquals(map(aMove.get("lastEvent")).get("dice"),map(bMove.get("lastEvent")).get("dice"));
  }
 }

 class SocketClient implements AutoCloseable, WebSocket.Listener {
  final java.util.concurrent.BlockingQueue<Map<?,?>> frames=new java.util.concurrent.LinkedBlockingQueue<>();
  final StringBuilder buffer=new StringBuilder();
  final HttpClient http=HttpClient.newHttpClient();final WebSocket socket;
  SocketClient(Player player){
   socket=http.newWebSocketBuilder().header("Authorization","Bearer "+player.token())
    .buildAsync(URI.create("ws://localhost:"+port+"/ws/online"),this).join();
  }
  public void onOpen(WebSocket socket){socket.request(1);}
  public java.util.concurrent.CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){
   buffer.append(data);if(last){frames.add(db.decode(buffer.toString(),Map.class));buffer.setLength(0);}socket.request(1);return null;
  }
  Map<?,?> until(Predicate<Map<?,?>> predicate)throws Exception{
   long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
   while(System.nanoTime()<end){var value=frames.poll(Math.max(1,end-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS);assertNotNull(value,"WebSocket event not delivered");if(predicate.test(value))return value;}
   throw new AssertionError("WebSocket event not delivered");
  }
  Map<?,?> request(String path,Map<?,?> body,UUID key)throws Exception{
   String request=id().toString();socket.sendText(db.encode(Map.of("type","REQUEST","requestId",request,"key",key,"path",path,"body",body)),true).join();
   var frame=until(f->request.equals(f.get("requestId")));assertEquals("ACK",frame.get("type"),frame.toString());return map(frame.get("data"));
  }
  Map<?,?> snapshot(Predicate<Map<?,?>> predicate)throws Exception{return map(until(f->"SNAPSHOT".equals(f.get("type"))&&predicate.test(map(f.get("data")))).get("data"));}
  public void close(){socket.abort();http.close();}
 }
 @Test void socketDeliversPrivateApprovalAndChatWithoutHttpPolling()throws Exception{
  var host=player("Socket Host");var guest=player("Socket Guest");
  try(var h=new SocketClient(host);var g=new SocketClient(guest)){
   h.snapshot(s->true);g.snapshot(s->true);
   var created=h.request("/rooms",Map.of("count",2),id());String room=map(created.get("room")).get("id").toString();
   g.request("/rooms/join",Map.of("code",map(created.get("invite")).get("code")),id());
   var hostState=h.snapshot(s->s.get("room") instanceof Map<?,?> r && r.get("joinRequests") instanceof List<?> requests&&!requests.isEmpty());
   var hostRoom=map(hostState.get("room"));var request=map(((List<?>)hostRoom.get("joinRequests")).getFirst());
   h.request("/rooms/"+room+"/requests/"+request.get("id"),Map.of("accept",true,"revision",hostRoom.get("revision")),id());
   var guestState=g.snapshot(s->s.get("room") instanceof Map<?,?> r&&room.equals(r.get("id")));
   assertFalse(map(guestState.get("room")).containsKey("joinRequests"));
   g.request("/rooms/"+room+"/messages",Map.of("body","🔥"),id());
   var chat=h.snapshot(s->s.get("messages") instanceof List<?> m&&!m.isEmpty());
   assertEquals("🔥",map(((List<?>)chat.get("messages")).getFirst()).get("body"));
   var hostVoice=h.request("/rooms/"+room+"/voice/join",Map.of(),id());
   assertFalse(((List<?>)hostVoice.get("iceServers")).isEmpty());
   var guestVoice=g.request("/rooms/"+room+"/voice/join",Map.of(),id());
   h.request("/rooms/"+room+"/voice/signal",Map.of("recipient",guest.id(),"type","offer","data","test-sdp"),id());
   var signal=g.snapshot(s->s.get("signals") instanceof List<?> signals&&!signals.isEmpty());
   var delivered=map(((List<?>)signal.get("signals")).getFirst());
   assertEquals("test-sdp",map(delivered.get("payload")).get("data"));
   assertTrue(((Number)delivered.get("id")).longValue()>((Number)guestVoice.get("signalCursor")).longValue());

  }
 }
 @Test void socketAcceptanceCommandsAndReconnectAreAuthoritative()throws Exception{
  var a=player("Socket A");var b=player("Socket B");
  try(var x=new SocketClient(a);var y=new SocketClient(b)){
   x.request("/matchmaking/tickets",Map.of("count",2),id());y.request("/matchmaking/tickets",Map.of("count",2),id());
   new MatchmakingService(db,new ludo.binge.LudoBingeServer.room.RoomService(db,""),true).tick();
   var found=x.snapshot(s->s.get("queue") instanceof Map<?,?> q&&"RESERVED".equals(q.get("status")));
   var accept=Map.of("proposalId",map(map(found.get("queue")).get("proposal")).get("id"));
   x.request("/matchmaking/accept",accept,id());y.request("/matchmaking/accept",accept,id());
   var countdown=x.snapshot(s->s.get("room") instanceof Map<?,?> r&&"COUNTDOWN".equals(r.get("phase")));
   String room=map(countdown.get("room")).get("id").toString();
   db.update("UPDATE rooms SET countdown_at=now()-interval '1 second' WHERE id=?",uuid(room));new MatchService(db,true).tick();
   var started=x.snapshot(s->s.get("match") instanceof Map<?,?> m&&Boolean.TRUE.equals(m.get("active")));
   var match=map(started.get("match"));assertEquals("WAITING_ROLL",map(match.get("state")).get("phase"));
   String path="/matches/"+match.get("matchId")+"/commands";UUID key=id();var command=Map.of("action","ROLL","revision",match.get("revision"));
   var rolled=x.request(path,command,key);var retry=x.request(path,command,key);assertEquals(rolled.get("revision"),retry.get("revision"));
   var remote=y.snapshot(s->s.get("match") instanceof Map<?,?> m&&m.get("revision") instanceof Number revision&&revision.longValue()==1);
   assertEquals(rolled.get("state"),map(remote.get("match")).get("state"));
   try(var reconnected=new SocketClient(b)){
    var recovered=reconnected.snapshot(s->s.get("match") instanceof Map<?,?> m&&match.get("matchId").equals(m.get("matchId")));
    assertEquals(rolled.get("state"),map(recovered.get("match")).get("state"));
   }
   db.update("UPDATE matches SET deadline=now()-interval '1 second' WHERE id=?",uuid(match.get("matchId")));
   new MatchService(db,true).tick();
   var timedOut=y.snapshot(s->s.get("match") instanceof Map<?,?> m&&m.get("revision") instanceof Number r&&r.longValue()==2);
   var event=map(map(timedOut.get("match")).get("lastEvent"));
   assertNull(event.get("dice"),"A timeout must not publish a random dice result");
   assertEquals(List.of("TURN_SKIPPED"),event.get("effects"));
  }
 }
 @Test void socketRejectsRoomEavesdroppingAndCrossRoomVoice()throws Exception{
  var host=player("Private");var stranger=player("Stranger");var created=call(host,"/rooms",Map.of("count",2));String room=map(created.get("room")).get("id").toString();
  try(var client=new SocketClient(stranger)){
   client.socket.sendText(db.encode(Map.of("type","SUBSCRIBE_ROOM","roomId",room)),true).join();
   assertEquals("NOT_A_MEMBER",client.until(f->"ERROR".equals(f.get("type"))).get("code"));
   String request=id().toString();client.socket.sendText(db.encode(Map.of("type","REQUEST","requestId",request,"key",id(),"path","/rooms/"+room+"/voice/signal","body",Map.of("recipient",host.id(),"type","offer","data","fake-sdp"))),true).join();
   assertEquals("NOT_A_MEMBER",client.until(f->request.equals(f.get("requestId"))).get("code"));
  }
 }
 @Test void rolledBackRoomChangesAreNotPublished(){
  var changes=new java.util.concurrent.atomic.AtomicInteger();db.onCommittedChange(changes::incrementAndGet);
  var user=player("Rollback");
  assertThrows(IllegalStateException.class,()->db.tx(()->{new ludo.binge.LudoBingeServer.room.RoomService(db,"").create(user.id(),2);throw new IllegalStateException("rollback");}));
  assertEquals(0,changes.get());
 }
}
