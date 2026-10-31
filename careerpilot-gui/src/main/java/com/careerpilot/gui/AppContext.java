package com.careerpilot.gui;

import com.careerpilot.CareerPilotCore;
import com.careerpilot.users.User;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The GUI's handle on the shared core: the Spring context plus the signed-in user. */
public final class AppContext {
    private final ConfigurableApplicationContext context;
    private User user;
    private java.util.function.Consumer<String> linkOpener = url -> { };

    public AppContext(ConfigurableApplicationContext context) { this.context = context; }

    public static AppContext start() {
        return new AppContext(new SpringApplicationBuilder(CareerPilotCore.class).web(WebApplicationType.NONE).headless(false)
                .logStartupInfo(false).properties("spring.main.banner-mode=off", "logging.level.root=WARN").run());
    }

    public <T> T bean(Class<T> type) { return context.getBean(type); }

    public User user() { return user; }

    public void setLinkOpener(java.util.function.Consumer<String> opener) { this.linkOpener = opener; }

    public void openLink(String url) { linkOpener.accept(url); }

    public void signIn(User user) { this.user = user; }

    public void signOut() { this.user = null; }

    public void close() { context.close(); }
}
