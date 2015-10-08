package com.mixpanel.example.hello;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * This class serves as an example of how session tracking may be done on Android. The length of a session
 * is defined as the time between a call to startSession() and a call to endSession() after which there is
 * not another call to startSession() for at least 15 seconds. If a session has been started and
 * another startSession() function is called, it is a no op.
 * <p/>
 * This class is not officially supported by Mixpanel, and you may need to modify it for your own application.
 * <p/>
 * Example Usage:
 * <p/>
 * <pre>
 * {@code
 *  public class MainActivity extends ActionBarActivity {
 *      @Override
 *      protected void onCreate(Bundle savedInstanceState) {
 *          super.onCreate(savedInstanceState);
 *          setContentView(R.layout.activity_main);
 *
 *          mSessionManager = SessionManager.getInstance(this, new SessionManager.SessionCompleteCallback() {
 *              @Override
 *              public void onSessionComplete(SessionManager.Session session) {
 *                  // You may send the session time to Mixpanel in here.
 *                  Log.d("MY APP", "session " + session.getUuid() + " lasted for " +
 *                                  session.getSessionLength()/1000 + " seconds and is now closed");
 *              }
 *          });
 *          mSessionManager.startSession();
 *      }
 *
 *      @Override
 *      public void onResume() {
 *          super.onResume();
 *          mSessionManager.startSession();
 *      }
 *
 *      @Override
 *      public void onPause() {
 *          super.onPause();
 *          mSessionManager.endSession();
 *      }
 *
 *      private SessionManager mSessionManager;
 *  }
 * }
 * </pre>
 */
public class SessionManager {
    private static String LOGTAG = "SessionManager";
    private static String SESSIONS_FILE_NAME = "user_sessions";
    private static final int MESSAGE_INIT = 0;
    private static final int MESSAGE_START_SESSION = 1;
    private static final int MESSAGE_END_SESSION = 2;

    private static SessionManager sInstance;
    private static final Object[] SESSIONS_LOCK = new Object[0];
    private List<Session> mSessions = new ArrayList<>();
    private Session mCurSession;
    private Session mPrevSession;
    private SessionHandler mSessionHandler;
    private Context mAppContext;
    private Thread mSessionCompleterThread;
    private final SessionCompleteCallback mSessionCompleteCallback;

    /**
     * Instantiate a new SessionManager object
     *
     * @param context
     * @param callback
     */
    private SessionManager(Context context, SessionCompleteCallback callback) {
        mAppContext = context.getApplicationContext();
        mSessionCompleteCallback = callback; // this will be called any time a session is complete
        HandlerThread handlerThread = new HandlerThread(getClass().getCanonicalName());
        handlerThread.start();
        mSessionHandler = new SessionHandler(this, handlerThread.getLooper());
        mSessionHandler.sendEmptyMessage(MESSAGE_INIT);
    }

    /**
     * Get the SessionManager singleton object, create on if one doesn't exist
     *
     * @param context
     * @param callback
     * @return
     */
    public static SessionManager getInstance(Context context, SessionCompleteCallback callback) {
        if (sInstance == null) {
            sInstance = new SessionManager(context, callback);
        }
        return sInstance;
    }

    /**
     * Dispatch request to handler thread to start a session
     */
    public void startSession() {
        mSessionHandler.sendEmptyMessage(MESSAGE_START_SESSION);
    }


    /**
     * Dispatch request to handler thread to end a session
     */
    public void endSession() {
        mSessionHandler.sendEmptyMessage(MESSAGE_END_SESSION);
    }

    /**
     * Called by the handler thread, this will resume the previous session if it ended within
     * the given threshold otherwise it'll create a new session. If a session already exists,
     * it will be a noop.
     */
    private void _startSession() {
        if (mCurSession == null) {
            if (mPrevSession != null && !mPrevSession.isExpired()) {
                Log.d(LOGTAG, "resuming session " + mPrevSession.getUuid());
                mCurSession = mPrevSession;
                mCurSession.resume();
                mPrevSession = null;
            } else {
                mCurSession = new Session();
                Log.d(LOGTAG, "creating new session " + mCurSession.getUuid());
                synchronized (SESSIONS_LOCK) {
                    mSessions.add(mCurSession);
                    writeSessionsToFile();
                    initSessionCompleter();
                }
            }
        }
    }

    /**
     * Takes the current session, sets the end time, and sets it as the previous session.
     */
    private void _endSession() {
        if (mCurSession != null) {
            mCurSession.end();
            mPrevSession = mCurSession;
            mCurSession = null;
        }
    }

    /**
     * Spawns a thread to monitor for sessions that need to be completed (ended sessions that are
     * guaranteed not to be resumed). If one is already running, this will be a noop.
     */
    private void initSessionCompleter() {
        if (mSessionCompleterThread == null || !mSessionCompleterThread.isAlive()) {
            mSessionCompleterThread = new Thread() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            completeExpiredSessions();
                            sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        Log.e(LOGTAG, "expiration watcher thread interrupted", e);
                    }
                }

                private void completeExpiredSessions() {
                    Log.d(LOGTAG, "checking for expired sessions...");
                    synchronized (SESSIONS_LOCK) {
                        Iterator<Session> iterator = mSessions.iterator();
                        while (iterator.hasNext()) {
                            Session session = iterator.next();
                            if (session.isExpired()) {
                                Log.d(LOGTAG, "expiring session id " + session.getUuid());
                                iterator.remove();
                                writeSessionsToFile();
                                mSessionCompleteCallback.onSessionComplete(session);
                            } else {
                                Log.d(LOGTAG, "session id " + session.getUuid() + " not yet expired...");
                            }
                        }

                    }
                }
            };
            mSessionCompleterThread.start();
        }
    }

    /**
     * Loads any previously non-completed sessions from local disk. This is necessary to guarantee
     * that sessions are eventually completed when an app is hard-killed or crashes
     */
    private void loadSessionsFromFile() {
        try {
            FileInputStream fis = mAppContext.openFileInput(SESSIONS_FILE_NAME);
            InputStreamReader isr = new InputStreamReader(fis);
            BufferedReader bufferedReader = new BufferedReader(isr);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray sessionsJson = new JSONArray(sb.toString());

            synchronized (SESSIONS_LOCK) {
                for (int i = 0; i < sessionsJson.length(); i++) {
                    JSONObject sessionsObj = sessionsJson.getJSONObject(i);
                    Session session = new Session(sessionsObj);

                    // if there are sessions that don't have an end time we must assume that the
                    // app was killed mid session so we'll just send now as the end time. The better
                    // solution would be to periodically mark a "lastAccessTime" that can be used
                    // in such a case.
                    if (session.getEndTime() == null) {
                        session.end();
                    }

                    mSessions.add(session);
                }
                if (mSessions.size() > 0) {
                    initSessionCompleter();
                }
            }
        } catch (FileNotFoundException e) {
            Log.e(LOGTAG, "Could not find sessions file", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "Could not read from sessions file", e);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Could not serialize json string from file", e);
        }
    }

    /**
     * Writes the current sessions list to local disk. This is so we have a persistent snapshot
     * of non-completed sessions that can be reloaded in case of app shutdown / crash.
     */
    private void writeSessionsToFile() {
        try {
            FileOutputStream fos = mAppContext.openFileOutput(SESSIONS_FILE_NAME, Context.MODE_PRIVATE);
            JSONArray jsonArray = new JSONArray();
            for (Session session : mSessions) {
                jsonArray.put(session.toJSON());
            }
            fos.write(jsonArray.toString().getBytes());
            fos.close();
        } catch (FileNotFoundException e) {
            Log.e(LOGTAG, "Could not find sessions file", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "Could not write to sessions file", e);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Could not turn session to JSON", e);
        }
    }

    public class Session {
        private String mUuid;
        private Long mStartTime;
        private Long mEndTime;
        private Long mSessionExpirationGracePeriod = 15000L;

        public Session() {
            mUuid = UUID.randomUUID().toString();
            mStartTime = System.currentTimeMillis();
        }

        public Session(JSONObject jsonObject) throws JSONException {
            mUuid = jsonObject.getString("uuid");
            mStartTime = jsonObject.getLong("startTime");
            if (jsonObject.has("endTime")) {
                mEndTime = jsonObject.getLong("endTime");
            }
            mSessionExpirationGracePeriod = jsonObject.getLong("sessionExpirationGracePeriod");
        }

        public JSONObject toJSON() throws JSONException {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("uuid", mUuid);
            jsonObject.put("startTime", mStartTime);
            jsonObject.put("endTime", mEndTime);
            jsonObject.put("sessionExpirationGracePeriod", mSessionExpirationGracePeriod);
            return jsonObject;
        }

        public void resume() {
            mEndTime = null;
        }

        public void end() {
            mEndTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return mEndTime != null && System.currentTimeMillis() > mEndTime + mSessionExpirationGracePeriod;
        }

        public Long getSessionLength() {
            if (mEndTime != null) {
                return mEndTime - mStartTime;
            } else {
                return System.currentTimeMillis() - mStartTime;
            }
        }

        public String getUuid() {
            return mUuid;
        }

        public Long getStartTime() {
            return mStartTime;
        }

        public Long getEndTime() {
            return mEndTime;
        }

        public Long getSessionExpirationGracePeriod() {
            return mSessionExpirationGracePeriod;
        }
    }

    public interface SessionCompleteCallback {
        void onSessionComplete(Session session);
    }

    /**
     * Handler thread responsible for all session interaction
     */
    public class SessionHandler extends Handler {
        private SessionManager mSessionManager;

        public SessionHandler(SessionManager sessionManager, Looper looper) {
            super(looper);
            mSessionManager = sessionManager;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_INIT:
                    mSessionManager.loadSessionsFromFile();
                    break;
                case MESSAGE_START_SESSION:
                    mSessionManager._startSession();
                    break;
                case MESSAGE_END_SESSION:
                    mSessionManager._endSession();
                    break;
            }
        }
    }
}
