package honey.road.node.api.admin;

import honey.road.node.queue.application.Recommendations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Window-opening recommendations (sect 6.8, 9.3). */
@RestController
@RequestMapping("/api/v1/admin/recommendations")
public class AdminRecommendationsController {
    private final Recommendations recommendations;

    public AdminRecommendationsController(Recommendations recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping
    public Recommendations.Result get() {
        return recommendations.current();
    }
}
