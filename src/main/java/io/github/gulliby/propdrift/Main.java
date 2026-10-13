package io.github.gulliby.propdrift;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Process entry point. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, false, StandardCharsets.UTF_8);
        int result = Cli.run(args, out, err);
        out.flush();
        err.flush();
        System.exit(result);
    }
}
