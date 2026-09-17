package honey.road.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("esuo")
public record NodeProperties(String branchId, String branchName, String timezone,
                             boolean demoData, String operatorKey) {}
