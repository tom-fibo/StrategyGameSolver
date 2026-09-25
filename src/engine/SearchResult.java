package engine;

public record SearchResult(int bestMove, int score, int depth, long nodes, String info) {}