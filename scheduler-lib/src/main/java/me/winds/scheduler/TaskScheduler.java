package me.winds.scheduler;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author:  winds
 * Data:    2018/3/12
 * Version: 1.0
 * Desc:    异步管理类
 */
public class TaskScheduler {
    private static final String TAG = "TaskScheduler";

    private volatile static TaskScheduler sTaskScheduler;

    private Executor mParallelExecutor;
    private ExecutorService mTimeOutExecutor;
    private ExecutorService mSingleThreadExecutor;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT > 0 ? Math.max(3, CPU_COUNT) : 3;

    private Map<String, Handler> mHandlerMap = new ConcurrentHashMap<>();
    private Handler mMainHandler = new SafeDispatchHandler(Looper.getMainLooper());

    /**
     * 实例化
     *
     * @return
     */
    public static TaskScheduler getInstance() {
        if (sTaskScheduler == null) {
            synchronized (TaskScheduler.class) {
                if (sTaskScheduler == null) {
                    sTaskScheduler = new TaskScheduler();
                }
            }
        }
        return sTaskScheduler;
    }

    /**
     * 初始化Scheduler 仅初始化可常用到的线程池
     * 其他的线程提供其他方法初始化
     */
    private TaskScheduler() {
        //最少三个线程
        mParallelExecutor = Executors.newFixedThreadPool(MAXIMUM_POOL_SIZE);
    }

    /**
     * 获取可控制超时的线程池
     *
     * @return
     */
    private ExecutorService getTimeOutExecutor() {
        if (mTimeOutExecutor == null) {
            synchronized (TaskScheduler.class) {
                if (mTimeOutExecutor == null) {
                    /**
                     * 没有核心线程的线程池要用 SynchronousQueue 而不是LinkedBlockingQueue，SynchronousQueue是一个只有一个任务的队列，
                     * 这样每次就会创建非核心线程执行任务,因为线程池任务放入队列的优先级比创建非核心线程优先级大.
                     */
                    mTimeOutExecutor = new ThreadPoolExecutor(
                            0,
                            MAXIMUM_POOL_SIZE,
                            60L,
                            TimeUnit.SECONDS,
                            new SynchronousQueue<Runnable>(),
                            new ThreadFactory() {
                                private final AtomicInteger mCount = new AtomicInteger(1);

                                @Override
                                public Thread newThread(Runnable r) {
                                    Thread thread = new Thread(r, "TaskScheduler timeoutThread #" + mCount.getAndIncrement());
                                    thread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
                                    return thread;
                                }
                            });
                }
            }
        }
        return mTimeOutExecutor;
    }

    /**
     * 获取单线程线程池
     *
     * @return
     */
    private ExecutorService getSingleThreadExecutor() {
        if (mSingleThreadExecutor == null) {
            synchronized (TaskScheduler.class) {
                if (mSingleThreadExecutor == null) {
                    mSingleThreadExecutor = Executors.newSingleThreadExecutor();
                }
            }
        }
        return mSingleThreadExecutor;
    }

    /**
     * 销毁当前所有线程池
     */
    public void destroy() {
        if (sTaskScheduler != null) {
            if (mParallelExecutor instanceof ExecutorService) {
                ((ExecutorService) mParallelExecutor).shutdown();
                mParallelExecutor = null;
            }
            if (mTimeOutExecutor != null) {
                mTimeOutExecutor.shutdown();
                mTimeOutExecutor = null;
            }
            if (mSingleThreadExecutor != null) {
                mSingleThreadExecutor.shutdown();
                mSingleThreadExecutor = null;
            }
        }
        sTaskScheduler = null;
    }

    /**
     * 线程池并行执行任务
     **/
    public static void execute(Runnable runnable) {
        getInstance().mParallelExecutor.execute(runnable);
    }

    /**
     * 线程池并行执行任务
     *
     * @see #execute(Runnable)
     **/
    public static <R> void execute(Task<R> task) {
        getInstance().mParallelExecutor.execute(task);
    }

    /**
     * 入队，单线程执行队列任务
     *
     * @param runnable
     */
    public static void enqueue(Runnable runnable) {
        getInstance().getSingleThreadExecutor().execute(runnable);
    }

    /**
     * 入队，单线程执行队列任务
     *
     * @param task
     */
    public static <R> void enqueue(Task<R> task) {
        getInstance().getSingleThreadExecutor().execute(task);
    }

    /**
     * 取消一个任务
     *
     * @param task 被取消的任务
     */
    public static void cancelTask(Task task) {
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 使用一个单独的线程池来执行超时任务，避免引起他线程不够用导致超时
     *
     * @param timeoutMillis 超时时间，单位毫秒
     *                      * 通过实现error(Exception) 判断是否为 TimeoutException 来判断是否超时,
     *                      不能100%保证实际的超时时间就是timeOutMillis，但一般没必要那么精确
     */
    public static <R> void executeTimeoutTask(final long timeoutMillis, final Task<R> task) {
        final Future future = getInstance().getTimeOutExecutor().submit(task);
        getInstance().getTimeOutExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    future.get(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!task.isCanceled()) {
                                task.cancel();
                            }
                        }
                    });
                }
            }
        });
    }

    /**
     * 在ui线程执行任务
     *
     * @param runnable
     */
    public static void runOnUiThread(Runnable runnable) {
        runOnUiThread(runnable, 0);
    }

    /**
     * 延时再ui线程执行
     *
     * @param runnable
     * @param delay
     */
    public static void runOnUiThread(Runnable runnable, long delay) {
        getInstance().mMainHandler.postDelayed(runnable, delay);
    }

    /**
     * 移除在ui线程的任务
     *
     * @param runnable
     */
    public static void removeUiThread(Runnable runnable) {
        removeHandlerCallback("main", runnable);
    }

    /**
     * 获取主线程
     *
     * @return
     */
    public static Handler getMainHandler() {
        return getInstance().mMainHandler;
    }

    /**
     * 当前是否为主线程
     *
     * @return
     */
    public static boolean isMainThread() {
        return Thread.currentThread() == getInstance().mMainHandler.getLooper().getThread();
    }

    /**
     * 获取回调到handlerName线程的handler.一般用于在一个后台线程执行同一种任务，避免线程安全问题。如数据库，文件操作
     *
     * @param handlerName 线程名
     * @return 异步任务handler
     */
    public static Handler provideHandler(String handlerName) {
        if (getInstance().mHandlerMap.containsKey(handlerName)) {
            return getInstance().mHandlerMap.get(handlerName);
        }
        HandlerThread handlerThread = new HandlerThread(handlerName, Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        Handler handler = new SafeDispatchHandler(handlerThread.getLooper());
        getInstance().mHandlerMap.put(handlerName, handler);
        return handler;
    }

    /**
     * 移除handler的callback
     *
     * @param threadName
     * @param runnable
     */
    public static void removeHandlerCallback(String threadName, Runnable runnable) {
        if (isMainThread() || "main".equals(threadName)) {
            getInstance().mMainHandler.removeCallbacks(runnable);
        } else if (getInstance().mHandlerMap.get(threadName) != null) {
            Handler handler = getInstance().mHandlerMap.get(threadName);
            if (handler != null) {
                handler.removeCallbacks(runnable);
            }
        }
    }

    public static void setDebugPrint(boolean bool) {
        ((SafeDispatchHandler) getMainHandler()).setCatchException(bool);
    }
}
