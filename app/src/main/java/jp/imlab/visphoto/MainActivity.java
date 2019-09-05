/**
 * Copyright 2018 Ricoh Company, Ltd.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.imlab.visphoto;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.icu.text.DateFormat;
import android.icu.text.SimpleDateFormat;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
// taking picture
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.theta360.automaticfaceblur.network.model.commands.CommandsName;
import com.theta360.automaticfaceblur.network.model.objects.ProgressObject;
import com.theta360.automaticfaceblur.network.model.requests.CommandsRequest;
import com.theta360.automaticfaceblur.network.model.responses.CommandsResponse;
import com.theta360.automaticfaceblur.network.model.values.Errors;
import com.theta360.automaticfaceblur.network.model.values.State;
import com.theta360.automaticfaceblur.task.TakePictureTask;
import com.theta360.automaticfaceblur.task.TakePictureTask.Callback;
// for recording
//import android.media.AudioAttributes;
import android.media.AudioManager;
//import android.media.MediaPlayer;
//import android.media.MediaPlayer.OnCompletionListener;
//import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaRecorder;
import android.media.MediaRecorder.AudioEncoder;
import android.media.MediaRecorder.AudioSource;
import android.media.MediaRecorder.OutputFormat;
// for File cloud upload V2
import jp.imlab.visphoto.R;
import com.theta360.clouduploadv2.ShutDownTimer;
import com.theta360.clouduploadv2.ShutDownTimerCallBack;
import com.theta360.clouduploadv2.Util.LogUtilDebugTree;
import com.theta360.clouduploadv2.httpserver.AndroidWebServer;
import com.theta360.clouduploadv2.httpserver.ErrorType;
import com.theta360.clouduploadv2.httpserver.PhotoInformation;
import com.theta360.clouduploadv2.httpserver.Theta360SQLiteOpenHelper;
import com.theta360.clouduploadv2.receiver.ChangeLedReceiver;
import com.theta360.clouduploadv2.receiver.DeleteFileReceiver;
import com.theta360.clouduploadv2.receiver.FinishApplicationReceiver;
import com.theta360.clouduploadv2.receiver.SpecifiedResultReceiver;
import com.theta360.clouduploadv2.receiver.UploadStatusReceiver;
import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedColor;
import com.theta360.pluginlibrary.values.LedTarget;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

public class MainActivity extends PluginActivity {
    private final DateFormat dateTimeFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");

    // Sound Play
    public static SoundPlay splay;
    private static MainActivity instance = null;

    // for taking picture
    private TakePictureTask mTakePictureTask;
    private boolean isPictureReady = false;
    public static final String DCIM = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DCIM).getPath();
    String pictureFileName;

    // for recording
//    private static boolean isReady = false;
    private boolean isRecording = false;
    private MediaRecorder mediaRecorder;
    private String soundFileName;
    private long startTimeMillis;
    private long stopTimeMillis;

    // File cloud upload V2
    private final int LOG_DELETE_ELAPSED_DAYS = 30;
    private Context con;
    private AndroidWebServer webUI;
    private int noOperationTimeoutMSec = AndroidWebServer.TIMEOUT_DEFAULT_MINUTE * 60 * 1000;
    private ShutDownTimer shutDownTimer;
    private SettingPolling settingPolling;
    private ExecutorService shutDownTimerService = null;
    private ExecutorService settingPollingService = null;
    private static Object lock;

    public static MainActivity getInstance() {
        return instance;
    }

//    public static void setIsReady(boolean bool) {
//        isReady = bool;
//    }

    private ChangeLedReceiver mChangeLedReceiver;
    private ChangeLedReceiver.Callback onChangeLedReceiver = new ChangeLedReceiver.Callback() {
        @Override
        public void callStartupCallback() {
            changeStartupLED();
        }

        @Override
        public void callReadyCallback() {
            changeReadyLED();
        }

        @Override
        public void callTransferringCallback() {
            changeTransferringLED();
        }

        @Override
        public void callStopTransferringCallback() {
            changeStopTransferringLED();
        }

        @Override
        public void callTransferringStatusCallback(int currentNumber, int allNumber) {
            changeTransferringStatusOLED(currentNumber, allNumber);
        }

        @Override
        public void callErrorCallback(String errorCode) {
            playPPPSoundWithErrorLED(errorCode);
        }
    };

    private UploadStatusReceiver mUploadStatusReceiver;
    private UploadStatusReceiver.Callback onUploadStatusReceiver = new UploadStatusReceiver.Callback() {
        @Override
        public void callStartCallback() {
            shutDownTimer.reset(true, noOperationTimeoutMSec);
        }

        @Override
        public void callEndCallback() {
            shutDownTimer.reset(false, noOperationTimeoutMSec);
        }
    };

    private SpecifiedResultReceiver mSpecifiedResultReceiver;
    private SpecifiedResultReceiver.Callback onSpecifiedResultReceiver = new SpecifiedResultReceiver.Callback() {
        @Override
        public void callSpecifiedResultCallback(String result) {
            Intent data = new Intent();
            Bundle bundle = new Bundle();
            ErrorType errorType = ErrorType.getType(result);
            bundle.putInt("ResultCode", errorType.getCode());
            bundle.putString("ResultMessage", errorType.getMessage());
            data.putExtras(bundle);
            setResult(RESULT_OK, data);
            finish();
        }
    };

    private DeleteFileReceiver mDeleteFileReceiver;
    private DeleteFileReceiver.Callback onDeleteFileReceiver = new DeleteFileReceiver.Callback() {
        @Override
        public void callDeleteFileCallback(String[] targets) {
            notificationDatabaseUpdate(targets);
        }
    };

    private FinishApplicationReceiver mFinishApplicationReceiver;
    private FinishApplicationReceiver.Callback onFinishApplicationReceiver = new FinishApplicationReceiver.Callback() {
        @Override
        public void callFinishApplicationCallback() {
            exitProcess();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Sound Play
        instance = this;
        splay = new SoundPlay();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> splay.playSound(splay.soundVisphotoIsShuttingDown)
        ));

        // recording
        stopTimeMillis = 0;

        // File cloud upload V2
        InitFileCloudUploadV2();

        setKeyCallback(new KeyCallback() {
            /**
             * Receive the shutter key down when it is not during taking picture task or
             * processing image task.
             * @param keyCode code of key
             * @param keyEvent event of key
             */
            @Override
            public void onKeyDown(int keyCode, KeyEvent keyEvent) {
                if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
//                    if (isReady) {
                        // 最後の録音停止から1秒未満の場合は録音開始ができない。
                        long currentTimeMillis = System.currentTimeMillis();
                        long intervalLength = currentTimeMillis - stopTimeMillis;
                        if (mTakePictureTask == null && !isRecording && intervalLength >= 2000) {
                            // recording
                            startRecorder();
                            // taking picture
                            takePicture();
                        } else {
                            // error
                            notificationAudioWarning();
                        }
                    }
//                }
            }

            @Override
            public void onKeyUp(int keyCode, KeyEvent keyEvent) {
                if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
                    // recording
//                    if (isReady && isRecording) {
                    if (isRecording) {
                        long currentTimeMillis = System.currentTimeMillis();
                        long recordLength = currentTimeMillis - startTimeMillis;
                        // 録音時間が5秒未満の場合は、最低でも5秒になるように、録音停止を遅延実行する。
                        if (recordLength < 5000) {
                            // 録音開始から5秒後に処理を実行する
                            new Handler().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    // TODO: ここで処理を実行する
                                    stopRecorder();
                                }
                            }, 5000 - recordLength);
                        } else {
                            stopRecorder();
                        }
                    }
                    // 録音中でなければ、何もしない。
                }
            }

            @Override
            public void onKeyLongPress(int keyCode, KeyEvent event) {
            }
        });

        notificationAudioSelf();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // WIFI connection (CL mode)
        notificationWlanCl();

        // File cloud upload V2
        mChangeLedReceiver = new ChangeLedReceiver(onChangeLedReceiver);
        IntentFilter changeLedFilter = new IntentFilter();
        changeLedFilter.addAction(ChangeLedReceiver.CHANGE_STARTUP_LED);
        changeLedFilter.addAction(ChangeLedReceiver.CHANGE_READY_LED);
        changeLedFilter.addAction(ChangeLedReceiver.CHANGE_TRANSFERRING_LED);
        changeLedFilter.addAction(ChangeLedReceiver.CHANGE_STOP_TRANSFERRING_LED);
        changeLedFilter.addAction(ChangeLedReceiver.CHANGE_TRANSFERRING_STATUS_LED);
        changeLedFilter.addAction(ChangeLedReceiver.CHANGE_ERROR_LED);
        registerReceiver(mChangeLedReceiver, changeLedFilter);

        mUploadStatusReceiver = new UploadStatusReceiver(onUploadStatusReceiver);
        IntentFilter uploadStatusFilter = new IntentFilter();
        uploadStatusFilter.addAction(UploadStatusReceiver.UPLOAD_START);
        uploadStatusFilter.addAction(UploadStatusReceiver.UPLOAD_END);
        registerReceiver(mUploadStatusReceiver, uploadStatusFilter);

        mSpecifiedResultReceiver = new SpecifiedResultReceiver(onSpecifiedResultReceiver);
        IntentFilter specifiedResultFilter = new IntentFilter();
        specifiedResultFilter.addAction(SpecifiedResultReceiver.SPECIFIED_RESULT);
        registerReceiver(mSpecifiedResultReceiver, specifiedResultFilter);

        mDeleteFileReceiver = new DeleteFileReceiver(onDeleteFileReceiver);
        IntentFilter deleteFileFilter = new IntentFilter();
        deleteFileFilter.addAction(DeleteFileReceiver.DELETE_FILE);
        registerReceiver(mDeleteFileReceiver, deleteFileFilter);

        mFinishApplicationReceiver = new FinishApplicationReceiver(onFinishApplicationReceiver);
        IntentFilter finishApplicationFilter = new IntentFilter();
        finishApplicationFilter.addAction(FinishApplicationReceiver.FINISH_APPLICATION);
        registerReceiver(mFinishApplicationReceiver, finishApplicationFilter);

        // Intent from other plugins
        Intent intent = getIntent();
        if (intent != null) {
            List<String> photoList = intent.getStringArrayListExtra("com.theta360.clouduploadv2.photoList");
            if (photoList != null && photoList.size() > 0) {
                webUI.uploadSpecifiedPhotoList(photoList);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // File cloud upload V2
        createShutDownTimer();
        createSettingPolling();
        webUI.createUploadProcess();
    }


    @Override
    protected void onPause() {
        super.onPause();

        // taking picture
        if (mTakePictureTask != null) {
            mTakePictureTask.cancel(true);
            mTakePictureTask = null;
        }

        // for recording
        releaseMediaRecorder();

        // File cloud upload V2
        unregisterReceiver(mChangeLedReceiver);
        unregisterReceiver(mUploadStatusReceiver);
        unregisterReceiver(mSpecifiedResultReceiver);
        unregisterReceiver(mDeleteFileReceiver);
        unregisterReceiver(mFinishApplicationReceiver);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // File cloud upload V2
        if (shutDownTimer != null) {
            shutDownTimer.exit();
        }
        if (settingPolling != null) {
            settingPolling.exit();
        }
        webUI.exitUpload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webUI.destroy();
    }

    /**
     * TakePictureTask Callback.
     */
    TakePictureTask.Callback mTakePictureTaskCallback = new Callback() {
        @Override
        public void onPreExecute() {
            setAutoClose(false);
        }

        @Override
        public void onPictureGenerated(String pictureUrl) {
            if (!TextUtils.isEmpty(pictureUrl)) {
//                notificationAudioOpen();
                if (isZ1()) {
//                    notificationOledDisplaySet(Display.PLUGIN);
//                    notificationOledTextShow("Processing", "");
                } else {
                    notificationLedHide(LedTarget.LED4);  // Camera
//                    notificationLedBlink(LedTarget.LED4, LedColor.BLUE, 1000);
                }
                isPictureReady = true;

                // rename picture file
                String soundFileBaseName = new File(soundFileName).getName();
                Log.d("Basename of soundFileName", soundFileBaseName);
                String pictureFileBaseName = soundFileBaseName.replace(".wav", ".JPG");
                Matcher matcher = Pattern.compile("/\\d{3}RICOH.*").matcher(pictureUrl);
                if (matcher.find()) {
                    String pictureFileName_DCIM = DCIM + matcher.group();
                    Log.d("pictureFileName_DCIM", pictureFileName_DCIM);

//                    File file_DCIM = new File(pictureFileName_DCIM);
//                    if (file_DCIM.exists()) {
//                        Log.d("file_DCIM.exists()", "true");
//                    } else {
//                        Log.d("file_DCIM.exists()", "false");
//                    }
//                    Log.d("AAAAAAAAA)", "AAAAA");

                    // rename
                    File pictureFile_DCIM = new File(pictureFileName_DCIM);
                    String basename = pictureFile_DCIM.getName();
                    Log.d("Basename of pictureFileName_DCIM", basename);
                    pictureFileName = pictureFileName_DCIM.replace(basename, pictureFileBaseName);
                    Log.d("pictureFileName", pictureFileName);
                    File pictureFile = new File(pictureFileName);
                    boolean isSuccess = pictureFile_DCIM.renameTo(pictureFile);
                    if (isSuccess) {
                        Log.d("isSuccess", "true");
                    } else {
                        Log.d("isSuccess", "false");
                    }

                }

                // Upload the picture file
//                uploadSingleFile(pictureFileName);
            } else {
                notificationError(getResources().getString(R.string.take_picture_error));
            }
            mTakePictureTask = null;
        }

        @Override
        public void onSendCommand(AsyncHttpServerResponse response, CommandsRequest commandsRequest,
                                  Errors errors) {
        }

//        @Override
//        public void onSendCommand(AsyncHttpServerResponse response, CommandsRequest commandsRequest,
//                                  Errors errors) {
//            if (mWebServer != null && response != null && commandsRequest != null) {
//                CommandsName commandsName = commandsRequest.getCommandsName();
//                if (errors == null) {
//                    CommandsResponse commandsResponse = new CommandsResponse(commandsName,
//                            State.IN_PROGRESS);
//                    commandsResponse.setProgress(new ProgressObject(0.00));
//                    mWebServer.sendCommandsResponse(response, commandsResponse);
//                } else {
//                    mWebServer.sendError(response, errors, commandsName);
//                }
//            }
//            if (errors != null) {
//                notificationError(errors.getMessage());
//            }
//        }

        @Override
        public void onCompleted() {
            setAutoClose(true);
        }

        @Override
        public void onTakePictureFailed() {
            notificationError(getResources().getString(R.string.error));
            setAutoClose(true);
        }
    };

    private void InitFileCloudUploadV2() {
        lock = new Object();
        con = getApplicationContext();

        // Initialize log
        //        DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        String logFileDirPath = con.getFilesDir().getAbsolutePath();
        long currentTimeMillis = System.currentTimeMillis();
        String logFileName = String.format("app_%s.log", dateTimeFormat.format(new Date(currentTimeMillis)));
        Timber.plant(new LogUtilDebugTree(logFileDirPath, logFileName));
        // Fill the log header
        Timber.i("\n\n*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*\n"
                + "*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*\n"
                + "\n    logging start ... " + dateTimeFormat.format(new Date(System.currentTimeMillis()))
                + "\n\n");

        // Delete logs after a certain number of days
        long logDeleteElapsedMillis = currentTimeMillis - LOG_DELETE_ELAPSED_DAYS * (1000L * 60L * 60L * 24L);
        FileFilter fileFilter = new FileFilter() {
            @Override
            public boolean accept(File file) {
                if (!(file.getName().matches("app_\\d{8}.log"))) {
                    return false;
                }
                return file.lastModified() <= logDeleteElapsedMillis;
            }
        };
        for (File file : new File(logFileDirPath).listFiles(fileFilter)) {
            file.delete();
        }

        // Do not sleep while launching the application
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initial setting of LEDs
        changeStartupLED();

        // Start HTTP server
        webUI = new AndroidWebServer(con);
        if (webUI.getIsReady())
            changeReadyLED();
    }

    private void uploadSingleFile(String fileName) {
        List<String> uploadFileList = new ArrayList<String>();
        uploadFileList.add(fileName);
        webUI.uploadSpecifiedPhotoList(uploadFileList);
    }

    /**
     * Call the shooting application when this plug-in ends.
     */
    @SuppressLint("WrongConstant")
    private void callRecordingApp() {
        con.sendBroadcastAsUser(new Intent("com.theta360.devicelibrary.receiver.ACTION_BOOT_BASIC"), android.os.Process.myUserHandle());
    }

    /**
     * End processing
     */
    private void exitProcess() {
        Timber.i("Application is terminated.");

        // Launch the shooting application (com.theta 360.receptor).
        callRecordingApp();

        // Finish plug-in
        finishAndRemoveTask();
    }

    private void changeStartupLED() {
        if (isZ1()) {
            notificationOledHide();
            notificationOledTextShow(getString(R.string.oled_middle), "");
        } else {
            notificationLedHide(LedTarget.LED4);  // Camera
            notificationLedHide(LedTarget.LED5);  // Video
            notificationLedHide(LedTarget.LED8);  // Error
            notificationLedHide(LedTarget.LED6);  // LIVE
        }
    }

    private void changeReadyLED() {
        if (isZ1()) {
            notificationOledTextShow(getString(R.string.oled_middle), getString(R.string.oled_bottom_ready));
        } else {
            notificationLedHide(LedTarget.LED4);  // Camera
            notificationLedHide(LedTarget.LED5);  // Video
            notificationLedHide(LedTarget.LED8);  // Error
//            notificationLedShow(LedTarget.LED6);  // LIVE
        }
    }

    private void changeTransferringLED() {
        if (!isZ1()) {
            notificationLedHide(LedTarget.LED5); // Video
            notificationLedHide(LedTarget.LED8); // Error
//            notificationLedBlink(LedTarget.LED4, LedColor.BLUE, 1000);  // Camera
//            notificationLedBlink(LedTarget.LED6, LedColor.BLUE, 1000);  // LIVE
        }
    }

    private void changeStopTransferringLED() {
        if (!isZ1()) {
            notificationLedHide(LedTarget.LED6);  // LIVE
        }
    }

    private void changeTransferringStatusOLED(int currentNumber, int allNumber) {
        if (isZ1()) {
            notificationOledTextShow(getString(R.string.oled_middle), getString(R.string.oled_bottom_transferring) + String.format(" %d/%d", currentNumber, allNumber));
        }
    }

    private void changeErrorLED() {
        notificationLedHide(LedTarget.LED4);  // Camera
        notificationLedHide(LedTarget.LED6);  // LIVE
        notificationLedBlink(LedTarget.LED5, LedColor.BLUE, 1000); // Video
        notificationLedBlink(LedTarget.LED8, LedColor.BLUE, 1000); // Error
    }

    /**
     * PPP(Error) sound playback and error LED control
     */
    private void playPPPSoundWithErrorLED(String errorCode) {
        if (isZ1()) {
            notificationOledTextShow(getString(R.string.oled_middle), getString(R.string.oled_bottom_error) + errorCode);
            notificationErrorOccured();
        } else {
            notificationAudioWarning();
            changeErrorLED();
        }
    }

    /**
     * Create an end monitoring timer
     */
    private void createShutDownTimer() {
        shutDownTimer = new ShutDownTimer(new ShutDownTimerCallBack() {
            @Override
            public void callBack() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exitProcess();
            }
        }, noOperationTimeoutMSec);

        try {
            shutDownTimerService = Executors.newSingleThreadExecutor();
            shutDownTimerService.execute(shutDownTimer);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            shutDownTimerService.shutdown();
        }
    }

    /**
     * Create setting monitoring thread
     */
    private void createSettingPolling() {

        // Create setting monitoring class
        settingPolling = new SettingPolling();

        try {
            settingPollingService = Executors.newSingleThreadExecutor();
            settingPollingService.execute(settingPolling);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            settingPollingService.shutdown();
        }
    }


    /**
     * Inner class for monitoring setting status
     */
    private class SettingPolling implements Runnable {

        // Confirmation interval. Unit millisecond
        private static final long CHECK_INTERVAL_MSEC = 1000;
        private Boolean isExit;
        private Boolean isStop;

        public SettingPolling() {
            isExit = false;
            isStop = false;
        }

        /**
         * End thread
         */
        public void exit() {
            isExit = true;
        }


        /**
         * Start monitoring
         */
        public void changeStart() {
            isStop = false;
        }

        /**
         * End monitoring
         */
        public void changeStop() {
            isStop = true;
        }


        @Override
        public void run() {

            Boolean first = true;
            while (!isExit) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MSEC);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (isStop) {
                    continue;
                }

                if (webUI.isRequested() || first) {
                    webUI.clearRequested();

                    if (first) {
                        first = false;
                    }

                    synchronized (lock) {
                        // Check setting
                        Theta360SQLiteOpenHelper hlpr = new Theta360SQLiteOpenHelper(con);
                        SQLiteDatabase dbObject = hlpr.getWritableDatabase();
                        Cursor cursor = dbObject.query("theta360_setting", null, null, null, null, null, null, null);

                        try {
                            if (cursor.moveToNext()) {
                                noOperationTimeoutMSec = cursor.getInt(cursor.getColumnIndex("no_operation_timeout_minute")) * 60 * 1000;
                                Timber.d("noOperationTimeoutMSec : " + noOperationTimeoutMSec);

                            } else {
                                // Create new record if DB is empty.
                                ContentValues values = new ContentValues();
                                values.put("no_operation_timeout_minute", AndroidWebServer.TIMEOUT_DEFAULT_MINUTE);
                                values.put("status", "");

                                long num = dbObject.insert("theta360_setting", null, values);
                                if (num != 1) {
                                    throw new SQLiteException("[setting data] initialize database error");
                                }
                            }
                            // Reset auto stop timer
                            if (!shutDownTimer.getIsUploading()) {
                                shutDownTimer.reset(false, noOperationTimeoutMSec);
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                            throw new SQLiteException("[setting data] Unexpected exception");
                        } finally {
                            cursor.close();
                            dbObject.close();
                        }
                    }
                }
            }
        }
    }

    /**
     * for recording
     */
    private void startRecorder() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                // TODO: ここで処理を実行する
                splay.playSound(splay.soundStartRecording);
            }
        }, 100);
        startTimeMillis = System.currentTimeMillis();
        soundFileName = getFilesDir() + File.separator + String.format("%s.wav", dateTimeFormat.format(new Date(startTimeMillis)));
//        pictureFileName = soundFilePath.replace(".wav", ".jpg");
        Log.d("soundFileName", soundFileName);
//        Log.d("pictureFileName", pictureFileName);
        new MediaRecorderPrepareTask().execute();
        notificationLedBlink(LedTarget.LED7, LedColor.RED, 2000);  // 動画記録ランプ
    }

    private void stopRecorder() {
        try {
            mediaRecorder.stop();

            splay.playSound(splay.soundStopRecording);
        } catch (RuntimeException e) {
            splay.playSound(splay.soundRecordingFailed);
            Log.d("Recorder", "RuntimeException: stop() is called immediately after start()");
            deleteSoundFile();
        } finally {
            releaseMediaRecorder();
            isRecording = false;
            stopTimeMillis = System.currentTimeMillis();
            notificationAudioMovStop();
            notificationLedHide(LedTarget.LED7);  // 動画記録ランプ
        }
        Log.d("Recorder", "Stop");

        // Upload the sound file
        uploadSingleFile(soundFileName);

    }

    private void takePicture() {
        mTakePictureTask = new TakePictureTask(mTakePictureTaskCallback, null,
                null);
        mTakePictureTask.execute();
        notificationLedBlink(LedTarget.LED4, LedColor.BLUE, 1000);  // Camera
        //                        notificationLedBlink(LedTarget.LED4, LedColor.BLUE, 2000);  // Camera
        //                        notificationAudioShutter();
        notificationAudioMovStart();
    }

    private boolean prepareMediaRecorder() {
        Log.d("Recorder", soundFileName);
        deleteSoundFile();

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setParameters("RicUseBFormat=false");

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(AudioSource.MIC);
        mediaRecorder.setAudioSamplingRate(44100); // 2019/1/21追記
        mediaRecorder.setOutputFormat(OutputFormat.DEFAULT);
        mediaRecorder.setAudioEncoder(AudioEncoder.DEFAULT);
        mediaRecorder.setOutputFile(soundFileName);

        try {
            mediaRecorder.prepare();
        } catch (Exception e) {
            Log.e("Recorder", "Exception preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }

        return true;
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void deleteSoundFile() {
        File file = new File(soundFileName);
        if (file.exists()) {
            file.delete();
        }
        file = null;
    }

    /**
     * Asynchronous task for preparing the {@link android.media.MediaRecorder} since it's a long
     * blocking operation.
     */
    private class MediaRecorderPrepareTask extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (prepareMediaRecorder()) {
                mediaRecorder.start();
                isRecording = true;
                return true;
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (!result) {
                Log.e("Recorder", "MediaRecorder prepare failed");
                notificationError("");
                return;
            }
            Log.d("Recorder", "onPostExecute");
        }
    }
}
