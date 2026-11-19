package app.quotapocket.widget;

enum WidgetDisplayMode {
    AUTO("自動"),
    CHATGPT("只顯示 ChatGPT"),
    CLAUDE("只顯示 Claude"),
    BOTH("顯示兩者");

    static final int CHATGPT_SOURCE = 1;
    static final int CLAUDE_SOURCE = 2;

    final String label;

    WidgetDisplayMode(String label) {
        this.label = label;
    }

    static WidgetDisplayMode fromPreference(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return AUTO; }
    }

    boolean usesSingleWidget(int sources) {
        return this == CHATGPT || this == CLAUDE
                || (this == AUTO && (sources == CHATGPT_SOURCE || sources == CLAUDE_SOURCE));
    }

    int singleCardIndex(int sources) {
        return this == CLAUDE || (this != CHATGPT && sources == CLAUDE_SOURCE) ? 1 : 0;
    }
}
