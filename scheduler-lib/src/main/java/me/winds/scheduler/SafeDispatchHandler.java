package me.winds.scheduler;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;


/**
 * a safe Handler avoid crash
 */
public class SafeDispatchHandler extends Handler {
    private static final String TAG = "SafeDispatchHandler";
    private boolean isCatchException = true;

    public SafeDispatchHandler(Looper looper) {
        super(looper);
    }

    public SafeDispatchHandler(Looper looper, Callback callback) {
        super(looper, callback);
    }

    public SafeDispatchHandler() {
        super();
    }

    public SafeDispatchHandler(Callback callback) {
        super(callback);
    }

    public void setCatchException(boolean bool) {
        isCatchException = bool;
    }

    @Override
    public void dispatchMessage(Message msg) {
        if(isCatchException) {
            try {
                super.dispatchMessage(msg);
            } catch (Throwable e) {
                Log.e(TAG, getStackTrace(e));
            }
        } else {
            super.dispatchMessage(msg);
        }
    }

    public static String getStackTrace(Throwable tr) {
        if(tr != null) {
            try {
                Throwable t = tr;
                while (t != null) {
                    t = t.getCause();
                }
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                tr.printStackTrace(pw);
                pw.flush();
                return sw.toString();
            } catch (Exception e) {
                return tr.getMessage();
            }
        }
        return "";
    }
}
