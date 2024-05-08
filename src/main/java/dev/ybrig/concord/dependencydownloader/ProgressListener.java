package dev.ybrig.concord.dependencydownloader;

import com.walmartlabs.concord.dependencymanager.DependencyEntity;

public interface ProgressListener {

    default void onRetry(int retryCount, int maxRetry, long interval, String cause) {
        // do nothing
    }

    default void onTransferFailed(String error) {
        // do nothing
    }

    default void onDependencyResolved(DependencyEntity dependency) {
        // do nothing
    }
}
