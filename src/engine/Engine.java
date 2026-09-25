package engine;

public interface Engine {
    String name();
    /** Think for about `millis` ms on `state` (which must be left unchanged) and return a move. */
    SearchResult search(GameState state, long millis);
}