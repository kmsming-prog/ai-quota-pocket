package app.quotapocket.widget;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClaudeLoginLink {
    private static final Pattern URL = Pattern.compile("https://[^\\s<>\\p{Cntrl}]+");
    private static final Pattern CHALLENGE = Pattern.compile("(?:^|&)code_challenge=[^&]+(?:&|$)");

    static String findIn(String output) {
        Matcher matches = URL.matcher(output);
        while (matches.find()) {
            // Wait for a delimiter so a read split in the middle of the query cannot open a partial URL.
            if (matches.end() < output.length() && isOfficial(matches.group())) return matches.group();
        }
        return null;
    }

    private static boolean isOfficial(String address) {
        try {
            URI url = URI.create(address);
            String host = url.getHost();
            if (!"https".equals(url.getScheme()) || url.getRawUserInfo() != null
                    || url.getPort() != -1 || url.getRawFragment() != null) return false;
            boolean official = "claude.ai".equals(host) || "claude.com".equals(host)
                    || "platform.claude.com".equals(host) || "console.anthropic.com".equals(host);
            String path = url.getRawPath();
            boolean authorizePath = "/oauth/authorize".equals(path) || "/oauth2/authorize".equals(path)
                    || ("claude.com".equals(host) && "/cai/oauth/authorize".equals(path));
            String query = url.getRawQuery();
            return official && authorizePath && query != null && CHALLENGE.matcher(query).find();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
