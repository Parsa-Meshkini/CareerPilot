package com.careerpilot.server;

import com.careerpilot.CareerPilotCore;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** REST API used by the Chrome extension (F04) and external clients. Start with: java -jar careerpilot-server.jar */
@SpringBootApplication(scanBasePackages = "com.careerpilot.server")
@Import(CareerPilotCore.class)
public class CareerPilotServer {
    public static void main(String[] args) {
        SpringApplication.run(CareerPilotServer.class, args);
    }
}
