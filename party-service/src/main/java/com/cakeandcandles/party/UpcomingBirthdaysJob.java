package com.cakeandcandles.party;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A background job. The agent instruments @Scheduled methods, so this produces
 * traces that start without any HTTP request - useful to show that not every
 * trace is a web request.
 */
@Component
public class UpcomingBirthdaysJob {

    private static final Logger log = LoggerFactory.getLogger(UpcomingBirthdaysJob.class);
    private final PartyPlanner planner;

    public UpcomingBirthdaysJob(PartyPlanner planner) {
        this.planner = planner;
    }

    @Scheduled(fixedRateString = "${jobs.upcoming-birthdays.rate:60000}", initialDelay = 15_000)
    public void run() {
        int upcoming = planner.countUpcomingBirthdays(7);
        log.info("{} birthday(s) coming up in the next 7 days", upcoming);
    }
}
