package test;

public class SimpleGame {
    public static void main(String[] args) {
        int secret = getRandom(1, 10);
        output("Guess a number from 1 to 10");
        int guess = getInput();
        boolean correctGuess = (secret == guess);
        if (correctGuess) {
            output("Congratulations! " +
                    guess + " was right ");
        }
        else {
            output("Sorry, your guess" + " was incorrect ");
        }
    }

    private static void output(String outputString) {
        // Output the string
    }

    private static int getInput() {
        // Get the user input and return it
        return 0;
    }

    private static int getRandom(int i, int j) {
        // Get a random number between i and j
        return 0;
    }
}
