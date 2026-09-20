package com.handsoffretail.fastmirror.service.implementation;

import com.handsoffretail.fastmirror.service.Sleeper;

/** The production {@link Sleeper}: a plain {@link Thread#sleep(long)}. */
public final class ThreadSleeperImpl implements Sleeper {

    @Override
    public void sleep(long millis) throws InterruptedException {
        if (millis <= 0L) {
            return;
        }
        Thread.sleep(millis);
    }
}
