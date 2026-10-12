package ludo.binge.LudoBingeServer.game.domain;

/** Immutable board/count contract. Physical seats never depend on screen rotation. */
public record MatchConfiguration(int playerCount, Board board, String rulesetVersion) {
    public enum Board { CLASSIC, SIX_PLAYER_HEX }
    public static final String RULESET_VERSION = "ludo-binge-online-v1";

    public MatchConfiguration {
        if (playerCount < 2 || playerCount > 6) {
            throw new IllegalArgumentException("Player count must be between 2 and 6");
        }
        if (board != boardFor(playerCount)) {
            throw new IllegalArgumentException("Board does not support the selected online player count");
        }
        if (!RULESET_VERSION.equals(rulesetVersion)) {
            throw new IllegalArgumentException("Unsupported ruleset version");
        }
    }

    public static MatchConfiguration forPlayers(int count) {
        return new MatchConfiguration(count, boardFor(count), RULESET_VERSION);
    }

    private static Board boardFor(int count) {
        return count <= 4 ? Board.CLASSIC : Board.SIX_PLAYER_HEX;
    }
}
