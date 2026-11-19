package app.quotapocket.widget;

import java.util.Objects;

public final class ClaudeLoginLinkTest {
    public static void main(String[] args) {
        String query = "?code=true&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"
                + "&code_challenge=sample&code_challenge_method=S256&state=sample";
        String current = "https://claude.com/cai/oauth/authorize" + query;
        String prompt = "If the browser didn't open, visit: ";
        expect(current, prompt + current + "\r\n\r\nPaste code here > ");
        expect(null, prompt + current.substring(0, current.length() - 3));

        for (String endpoint : new String[]{"https://claude.ai/oauth/authorize",
                "https://platform.claude.com/oauth/authorize", "https://console.anthropic.com/oauth2/authorize"}) {
            expect(endpoint + query, prompt + endpoint + query + "\n");
        }

        for (String endpoint : new String[]{"https://claude.com.example.org/cai/oauth/authorize",
                "https://claude.com@phishing.example/cai/oauth/authorize",
                "https://another.example@claude.com/cai/oauth/authorize",
                "https://claude.com:8142/cai/oauth/authorize",
                "https://claude.com/cai/oauth/authorize/another-page",
                "https://claude.com/cai/oauth/%61uthorize", "https://claude.ai/cai/oauth/authorize"}) {
            expect(null, prompt + endpoint + query + "\n");
        }
        expect(null, prompt + current.replace("&code_challenge=", "&fake_code_challenge=") + "\n");
        expect(null, prompt + current + "#fragment\n");
        System.out.println("Claude login URL regression checks passed.");
    }

    private static void expect(String expected, String output) {
        if (!Objects.equals(expected, ClaudeLoginLink.findIn(output))) {
            throw new AssertionError("Unexpected result for: " + output);
        }
    }
}
