package engine;

import java.util.Scanner;

public class ManualEngine implements Engine {

    // One shared Scanner for the whole program. Never close it: closing it also closes System.in.
    private static final Scanner INPUT = new Scanner(System.in);

    public String name() {
        return "Manual";
    }

    public SearchResult search(GameState state, long millis) {
        int[] moves = new int[state.maxMoves()];
        int possibleMoves = state.legalMoves(moves);

        StringBuilder choices = new StringBuilder();
        for (int i = 0; i < possibleMoves; i++) {
            if (i > 0) choices.append(", ");
            choices.append(state.moveToString(moves[i]));
        }

        System.out.println(state);
        System.out.println("Valid moves: " + choices);
        while (true) {
            System.out.print("Your move: ");
            if (!INPUT.hasNextLine()) throw new IllegalStateException("Input closed while waiting for a move");
            int move = state.parseMove(INPUT.nextLine());
            if (move >= 0) return new SearchResult(move, 0, 0, 0, "Human decided.");
            System.out.println("Not a legal move, try again.");
        }
    }
}
