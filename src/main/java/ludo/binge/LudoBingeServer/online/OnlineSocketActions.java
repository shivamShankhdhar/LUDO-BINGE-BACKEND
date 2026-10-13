package ludo.binge.LudoBingeServer.online;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import jakarta.validation.Validator;
import ludo.binge.LudoBingeServer.identity.IdentityService.Actor;
import ludo.binge.LudoBingeServer.shared.Store;
import ludo.binge.LudoBingeServer.room.RoomService;
import ludo.binge.LudoBingeServer.game.MatchService;
import static ludo.binge.LudoBingeServer.shared.Store.*;
import static ludo.binge.LudoBingeServer.shared.ApiError.require;

/** Socket commands use the same authorization, validation and receipts as HTTP commands. */
@Component
public class OnlineSocketActions {
 private final OnlineController online;private final Store db;private final RoomService rooms;private final MatchService matches;private final Validator validator;
 public OnlineSocketActions(OnlineController online,Store db,RoomService rooms,MatchService matches,Validator validator){this.online=online;this.db=db;this.rooms=rooms;this.matches=matches;this.validator=validator;}
 private <T>T valid(T value){require(validator.validate(value).isEmpty(),400,"INVALID_REQUEST");return value;}
 private String string(Map<?,?> body,String key){return Objects.toString(body.get(key),"");}
 private long number(Map<?,?> body,String key){require(body.get(key) instanceof Number,400,"INVALID_REQUEST");return ((Number)body.get(key)).longValue();}
 public Object execute(Actor actor,String path,Map<?,?> body,UUID key){
  var auth=new UsernamePasswordAuthenticationToken(actor,null);UUID user=actor.userId();
  if(path.equals("/matchmaking/accept"))return online.accept(auth,key,new OnlineController.Accept(body.get("proposalId")==null?null:uuid(body.get("proposalId"))));
  if(path.equals("/matchmaking/tickets"))return online.search(auth,key,valid(new OnlineController.Search(Math.toIntExact(number(body,"count")))));
  if(path.equals("/matchmaking/cancel"))return online.cancel(auth,key);
  if(path.equals("/rooms/requests/cancel"))return online.cancelRequest(auth);
  if(path.equals("/rooms")){int count=valid(new OnlineController.Search(Math.toIntExact(number(body,"count")))).count();return db.mutate(user,key,"create",body,()->rooms.create(user,count));}
  if(path.equals("/rooms/join")){
   String code=string(body,"code");require(!code.isBlank()&&code.length()<=128,400,"INVALID_REQUEST");
   return db.mutate(user,key,"join",body,()->{UUID room=uuid(rooms.resolve(code).get("room_id"));return Map.of("roomId",room,"request",rooms.join(user,room,code));});
  }
  String[] parts=path.split("/");
  if(parts.length==4&&parts[1].equals("matches")&&parts[3].equals("commands")){
   String action=string(body,"action");require(Set.of("ROLL","MOVE","FORFEIT").contains(action),400,"INVALID_COMMAND");
   Integer pawn=body.get("pawn")==null?null:Math.toIntExact(number(body,"pawn"));require(pawn==null||(pawn>=0&&pawn<=3),400,"INVALID_REQUEST");
   return matches.command(user,uuid(parts[2]),key,number(body,"revision"),action,pawn);
  }
  require(parts.length>=4&&parts[1].equals("rooms"),404,"NOT_FOUND");UUID room=uuid(parts[2]);String action=parts[3];
  if(parts.length==4&&action.equals("messages"))return online.message(auth,room,key,valid(new OnlineController.Message(string(body,"body"))));
  if(parts.length==5&&action.equals("voice"))return switch(parts[4]){
   case "join" -> online.voiceJoin(auth,room);
   case "leave" -> online.voiceLeave(auth,room);
   case "heartbeat" -> online.voiceHeartbeat(auth,room);
   case "signal" -> online.signal(auth,room,valid(new OnlineController.Signal(uuid(body.get("recipient")),string(body,"type"),string(body,"data"),body.get("mid")==null?null:string(body,"mid"),body.get("line")==null?0:Math.toIntExact(number(body,"line")))));
   default -> throw new IllegalArgumentException("INVALID_REQUEST");
  };
  return db.mutate(user,key,path,body,()->{
   if(parts.length==5&&action.equals("requests")){require(body.get("accept") instanceof Boolean,400,"INVALID_REQUEST");return rooms.decide(user,room,uuid(parts[4]),Boolean.TRUE.equals(body.get("accept")),number(body,"revision"));}
   require(parts.length==4,404,"NOT_FOUND");
   return switch(action){
    case "ready" -> {require(body.get("ready") instanceof Boolean,400,"INVALID_REQUEST");yield rooms.ready(user,room,Boolean.TRUE.equals(body.get("ready")),number(body,"revision"));}
    case "start" -> rooms.start(user,room,number(body,"revision"));
    case "leave" -> rooms.leave(user,room,user,number(body,"revision"));
    case "invite" -> rooms.invite(user,room);
    case "configure" -> rooms.configure(user,room,valid(new OnlineController.Search(Math.toIntExact(number(body,"count")))).count(),number(body,"revision"));
    default -> throw new IllegalArgumentException("INVALID_REQUEST");
   };
  });
 }
}
