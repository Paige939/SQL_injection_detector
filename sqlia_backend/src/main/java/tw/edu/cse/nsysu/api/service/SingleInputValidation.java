package tw.edu.cse.nsysu.api.service;

public final class SingleInputValidation {
    public static final int MAX_LENGTH = 4000;
    public static final String MESSAGE = "Enter nonblank input of at most 4000 characters.";

    private SingleInputValidation() {}

    public static boolean isValid(String input) {
        return input != null && !input.isBlank() && input.length() <= MAX_LENGTH;
    }

    public static void requireValid(String input) {
        if (!isValid(input)) throw new IllegalArgumentException(MESSAGE);
    }
}
