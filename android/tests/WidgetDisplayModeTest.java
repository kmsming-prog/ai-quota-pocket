package app.quotapocket.widget;

public final class WidgetDisplayModeTest {
    public static void main(String[] args) {
        int chatgpt = WidgetDisplayMode.CHATGPT_SOURCE;
        int claude = WidgetDisplayMode.CLAUDE_SOURCE;

        checkMode(WidgetDisplayMode.AUTO, 0, false, 0);
        checkMode(WidgetDisplayMode.AUTO, chatgpt, true, 0);
        checkMode(WidgetDisplayMode.AUTO, claude, true, 1);
        checkMode(WidgetDisplayMode.AUTO, chatgpt | claude, false, 0);

        for (int sources : new int[]{0, chatgpt, claude, chatgpt | claude}) {
            checkMode(WidgetDisplayMode.CHATGPT, sources, true, 0);
            checkMode(WidgetDisplayMode.CLAUDE, sources, true, 1);
            checkMode(WidgetDisplayMode.BOTH, sources, false, sources == claude ? 1 : 0);
        }

        for (WidgetDisplayMode mode : WidgetDisplayMode.values()) {
            if (WidgetDisplayMode.fromPreference(mode.name()) != mode) throw new AssertionError("Mode changed after saving");
        }
        if (WidgetDisplayMode.fromPreference("unknown") != WidgetDisplayMode.AUTO
                || WidgetDisplayMode.fromPreference(null) != WidgetDisplayMode.AUTO) {
            throw new AssertionError("Unset preferences must default to automatic");
        }
        System.out.println("Widget display and automatic provider selection checks passed.");
    }

    private static void checkMode(WidgetDisplayMode mode, int sources, boolean single, int card) {
        if (mode.usesSingleWidget(sources) != single || mode.singleCardIndex(sources) != card) {
            throw new AssertionError("Unexpected layout or provider for " + mode + " with sources " + sources);
        }
    }
}
