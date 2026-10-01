package honey.road.node.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Link to the demo scenario runner ({@code tools/demo-runner}), demo stands only.
 *
 * @param url   runner address inside the stand network; blank => the scenario console is off
 * @param token shared secret the node presents to the runner
 */
@ConfigurationProperties("esuo.demo-runner")
public record DemoRunnerProperties(String url, String token) {
    public DemoRunnerProperties {
        url = url == null ? "" : url.trim().replaceAll("/+$", "");
        token = token == null ? "" : token.trim();
    }
}
