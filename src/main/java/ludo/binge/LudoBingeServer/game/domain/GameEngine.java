package ludo.binge.LudoBingeServer.game.domain;

import java.util.*;

/** Pure reducer. Coordinates mirror Android LudoEngine, including capture-gated laps. */
public final class GameEngine {
 public record Player(String userId,List<Integer> pawns,boolean captured,boolean forfeited,int missedTurns) {
  public Player { pawns=List.copyOf(pawns); }
 }
 public record State(int count,String ruleset,List<Player> players,int active,long turnId,String phase,Integer dice,int sixes,String winner,List<Player> checkpoint) {
  public State { players=List.copyOf(players); checkpoint=checkpoint==null?null:List.copyOf(checkpoint); }
  public State(int count,String ruleset,List<Player> players,int active,long turnId,String phase,Integer dice,int sixes,String winner){this(count,ruleset,players,active,turnId,phase,dice,sixes,winner,null);}
 }
 public record Transition(State state,List<Integer> path,List<String> effects) {}
 public State initial(List<String> users) {
  MatchConfiguration.forPlayers(users.size());
  return new State(users.size(),MatchConfiguration.RULESET_VERSION,users.stream().map(id->new Player(id,List.of(-1,-1,-1,-1),false,false,0)).toList(),0,1,"WAITING_ROLL",null,0,null);
 }
 private static void check(boolean valid,String reason){if(!valid)throw new IllegalArgumentException(reason);}
 public int finish(State s){return s.count()>4?83:56;}
 public int next(int step,int distance,boolean captured,boolean hex) {
  if(step==-1)return 0;
  for(int i=0;i<distance;i++)step=step==100?0:step==(hex?76:50)&&!captured?100:step+1;
  return step;
 }
 public List<Integer> legal(State s,int dice) {
  check(dice>=1&&dice<=6,"INVALID_DICE");var p=s.players().get(s.active());var result=new ArrayList<Integer>();
  if(p.forfeited())return result;
  for(int i=0;i<4;i++) {int v=p.pawns().get(i);int n=next(v,dice,p.captured(),s.count()>4);
   if(v!=finish(s)&&(v!=-1||dice==1||dice==6)&&(n==100||n<=finish(s)))result.add(i);
  }return List.copyOf(result);
 }
 public int global(State s,int seat,int step){
  int length=s.count()>4?78:52,entry=s.count()>4?77:51;
  if(step!=100&&(step<0||step>=entry))return -1;
  int offset=s.count()>4?new int[]{65,0,13,26,39,52}[seat]:(s.count()==2?new int[]{39,13}[seat]:new int[]{39,0,13,26}[seat]);
  return (offset+(step==100?length-1:step))%length;
 }
 public Transition roll(State s,int dice) {
  check(s.phase().equals("WAITING_ROLL"),"WRONG_PHASE");
  check(dice>=1&&dice<=6,"INVALID_DICE");
  int sixes=dice==6?s.sixes()+1:0;
  if(sixes>=3){var restored=new ArrayList<>(s.checkpoint()==null?s.players():s.checkpoint());for(int i=0;i<s.count();i++){var current=s.players().get(i);var before=restored.get(i);restored.set(i,current.forfeited()?current:new Player(before.userId(),before.pawns(),before.captured(),false,current.missedTurns()));}return new Transition(advance(s,restored,false,0),List.of(),List.of("DICE_ROLLED","THREE_SIXES_ROLLBACK","TURN_CHANGED"));}
  var moves=legal(s,dice);
  if(moves.isEmpty())return new Transition(advance(s,s.players(),false,0),List.of(),List.of("DICE_ROLLED","NO_LEGAL_MOVE","TURN_CHANGED"));
  return new Transition(new State(s.count(),s.ruleset(),s.players(),s.active(),s.turnId(),"WAITING_MOVE",dice,sixes,null,s.checkpoint()==null?s.players():s.checkpoint()),List.of(),List.of("DICE_ROLLED"));
 }
 public Transition move(State s,int pawn) {
  check(s.phase().equals("WAITING_MOVE")&&s.dice()!=null,"WRONG_PHASE");check(legal(s,s.dice()).contains(pawn),"ILLEGAL_MOVE");
  var players=new ArrayList<>(s.players());var current=players.get(s.active());var pawns=new ArrayList<>(current.pawns());
  int old=pawns.get(pawn),target=next(old,s.dice(),current.captured(),s.count()>4);pawns.set(pawn,target);
  var path=new ArrayList<Integer>();if(old==-1)path.add(0);else for(int i=1;i<=s.dice();i++)path.add(next(old,i,current.captured(),s.count()>4));
  int cell=global(s,s.active(),target);boolean capture=false;
  if(cell>=0&&cell%13!=0&&cell%13!=8)for(int seat=0;seat<s.count();seat++)if(seat!=s.active()){
   var enemy=players.get(seat);if(enemy.forfeited())continue;var enemyPawns=new ArrayList<>(enemy.pawns());
   for(int i=0;i<4;i++)if(global(s,seat,enemyPawns.get(i))==cell){enemyPawns.set(i,-1);capture=true;}
   players.set(seat,new Player(enemy.userId(),enemyPawns,enemy.captured(),false,enemy.missedTurns()));
  }
  players.set(s.active(),new Player(current.userId(),pawns,current.captured()||capture,false,current.missedTurns()));
  var effects=new ArrayList<String>();effects.add("PAWN_MOVED");if(capture)effects.add("CAPTURE");if(target==finish(s))effects.add("PAWN_HOME");
  if(pawns.stream().allMatch(p->p==finish(s))){effects.add("MATCH_FINISHED");return new Transition(new State(s.count(),s.ruleset(),players,s.active(),s.turnId(),"FINISHED",null,0,current.userId()),path,effects);}
  int sixes=s.sixes();boolean bonus=s.dice()==6||capture||target==finish(s);
  effects.add(bonus?"BONUS_TURN":"TURN_CHANGED");return new Transition(advance(s,players,bonus,sixes),path,effects);
 }
 private State advance(State s,List<Player> players,boolean bonus,int sixes){int active=s.active();if(!bonus)do{active=(active+1)%s.count();}while(players.get(active).forfeited());return new State(s.count(),s.ruleset(),players,active,s.turnId()+1,"WAITING_ROLL",null,bonus?sixes:0,null,bonus?s.checkpoint():null);}
 public State human(State s,int seat){var players=new ArrayList<>(s.players());var p=players.get(seat);players.set(seat,new Player(p.userId(),p.pawns(),p.captured(),p.forfeited(),0));return new State(s.count(),s.ruleset(),players,s.active(),s.turnId(),s.phase(),s.dice(),s.sixes(),s.winner(),s.checkpoint());}
 public State forfeit(State s,int seat){
  if(s.phase().equals("FINISHED"))return s;var players=new ArrayList<>(s.players());var p=players.get(seat);players.set(seat,new Player(p.userId(),List.of(-1,-1,-1,-1),p.captured(),true,p.missedTurns()));
  var remaining=players.stream().filter(x->!x.forfeited()).toList();
  if(remaining.size()<=1)return new State(s.count(),s.ruleset(),players,s.active(),s.turnId(),"FINISHED",null,0,remaining.isEmpty()?null:remaining.getFirst().userId());
  return seat==s.active()?advance(s,players,false,0):new State(s.count(),s.ruleset(),players,s.active(),s.turnId(),s.phase(),s.dice(),s.sixes(),s.winner(),s.checkpoint());
 }
 public Transition timeout(State s){
  var players=new ArrayList<>(s.players());var p=players.get(s.active());int missed=p.missedTurns()+1;
  players.set(s.active(),new Player(p.userId(),p.pawns(),p.captured(),p.forfeited(),missed));
  s=new State(s.count(),s.ruleset(),players,s.active(),s.turnId(),s.phase(),s.dice(),s.sixes(),s.winner(),s.checkpoint());
  if(missed>=3)return new Transition(forfeit(s,s.active()),List.of(),List.of("TIMEOUT_FORFEIT"));
  // A missed deadline skips the turn; it never rolls dice or moves a pawn for a human.
  return new Transition(advance(s,s.players(),false,0),List.of(),List.of("TURN_SKIPPED"));
 }
}
