package jp.imlab.visphoto;

import com.theta360.clouduploadv2.httpserver.AndroidWebServer;

import android.content.Intent;
import android.app.IntentService;
import android.util.Log;

public class FileUploadIntentService extends IntentService {

    private AndroidWebServer webUI;

    public FileUploadIntentService(AndroidWebServer webUI) {
        super("FileUploadIntentService");

        this.webUI = webUI;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("debug", "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d("debug", "onStartCommand");

        return super.onStartCommand(intent,flags,startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
//            final String action = intent.getAction();
//            if (ACTION_FOO.equals(action)) {
//                final String param1 = intent.getStringExtra(EXTRA_PARAM1);
//                final String param2 = intent.getStringExtra(EXTRA_PARAM2);
//                handleActionFoo(param1, param2);
//            } else if (ACTION_BAZ.equals(action)) {
//                final String param1 = intent.getStringExtra(EXTRA_PARAM1);
//                final String param2 = intent.getStringExtra(EXTRA_PARAM2);
//                handleActionBaz(param1, param2);
//            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d("debug", "onDestroy");

        super.onDestroy();
    }
};
