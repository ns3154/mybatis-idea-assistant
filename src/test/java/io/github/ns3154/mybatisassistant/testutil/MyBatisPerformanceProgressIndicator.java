package io.github.ns3154.mybatisassistant.testutil;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记录性能夹具的取消检查和进度变化，并允许在算法内部第 N 次检查时取消。
 */
public final class MyBatisPerformanceProgressIndicator implements ProgressIndicator {
    private final EmptyProgressIndicator delegate = new EmptyProgressIndicator();
    private final AtomicInteger checkCount = new AtomicInteger();
    private final AtomicInteger canceledCheckCount = new AtomicInteger();
    private final List<Double> fractions = new CopyOnWriteArrayList<>();
    private final List<Boolean> fractionReadAccess = new CopyOnWriteArrayList<>();
    private final List<CancellationPoll> cancellationPolls = new CopyOnWriteArrayList<>();
    private final List<String> secondaryTexts = new CopyOnWriteArrayList<>();
    private final List<Boolean> secondaryTextReadAccess = new CopyOnWriteArrayList<>();
    private volatile String secondaryText = "";
    private volatile double fraction;
    private volatile int cancelAtCheck = Integer.MAX_VALUE;

    public void armCancellationAfterChecks(int additionalChecks) {
        if (additionalChecks <= 0) {
            throw new IllegalArgumentException("additionalChecks must be positive");
        }
        cancelAtCheck = checkCount.get() + additionalChecks;
    }

    public int checkCount() {
        return checkCount.get();
    }

    public int canceledCheckCount() {
        return canceledCheckCount.get();
    }

    public List<Double> fractions() {
        return List.copyOf(fractions);
    }

    public List<Boolean> fractionReadAccess() {
        return List.copyOf(fractionReadAccess);
    }

    public List<CancellationPoll> cancellationPolls() {
        return List.copyOf(cancellationPolls);
    }

    public List<String> secondaryTexts() {
        return List.copyOf(secondaryTexts);
    }

    public List<Boolean> secondaryTextReadAccess() {
        return List.copyOf(secondaryTextReadAccess);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }

    @Override
    public boolean isCanceled() {
        pollCancellation();
        return delegate.isCanceled();
    }

    @Override
    public void setText(String text) {
        delegate.setText(text);
    }

    @Override
    public String getText() {
        return delegate.getText();
    }

    @Override
    public void setText2(String text) {
        secondaryTexts.add(text);
        secondaryTextReadAccess.add(
                ApplicationManager.getApplication().isReadAccessAllowed());
        secondaryText = text;
        delegate.setText2(text);
    }

    @Override
    public String getText2() {
        return secondaryText;
    }

    @Override
    public double getFraction() {
        return fraction;
    }

    @Override
    public void setFraction(double fraction) {
        fractions.add(fraction);
        fractionReadAccess.add(ApplicationManager.getApplication().isReadAccessAllowed());
        this.fraction = fraction;
        delegate.setFraction(fraction);
    }

    @Override
    public void pushState() {
        delegate.pushState();
    }

    @Override
    public void popState() {
        delegate.popState();
    }

    @Override
    public void startNonCancelableSection() {
        delegate.startNonCancelableSection();
    }

    @Override
    public void finishNonCancelableSection() {
        delegate.finishNonCancelableSection();
    }

    @Override
    public boolean isModal() {
        return delegate.isModal();
    }

    @Override
    public ModalityState getModalityState() {
        return delegate.getModalityState();
    }

    @Override
    public void setModalityProgress(ProgressIndicator modalityProgress) {
        delegate.setModalityProgress(modalityProgress);
    }

    @Override
    public boolean isIndeterminate() {
        return delegate.isIndeterminate();
    }

    @Override
    public void setIndeterminate(boolean indeterminate) {
        delegate.setIndeterminate(indeterminate);
    }

    @Override
    public void checkCanceled() {
        pollCancellation();
        if (delegate.isCanceled()) {
            canceledCheckCount.incrementAndGet();
        }
        delegate.checkCanceled();
    }

    private void pollCancellation() {
        cancellationPolls.add(new CancellationPoll(
                fraction,
                ApplicationManager.getApplication().isReadAccessAllowed()));
        int current = checkCount.incrementAndGet();
        if (current >= cancelAtCheck) {
            delegate.cancel();
        }
    }

    /**
     * 记录一次真实取消轮询发生时的进度阶段与读锁状态。
     */
    public record CancellationPoll(double fraction, boolean readAccess) {
    }

    @Override
    public boolean isPopupWasShown() {
        return delegate.isPopupWasShown();
    }

    @Override
    public boolean isShowing() {
        return delegate.isShowing();
    }
}
