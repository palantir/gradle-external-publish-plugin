/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.externalpublish;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.gradle.api.Action;
import org.gradle.api.Task;

final class CircleCiContextDeadlineAvoidance {
    private static final ScheduledExecutorService CIRCLE_CI_OUTPUT_SPAMMER =
            Executors.newScheduledThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "circle-output-spammer-%d");
                thread.setDaemon(true);
                return thread;
            });

    public static void avoidHittingCircleCiContextDeadlineByPrintingEverySoOften(Task task) {
        // The default context deadline is 10 minutes, so we use 5 minutes
        avoidHittingCircleCiContextDeadlineByPrintingEvery(task, Duration.ofMinutes(5));
    }

    public static void avoidHittingCircleCiContextDeadlineByPrintingEvery(Task task, Duration duration) {
        AtomicReference<ScheduledFuture<?>> spammerTask = new AtomicReference<>();

        task.doFirst(new Action<Task>() {
            @Override
            public void execute(Task _ignored) {
                spammerTask.set(CIRCLE_CI_OUTPUT_SPAMMER.scheduleWithFixedDelay(
                        () -> {
                            task.getLogger().lifecycle("Printing output to avoid hitting Circle context deadline");
                        },
                        duration.toMillis(),
                        duration.toMillis(),
                        TimeUnit.MILLISECONDS));
            }
        });

        task.doLast(new Action<Task>() {
            @Override
            public void execute(Task _ignored) {
                spammerTask.get().cancel(true);
            }
        });
    }

    private CircleCiContextDeadlineAvoidance() {}
}
