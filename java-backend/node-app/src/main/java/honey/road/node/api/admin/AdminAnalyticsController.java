package honey.road.node.api.admin;

import honey.road.node.queue.application.AnalyticsQueries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Branch analytics (sect 9.3). Optional {@code from}/{@code to}: a date {@code 2026-09-21} (branch timezone)
 * or an ISO-8601 instant; default is today up to now.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsController {
    private final AnalyticsQueries analytics;

    public AdminAnalyticsController(AnalyticsQueries analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    public AnalyticsQueries.Summary summary(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return analytics.summary(analytics.period(from, to));
    }

    @GetMapping("/windows")
    public List<AnalyticsQueries.WindowStat> windows(@RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        return analytics.windows(analytics.period(from, to));
    }

    @GetMapping("/deviations")
    public AnalyticsQueries.Deviations deviations(@RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        return analytics.deviations(analytics.period(from, to));
    }

    @GetMapping("/unfinished")
    public List<AnalyticsQueries.Unfinished> unfinished(@RequestParam(required = false) String from,
                                                        @RequestParam(required = false) String to) {
        return analytics.unfinished(analytics.period(from, to));
    }
}
