package engine;

import java.util.concurrent.ThreadLocalRandom;

public class RandomEngine implements Engine {

    public String name() {
        return "Random Engine";
    }

    public SearchResult search(GameState state, long millis) {
        int maxMoves = state.maxMoves();
        int[] moves = new int[maxMoves];
        int possibleMoves = state.legalMoves(moves);
        return new SearchResult(moves[ThreadLocalRandom.current().nextInt(possibleMoves)], 0, 0, 0, "Random Engine randomly selects.");
    }

}
