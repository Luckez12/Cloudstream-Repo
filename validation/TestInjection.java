import java.nio.file.*;
import java.util.regex.*;

// Kotlin Regex.escapeReplacement uses Matcher.quoteReplacement on JVM.
// Exercise the actual hook payload with JVM replacement semantics, then pass
// the resulting HTML to the hook test. This is not an Android compilation.
class TestInjection {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static String inject(String html, String base, String hook) {
        if (html.toLowerCase(java.util.Locale.ROOT).contains("<head>")) {
            return Pattern.compile("<head>", Pattern.CASE_INSENSITIVE)
                .matcher(html).replaceFirst(Matcher.quoteReplacement("<head>" + base + hook));
        }
        return base + hook + html;
    }
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of("MSM21/src/main/kotlin/com/msm21/extractors.kt"));
        check(source.contains("Regex.escapeReplacement(\"<head>$baseTag$hook\")"),
            "Production literal replacement guard missing");
        String marker = "private const val HOOK_JS = \"\"\"";
        int begin = source.indexOf(marker) + marker.length();
        String hook = source.substring(begin, source.indexOf("\"\"\"", begin));
        String base = "<base href=\"https://example.com/player/?v=a%24b\">";
        String html = "<!doctype html><html><head><title>Player</title></head><body></body></html>";
        boolean reproduced = false;
        try {
            Pattern.compile("<head>").matcher(html).replaceFirst("<head>" + base + hook);
        } catch (IllegalArgumentException ex) {
            reproduced = ex.getMessage().contains("group reference");
        }
        check(reproduced, "Old failure not reproduced with actual hook");
        String fixed = inject(html, base, hook);
        check(fixed.equals(html.replace("<head>", "<head>" + base + hook)), "Hook bytes changed");
        check(inject("<HTML><HEAD>x</HEAD></HTML>", base, hook)
            .equals("<HTML><head>" + base + hook + "x</HEAD></HTML>"), "Uppercase head failed");
        String noHead = "<html><body>x</body></html>";
        check(inject(noHead, base, hook).equals(base + hook + noHead), "No-head fallback changed");
        String meta = "<script>const a='$1 ${literal} \\path'; /^done$/;</script>";
        check(inject("<head>original", base, meta).equals("<head>" + base + meta + "original"),
            "Dollar or backslash payload altered");
        check(inject("<head>first<head>second", base, hook)
            .equals("<head>" + base + hook + "first<head>second"), "More than first head replaced");
        Files.writeString(Path.of(args[0]), fixed);
        System.out.println("PASS: reproduced v16 JVM error; actual hook preserved literally; uppercase/no-head/metacharacter/first-head cases");
    }
}
