/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.kernel.async;

import com.google.common.collect.Lists;
import sirius.kernel.commons.Callback;
import sirius.kernel.commons.ValueHolder;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.HandledException;
import sirius.kernel.health.Log;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents a value which is computed by another task or thread.
 * <p>
 * This is the core mechanism of non-blocking communication between different threads or systems. A value which
 * is not immediately available is returned as <tt>Promise</tt>. This promise is either successfully fulfilled
 * or supplied with a failure. In any case a {@link CompletionHandler} can be attached which is notified once
 * the computation is completed.
 * <p>
 * Since promises can be chained ({@link #chain(Promise)}, {@link #failChain(Promise, sirius.kernel.commons.Callback)})
 * or aggregated ({@link Tasks#sequence(java.util.List)}, {@link Barrier}) complex computations can be glued
 * together using simple components.
 *
 * @param <V> contains the type of the value which is to be computed
 */
public class Promise<V> {

    private ValueHolder<V> value;
    private Throwable failure;
    private volatile boolean logErrors = true;
    private List<CompletionHandler<V>> handlers = Lists.newArrayListWithCapacity(2);

    /**
     * Returns the value of the promise or <tt>null</tt> if not completed yet.
     *
     * @return the value of the promised computation. This method will not block, so <tt>null</tt>  is returned if
     * the computation has not finished (or failed) yet.
     */
    public V get() {
        return value != null ? value.get() : null;
    }

    /**
     * Marks the promise as successful and completed with the given value.
     *
     * @param value the value to be used as promised result.
     */
    public void success(@Nullable final V value) {
        this.value = new ValueHolder<V>(value);
        for (final CompletionHandler<V> handler : handlers) {
            completeHandler(value, handler);
        }
    }

    /*
     * Invokes the onSuccess method of given CompletionHandler.
     */
    private void completeHandler(final V value, final CompletionHandler<V> handler) {
        try {
            handler.onSuccess(value);
        } catch (Throwable e) {
            Exceptions.handle(Tasks.LOG, e);
        }
    }

    /**
     * Marks the promise as failed due to the given error.
     *
     * @param exception the error to be used as reason for failure.
     */
    public void fail(@Nonnull final Throwable exception) {
        this.failure = exception;
        if (logErrors) {
            Exceptions.handle(Tasks.LOG, exception);
        } else if (Tasks.LOG.isFINE() && !(exception instanceof HandledException)) {
            Tasks.LOG.FINE(Exceptions.createHandled().error(exception));
        }
        for (final CompletionHandler<V> handler : handlers) {
            failHandler(exception, handler);
        }
    }

    /*
     * Invokes the onFailure method of given CompletionHandler.
     */
    private void failHandler(final Throwable exception, final CompletionHandler<V> handler) {
        try {
            handler.onFailure(exception);
        } catch (Throwable e) {
            Exceptions.handle(Tasks.LOG, e);
        }
    }

    /**
     * Determines if the promise is completed yet.
     *
     * @return <tt>true</tt> if the promise has either successfully completed or failed yet, <tt>false</tt> otherwise.
     */
    public boolean isCompleted() {
        return isFailed() || isSuccessful();
    }

    /**
     * Determines if the promise is failed.
     *
     * @return <tt>true</tt> if the promise failed, <tt>false</tt> otherwise.
     */
    public boolean isFailed() {
        return failure != null;
    }

    /**
     * Determines if the promise was successfully completed yet.
     *
     * @return <tt>true</tt> if the promise was successfully completed, <tt>false</tt> otherwise.
     */
    public boolean isSuccessful() {
        return value != null;
    }

    /**
     * Waits until the promise is completed.
     *
     * @param timeout the maximal time to wait for the completion of this promise.
     */
    public void await(Duration timeout) {
        if (isCompleted()) {
            return;
        }

        awaitBlocking(timeout);
    }

    /*
     * Waits for a yet uncompleted promise by blocking the current thread via a Condition.
     */
    private void awaitBlocking(Duration timeout) {
        Lock lock = new ReentrantLock();
        Condition completed = lock.newCondition();

        onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(@Nullable Object value) throws Exception {
                lock.lock();
                try {
                    completed.signalAll();
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void onFailure(Throwable throwable) throws Exception {
                lock.lock();
                try {
                    completed.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        });

        if (!isCompleted()) {
            lock.lock();
            try {
                completed.await(timeout.getSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Exceptions.ignore(e);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Returns the failure which was the reason for this promise to have failed.
     *
     * @return the error which made this promise fail, or <tt>null</tt>  if the promnise is still running or not
     * completed yet.
     */
    public Throwable getFailure() {
        return failure;
    }

    /**
     * Used the result of this promise to create a new one by passing the resulting value into the given mapper.
     *
     * @param mapper the mapper to transform the promised value of this promise.
     * @param <X>    the resulting type of the mapper
     * @return a new promise which will be either contain the mapped value or which fails if either this promise fails
     * or if the mapper throws an exception.
     */
    @Nonnull
    public <X> Promise<X> map(@Nonnull final Function<V, X> mapper) {
        final Promise<X> result = new Promise<X>();
        onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
                try {
                    result.success(mapper.apply(value));
                } catch (Throwable throwable) {
                    result.fail(throwable);
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                result.fail(throwable);
            }
        });

        return result;
    }

    /**
     * Uses to result of this promise to generate a new promise using the given mapper.
     *
     * @param mapper the mapper to transform the promised value of this promise.
     * @param <X>    the resulting type of the mapper
     * @return a new promise which will be either contain the mapped value or which fails if either this promise fails
     * or if the mapper throws an exception.
     */
    @Nonnull
    public <X> Promise<X> flatMap(@Nonnull final Function<V, Promise<X>> mapper) {
        final Promise<X> result = new Promise<X>();
        onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
                try {
                    mapper.apply(value).chain(result);
                } catch (Throwable throwable) {
                    result.fail(throwable);
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                result.fail(throwable);
            }
        });

        return result;
    }

    /**
     * Chains this promise to the given one.
     * <p>
     * Connects both, the successful path as well as the failure handling of this promise to the given one.
     *
     * @param promise the promise to be used as completion handler for this.
     */
    public void chain(@Nonnull final Promise<V> promise) {
        onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
                promise.success(value);
            }

            @Override
            public void onFailure(Throwable throwable) {
                promise.fail(throwable);
            }
        });
    }

    /**
     * Chains this promise to the given one, by transforming the result value of this promise using the given mapper.
     *
     * @param promise the promise to be used as completion handler for this.
     * @param mapper  the mapper to be used to convert the result of this promise to the value used to the given
     *                promise.
     * @param <X>     type of the value expected by the given promise.
     */
    public <X> void mapChain(@Nonnull final Promise<X> promise, @Nonnull final Function<V, X> mapper) {
        onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
                try {
                    promise.success(mapper.apply(value));
                } catch (Throwable e) {
                    promise.fail(e);
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                promise.fail(throwable);
            }
        });
    }

    /**
     * Forwards failures to the given promise, while sending successful value to the given successHandler.
     *
     * @param promise        the promise to be supplied with any failure of this promise.
     * @param successHandler the handler used to process successfully computed values.
     * @param <X>            type of promised value of the given promise.
     * @return <tt>this</tt> for fluent method chaining
     */
    @Nonnull
    public <X> Promise<V> failChain(@Nonnull final Promise<X> promise, @Nonnull final Callback<V> successHandler) {
        return onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
                try {
                    successHandler.invoke(value);
                } catch (Throwable e) {
                    promise.fail(e);
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
                promise.fail(throwable);
            }
        });
    }

    /**
     * Adds a completion handler to this promise.
     * <p>
     * If the promise is already completed, the handler is immediately invoked.
     *
     * @param handler the handler to be notified once the promise is completed. A promise can notify more than one
     *                handler.
     * @return <tt>this</tt> for fluent method chaining
     */
    @Nonnull
    public Promise<V> onComplete(@Nonnull CompletionHandler<V> handler) {
        if (handler != null) {
            if (isSuccessful()) {
                completeHandler(get(), handler);
            } else if (isFailed()) {
                failHandler(getFailure(), handler);
            } else {
                this.handlers.add(handler);
                logErrors = false;
            }
        }

        return this;
    }

    /**
     * Adds a completion handler to this promise which only handles the successful completion of the promise.
     * <p>
     * If the promise is already completed, the handler is immediately invoked.
     *
     * @param successHandler the handler to be notified once the promise is completed. A promise can notify more than
     *                       one handler.
     * @return <tt>this</tt> for fluent method chaining
     */
    @Nonnull
    public Promise<V> onSuccess(@Nonnull final Callback<V> successHandler) {
        return onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
                try {
                    successHandler.invoke(value);
                } catch (Throwable t) {
                    fail(t);
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
            }
        });
    }

    /**
     * Adds a completion handler to this promise which only handles the successful completion of the promise.
     * <p>
     * If the promise is already completed, the handler is immediately invoked.
     *
     * @param successHandler the handler to be notified once the promise is completed. A promise can notify more than
     *                       one handler.
     * @return <tt>this</tt> for fluent method chaining
     */
    @Nonnull
    public Promise<V> onSuccess(@Nonnull final Consumer<V> successHandler) {
        return onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
                try {
                    successHandler.accept(value);
                } catch (Throwable t) {
                    fail(t);
                }
            }

            @Override
            public void onFailure(Throwable throwable) {
            }
        });
    }

    /**
     * Adds a completion handler to this promise which only handles the failed completion of the promise.
     * <p>
     * If the promise is already completed, the handler is immediately invoked.
     *
     * @param failureHandler the handler to be notified once the promise is completed. A promise can notify more than
     *                       one handler.
     * @return <tt>this</tt> for fluent method chaining
     */
    @Nonnull
    public Promise<V> onFailure(@Nonnull final Callback<Throwable> failureHandler) {
        logErrors = false;
        return onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
            }

            @Override
            public void onFailure(Throwable throwable) throws Exception {
                failureHandler.invoke(throwable);
            }
        });
    }

    /**
     * Adds a completion handler to this promise which only handles the failed completion of the promise.
     * <p>
     * If the promise is already completed, the handler is immediately invoked.
     *
     * @param failureHandler the handler to be notified once the promise is completed. A promise can notify more than
     *                       one handler.
     * @return <tt>this</tt> for fluent method chaining
     */
    @Nonnull
    public Promise<V> onFailure(@Nonnull final Consumer<Throwable> failureHandler) {
        logErrors = false;
        return onComplete(new CompletionHandler<V>() {
            @Override
            public void onSuccess(V value) throws Exception {
            }

            @Override
            public void onFailure(Throwable throwable) throws Exception {
                failureHandler.accept(throwable);
            }
        });
    }

    /**
     * Disables the error logging even if no failure handlers are present.
     *
     * @return <tt>this</tt> for fluent method chaining
     */
    public Promise<V> doNotLogErrors() {
        logErrors = false;
        return this;
    }

    /**
     * Adds an error handler, which handles failures by logging them to the given {@link Log}
     * <p>
     * By default, if no explicit completion handler is present, all failures are logged using the <tt>async</tt>
     * logger.
     *
     * @param log the logger to be used when logging an error.
     * @return <tt>this</tt> for fluent method chaining
     */
    @Nonnull
    public Promise<V> handleErrors(@Nonnull final Log log) {
        return onFailure((Consumer<Throwable>) value -> Exceptions.handle(log, value));
    }
}
