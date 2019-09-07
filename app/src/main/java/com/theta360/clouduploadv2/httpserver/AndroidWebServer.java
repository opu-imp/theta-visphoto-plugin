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

package com.theta360.clouduploadv2.httpserver;

import static fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;


import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.icu.text.DateFormat;
import android.media.ExifInterface;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;

import com.google.gson.Gson;
import com.theta360.clouduploadv2.net.UploadPhotoApi;
import com.theta360.clouduploadv2.net.UploadPhotoApiCallback;
import com.theta360.clouduploadv2.net.UploadPhotoApiFactory;
import com.theta360.clouduploadv2.receiver.ChangeLedReceiver;
import com.theta360.clouduploadv2.receiver.FinishApplicationReceiver;
import com.theta360.clouduploadv2.receiver.DeleteFileReceiver;
import com.theta360.clouduploadv2.receiver.SpecifiedResultReceiver;
import com.theta360.clouduploadv2.receiver.UploadStatusReceiver;
import com.theta360.clouduploadv2.settingdata.SettingData;
import com.theta360.clouduploadv2.CameraConnector;

import org.json.JSONException;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;

import timber.log.Timber;

import jp.imlab.visphoto.MainActivity;
import jp.imlab.visphoto.SoundPlay;

/**
 * Provide web server function
 */
public class AndroidWebServer {

    public static final int TIMEOUT_DEFAULT_MINUTE = -1;

    private final String DCIM_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + Environment.DIRECTORY_DCIM + "/";
    private final String PICTURES_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + Environment.DIRECTORY_PICTURES + "/";

    public static final int UPLOAD_TIMEOUT_MSEC = 60000;
    private final int UPLOAD_RETRY_WAIT_MSEC = 30000;
    private final int REFRESH_COUNT_MAX = 3;

    private Theta360SQLiteOpenHelper helper;
    private SQLiteDatabase dbObject;

    private static final int PORT = 8888;
    private SimpleHttpd server;

    private Context con;
    private UploadProcess uploadProcess;

    private final Object lock = new Object();
    private Boolean requested;
    private UploadPhotoApi uploadPhotoApi;
    private boolean isReady = false;
    private String userId = null;
    private String refreshToken = null;
    private String apiType = null;

    private List<PhotoInformation> uploadedPhotoList;
    private List<PhotoInformation> uploadingPhotoList;
    private PhotoInformation uploadingPhoto;
    private Deque<PhotoInformation> uploadingFileQueue;
    private Boolean isSucceedUpload;
    private int errorCode;
    private List<PhotoInformation> specifiedPhotoList;
    private ErrorType errorType;
    private boolean isUploading = false;
    private int uploadAllNumber;
    private int uploadCurrentNumber;
    private boolean isDeleteUploadedFile;

    ExecutorService uploadPushedFilesService;

    public AndroidWebServer(Context context) {
        con = context;
        create();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Create
     */
    @SuppressLint({"SetTextI18n", "LongLogTag"})
    public void create() {

        Log.d("AndroidWebServerActivity", "onCreate");
        WifiManager wifiManager = (WifiManager) con.getSystemService(Context.WIFI_SERVICE);

        assert wifiManager != null;
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        @SuppressLint("DefaultLocale") final String formattedIpAddress = String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));

        Timber.i("Launch server with IP [" + formattedIpAddress + "].");

        try {
            server = new SimpleHttpd();
            server.start();
            Log.i("AndroidWebServerActivity", "Start server");
        } catch (Exception e) {
            e.printStackTrace();
        }

        helper = new Theta360SQLiteOpenHelper(con);

        clearRequested();

        dbObject = helper.getWritableDatabase();
        uploadedPhotoList = new ArrayList();
        updateUploadInfo();

        uploadingFileQueue = new ArrayDeque<>();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Discard
     */
    public void destroy() {
        if (server != null) {
            server.stop();
//            new Handler().postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    // TODO: ここで処理を実行する
//                    MainActivity.splay.playSound(MainActivity.splay.soundVisphotoIsShuttingDown);
//                }
//            }, 1000);
            Log.i("AndroidWebServerActivity", "Stop server");
            Log.d("errorType", errorType.toString());
        }
    }

    /**
     * Get the flag for receiving a request
     *
     * @return Flag requested
     */
    public Boolean isRequested() {
        return requested;
    }

    /**
     * Clear the flag for receiving a request
     */
    public void clearRequested() {
        requested = false;
    }

    /**
     * Check ready for uploading
     */
    public boolean getIsReady() {
        return isReady;
    }

    /**
     * Execute / stop upload processing
     */
    public void startUpload() {
        if (uploadProcess != null) {
            uploadProcess.start();
        }
    }

    /**
     * End thread of upload processing
     */
    public void exitUpload() {
        if (uploadProcess != null) {
            uploadProcess.exit();
        }
    }

    /**
     * Create thread of upload processing
     */
    public void createUploadProcess() {
        uploadProcess = new UploadProcess();

        ExecutorService uploadProcessService = null;
        try {
            uploadProcessService = Executors.newSingleThreadExecutor();
            uploadProcessService.execute(uploadProcess);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            uploadProcessService.shutdown();
        }
    }

    private class UploadProcess implements Runnable {
        private boolean isStartUpload = false;
        private boolean isExit = false;

        /**
         * Execute / stop upload processing
         */
        public void start() {
            isStartUpload = true;
        }

        /**
         * End thread
         */
        public void exit() {
            isExit = true;
        }

        @Override
        public void run() {
            while (!isExit) {
                if (!isStartUpload) {
                    continue;
                }
                updateUploadInfo();
                if (isReady) {
                    if (server.uploadFileService == null) {
                        int refreshCount = 0;
                        boolean refreshResult = false;
                        while (!refreshResult && refreshCount < REFRESH_COUNT_MAX) {
                            refreshResult = server.hasRefreshToken();
                            refreshCount++;
                        }
                        server.startUploadFile();
                    } else {
                        server.uploadFileService.shutdownNow();
                        server.uploadFileService = null;
                    }
                }
                isStartUpload = false;
            }
        }
    }

    /**
     * Upload specified image by intent
     *
     * @param photoList Upload image list
     */
    public void uploadSpecifiedPhotoList(List<String> photoList) {
        setSpecifiedPhotoList(photoList);
        startUploadSpecifiedPhotoList();
    }

    private void setSpecifiedPhotoList(List<String> photoList) {
        specifiedPhotoList = new ArrayList();
        PhotoInformation photoInformation;
        for (String path : photoList) {
            ExtensionType extensionType = ExtensionType.getType(path);
            if (extensionType == null) {
                continue;
            }
            if (!(new File(path).isFile())) {
                continue;
            }

            try {
                String datetime;
                if (extensionType == ExtensionType.WAV) {
                    File file = new File(path);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                    datetime = sdf.format(file.lastModified());
                } else {
                    ExifInterface exifInterface = new ExifInterface(path);
                    datetime = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
                }
                photoInformation = new PhotoInformation();
                photoInformation.setPath(path);
                photoInformation.setDatetime(datetime);
                specifiedPhotoList.add(photoInformation);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // ***** for uploading single file *****
    public void addUploadingFile(String path) {
//        specifiedPhotoList = new ArrayList();
        PhotoInformation photoInformation;
//        for (String path : photoList) {
            ExtensionType extensionType = ExtensionType.getType(path);
            if (extensionType == null) {
//                continue;
                return;
            }
            if (!(new File(path).isFile())) {
//                continue;
                return;
            }

            try {
                String datetime;
                if (extensionType == ExtensionType.WAV) {
                    File file = new File(path);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                    datetime = sdf.format(file.lastModified());
                } else {
                    ExifInterface exifInterface = new ExifInterface(path);
                    datetime = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
                }
                photoInformation = new PhotoInformation();
                photoInformation.setPath(path);
                photoInformation.setDatetime(datetime);
//                specifiedPhotoList.add(photoInformation);
                uploadingFileQueue.push(photoInformation);
            } catch (IOException e) {
                e.printStackTrace();
            }
//        }
    }

    private void startUploadSpecifiedPhotoList() {
        UploadSpecifiedPhotoList uploadSpecifiedPhotoList = new UploadSpecifiedPhotoList();

        ExecutorService uploadSpecifiedPhotoListService = null;
        try {
            uploadSpecifiedPhotoListService = Executors.newSingleThreadExecutor();
            uploadSpecifiedPhotoListService.execute(uploadSpecifiedPhotoList);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            uploadSpecifiedPhotoListService.shutdown();
        }
    }

    private class UploadSpecifiedPhotoList implements Runnable {
        @Override
        public void run() {
            errorType = ErrorType.SUCCESS;
            updateUploadInfo();
            if (refreshToken == null || refreshToken.isEmpty()) {
                errorType = ErrorType.NOT_SETTINGS;
            } else {
                int refreshCount = 0;
                boolean refreshResult = false;
                while (!refreshResult && refreshCount < REFRESH_COUNT_MAX) {
                    refreshResult = server.hasRefreshToken();
                    refreshCount++;
                }
//                server.hasUploadFile();

                if (!server.hasUploadFile()) {
                    changeErrorLed();
//                    MainActivity.splay.playSound(MainActivity.splay.soundUploadingFailed);
                }

            }
            Intent intent = new Intent(SpecifiedResultReceiver.SPECIFIED_RESULT);
            intent.putExtra(SpecifiedResultReceiver.RESULT, errorType.getType());
            con.sendBroadcast(intent);
        }
    }


    // ***** for uploading single file *****
    public void startUploadPushedFiles() {
        UploadPushedFiles uploadPushedFiles = new UploadPushedFiles();

        uploadPushedFilesService = null;
        try {
            uploadPushedFilesService = Executors.newSingleThreadExecutor();
            uploadPushedFilesService.execute(uploadPushedFiles);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
//            uploadPushedFilesService.shutdown();
        }
    }

    // ***** for uploading single file *****
    public void stopUploadPushedFiles() {
        uploadPushedFilesService.shutdown();
    }

    // ***** for uploading single file *****
    private class UploadPushedFiles implements Runnable {
        @Override
        public void run() {
            while(true) {
                errorType = ErrorType.SUCCESS;
                updateUploadInfo();
                if (refreshToken == null || refreshToken.isEmpty()) {
                    errorType = ErrorType.NOT_SETTINGS;
                } else {
                    int refreshCount = 0;
                    boolean refreshResult = false;
                    while (!refreshResult && refreshCount < REFRESH_COUNT_MAX) {
                        refreshResult = server.hasRefreshToken();
                        refreshCount++;
                    }

                    Log.d("UploadPushedFiles, dequeue size", ""+uploadingFileQueue.size());

                    if (uploadingFileQueue.size()!=0) {
                        PhotoInformation pi = uploadingFileQueue.pop();
                        if (!server.uploadSingleFile(pi)) {
                            changeErrorLed();
//                            MainActivity.splay.playSound(MainActivity.splay.soundUploadingFailed);
                        }
                        Intent intent = new Intent(SpecifiedResultReceiver.SPECIFIED_RESULT);
                        intent.putExtra(SpecifiedResultReceiver.RESULT, errorType.getType());
                        con.sendBroadcast(intent);
                    }
                }

                try {
                    Thread.sleep(1000);
                } catch(InterruptedException e){
                    e.printStackTrace();
                }
            }

        }
    }

    /**
     * Converts an InputStream to a string.
     *
     * @param is Source InputStream
     * @return Converted character string
     */
    private String inputStreamToString(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();

        return sb.toString();
    }

    /**
     * Converts a string to InputStream.
     *
     * @param str Source character string
     * @return Converted InputStream
     */
    private InputStream stringToInputStream(String str) throws UnsupportedEncodingException {
        return new ByteArrayInputStream(str.getBytes("utf-8"));
    }

    private void updateUploadInfo() {
        // Confirm whether the upload destination authentication information is stored in the DB
        Cursor cursor = dbObject.query("auth_information", null, null, null, null, null, null, null);
        try {
            if (cursor.moveToNext()) {
                refreshToken = cursor.getString(cursor.getColumnIndex("refresh_token"));
                userId = cursor.getString(cursor.getColumnIndex("user_id"));
                apiType = cursor.getString(cursor.getColumnIndex("api_type"));
                // Provisional response
                if (apiType.equals(UploadPhotoApiFactory.GOOGLE_PHOTO)) {
                    refreshToken = "";
                    userId = "";
                    apiType = UploadPhotoApiFactory.GOOGLE_DRIVE;
                    ContentValues values = new ContentValues();
                    values.put("refresh_token", refreshToken);
                    values.put("user_id", userId);
                    values.put("api_type", apiType);
                    dbObject.update("auth_information", values, null, null);
                }
                if (!apiType.isEmpty() && (uploadPhotoApi == null || !uploadPhotoApi.getApiType().equals(apiType))) {
                    uploadPhotoApi = UploadPhotoApiFactory.createUploadPhotoApi(con, apiType);
                    // Update list of uploaded photos
                    updateUploadedPhotoList();
                }
            } else {
                // Create a record if there is no record in DB
                ContentValues values;
                values = new ContentValues();
                values.put("refresh_token", "");
                values.put("user_id", "");
                values.put("api_type", UploadPhotoApiFactory.GOOGLE_DRIVE);
                dbObject.insert("auth_information", null, values);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            cursor.close();
        }

        if (refreshToken != null && !refreshToken.isEmpty()) {
            isReady = true;
        } else {
            isReady = false;
        }
    }

    private void updateUploadedPhotoList() {
        Cursor cursor = dbObject.query("uploaded_photo", null, "api_type = ?", new String[]{apiType}, null, null, null, null);
        uploadedPhotoList = new ArrayList();
        try {
            while (cursor.moveToNext()) {
                PhotoInformation uploadedPhoto = new PhotoInformation();
                uploadedPhoto.setPath(cursor.getString(cursor.getColumnIndex("path")));
                uploadedPhoto.setDatetime(cursor.getString(cursor.getColumnIndex("datetime")));
                uploadedPhoto.setUserId(cursor.getString(cursor.getColumnIndex("user_id")));
                uploadedPhotoList.add(uploadedPhoto);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new SQLiteException("[select data] Unexpected exception");
        } finally {
            cursor.close();
        }
    }

    private void changeStartupLed() {
        Intent intent = new Intent(ChangeLedReceiver.CHANGE_STARTUP_LED);
        con.sendBroadcast(intent);
    }

    private void changeReadyLed() {
        Intent intent = new Intent(ChangeLedReceiver.CHANGE_READY_LED);
        con.sendBroadcast(intent);
    }

    private void changeTransferringLed() {
        Intent intent = new Intent(ChangeLedReceiver.CHANGE_TRANSFERRING_LED);
        con.sendBroadcast(intent);
    }

    private void changeStopTransferringLed() {
        Intent intent = new Intent(ChangeLedReceiver.CHANGE_STOP_TRANSFERRING_LED);
        con.sendBroadcast(intent);
    }

    private void changeTransferringStatusLed() {
        Intent intent = new Intent(ChangeLedReceiver.CHANGE_TRANSFERRING_STATUS_LED);
        intent.putExtra(ChangeLedReceiver.TRANSFERRING_CURRENT_NUMBER, uploadCurrentNumber + 1);
        intent.putExtra(ChangeLedReceiver.TRANSFERRING_ALL_NUMBER, uploadAllNumber);
        con.sendBroadcast(intent);
    }

    private void changeErrorLed() {
        Intent intent = new Intent(ChangeLedReceiver.CHANGE_ERROR_LED);
        intent.putExtra(ChangeLedReceiver.ERROR_CODE, "E" + errorType.getCode());
        con.sendBroadcast(intent);
    }

    private void notificationStartUpload() {
        isUploading = true;
        Intent intent = new Intent(UploadStatusReceiver.UPLOAD_START);
        con.sendBroadcast(intent);
    }

    private void notificationEndUpload() {
        isUploading = false;
        Intent intent = new Intent(UploadStatusReceiver.UPLOAD_END);
        con.sendBroadcast(intent);
    }

    private void notificationDeleteFile(String[] targets) {
        Intent intent = new Intent(DeleteFileReceiver.DELETE_FILE);
        intent.putExtra(DeleteFileReceiver.TARGETS, targets);
        con.sendBroadcast(intent);
    }

    /**
     * HTTP communication implementation class
     */
    private class SimpleHttpd extends NanoHTTPD implements UploadPhotoApiCallback {
        private final Logger LOG = Logger.getLogger(SimpleHttpd.class.getName());
        private ExecutorService uploadFileService = null;
        private ExecutorService pollingGetTokenService = null;

        /**
         * Constructor
         */
        public SimpleHttpd() throws IOException {
            super(PORT);
        }

        /**
         * Response to request
         *
         * @param session session
         * @return resource
         */
        @Override
        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            String uri = session.getUri();
            this.LOG.info(method + " '" + uri + "' ");

            if ("/".equals(uri)) {
                uri = "index.html";
            }

            // In the case of NanoHTTPD, since the POST request is stored in a temporary file, a buffer is given for reading again
            Map<String, String> tmpRequestFile = new HashMap<>();
            if (Method.POST.equals(method)) {
                try {
                    session.parseBody(tmpRequestFile);
                } catch (IOException e) {
                    return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "SERVER INTERNAL ERROR: IOException: " + e.getMessage());
                } catch (ResponseException e) {
                    return newFixedLengthResponse(e.getStatus(), MIME_PLAINTEXT, e.getMessage());
                }
            }
            Map<String, String> params = session.getParms();

            if (params.get("google_auth") != null) {
                updateUploadInfo();
                if (isReady) {
                    changeReadyLed();
                }
                try {
                    // Start upload destination authentication
                    uploadPhotoApi = UploadPhotoApiFactory.createUploadPhotoApi(con, apiType);
                    doAuthorization();
                    uri = "/google_auth.html";
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new SQLiteException("[update data] unexpected exception");
                }
            } else if (params.get("no_operation_timeout_minute") != null) {
                ContentValues values = new ContentValues();
                values.put("no_operation_timeout_minute", params.get("no_operation_timeout_minute"));
                dbObject.update("theta360_setting", values, null, null);
                requested = true;
            } else if (params.get("api_type") != null) {
                // Provisional response
                /*
                String new_api_type = params.get("api_type");
                if (!apiType.equals(new_api_type)) {
                    refreshToken = "";
                    userId = "";
                    apiType = new_api_type;
                    updateAuthDb();
                    changeStartupLed();
                }
                */
            } else if (uri.equals("/settings")) {
                ContentValues values = new ContentValues();
                String rawUpload = params.get("raw_upload");
                if (rawUpload != null) {
                    values.put("is_upload_raw", Boolean.compare(rawUpload.equals("true"), false));
                }
                String videoUpload = params.get("video_upload");
                if (videoUpload != null) {
                    values.put("is_upload_movie", Boolean.compare(videoUpload.equals("true"), false));
                }
                String uploadedDelete = params.get("uploaded_delete");
                if (uploadedDelete != null) {
                    values.put("is_delete_uploaded_file", Boolean.compare(uploadedDelete.equals("true"), false));
                }
                dbObject.update("theta360_setting", values, null, null);
                uri = "/index.html";
            } else if (uri.equals("/api_type.html")) {
                // Provisional response
                uri = "/index.html";
            }

            return serveFile(uri);
        }

        private void startPollingGetToken() {
            PollingGetToken pollingGetToken = new PollingGetToken();

            pollingGetTokenService = null;
            try {
                pollingGetTokenService = Executors.newSingleThreadExecutor();
                pollingGetTokenService.execute(pollingGetToken);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                pollingGetTokenService.shutdown();
            }
        }

        private class PollingGetToken implements Runnable {
            @Override
            public void run() {
                long pollingEndTimeMillis = System.currentTimeMillis() + uploadPhotoApi.getExpiresIn() * 1000;
                long pollingIntervalMillis = uploadPhotoApi.getInterval() * 1000;
                boolean isGotAccessToken = false;
                try {
                    while (System.currentTimeMillis() < pollingEndTimeMillis) {
                        Timber.d("polling get token");
                        if (!isGotAccessToken && hasAccessToken()) {
                            isGotAccessToken = true;
                        }
                        if (isGotAccessToken && hasUserinfo()) {
                            updateAuthDb();
                            break;
                        }
                        Thread.sleep(pollingIntervalMillis);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void startUploadFile() {
            UploadFile uploadFile = new UploadFile();

            uploadFileService = null;
            try {
                uploadFileService = Executors.newSingleThreadExecutor();
                uploadFileService.execute(uploadFile);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                uploadFileService.shutdown();
            }
        }

        private class UploadFile implements Runnable {
            @Override
            public void run() {
                if (!hasUploadFile()) {
                    changeErrorLed();
                }
            }
        }

        private void doAuthorization() {
            uploadPhotoApi.setCallback(this);
            uploadPhotoApi.startRequestCode();
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }

        private boolean hasAccessToken() {
            // Get a token
            uploadPhotoApi.setCallback(this);
            uploadPhotoApi.startRequestToken();
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }

            if (uploadPhotoApi.getAccessToken() == null || uploadPhotoApi.getAccessToken().isEmpty()) {
                return false;
            }
            changeReadyLed();
            refreshToken = uploadPhotoApi.getRefreshToken();
            return true;
        }

        private boolean hasRefreshToken() {
            uploadPhotoApi.setRefreshToken(refreshToken);
            uploadPhotoApi.setCallback(this);
            uploadPhotoApi.startRefreshToken();
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }

            if (uploadPhotoApi.getAccessToken() == null || uploadPhotoApi.getAccessToken().isEmpty()) {
                return false;
            }
            return true;
        }

        private boolean hasUserinfo() {
            uploadPhotoApi.setCallback(this);
            uploadPhotoApi.startRequestUserinfo();
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }

            if (uploadPhotoApi.getUserId() == null || uploadPhotoApi.getUserId().isEmpty()) {
                return false;
            }
            userId = uploadPhotoApi.getUserId();
            return true;
        }

        private boolean hasUploadFile() {
            Log.i("SimpleHttpd", "hasUploadFile");
            notificationStartUpload();
            changeTransferringLed();
            uploadPhotoApi.setUserId(userId);
            boolean result = true;
            SettingData settingData = readSettingData();
//            if (specifiedPhotoList == null || specifiedPhotoList.size() == 0) {
//                boolean isUploadRaw = settingData.getIsUploadRaw();
//                boolean isUploadMovie = settingData.getIsUploadMovie();
//                uploadingPhotoList = getPhotoList(DCIM_PATH, isUploadRaw, isUploadMovie);
//                uploadingPhotoList.addAll(getPhotoList(PICTURES_PATH, isUploadRaw, isUploadMovie));
//            } else {
                uploadingPhotoList = new ArrayList();
                for (PhotoInformation photoInformation : specifiedPhotoList) {
                    photoInformation.setUserId(userId);
                    uploadingPhotoList.add(photoInformation);
//                }
            }
            uploadAllNumber = uploadingPhotoList.size();
            uploadCurrentNumber = 0;
            Timber.i("uploading " + uploadAllNumber + " files");

            if (uploadPhotoApi.getAccessToken() == null || uploadPhotoApi.getAccessToken().isEmpty()) {
                Timber.e("Access token is empty");
                MainActivity.splay.playSound(MainActivity.splay.soundAccessTokenIsEmpty);
                errorType = ErrorType.NOT_SETTINGS;
                changeReadyLed();
                uploadingPhotoList = null;
                specifiedPhotoList = null;
                uploadFileService = null;
                notificationEndUpload();
                return false;
            }

            boolean isNotAuthorization = false;
            int timeoutMSec = settingData.getNoOperationTimeoutMinute() * 60 * 1000;
            isDeleteUploadedFile = settingData.getIsDeleteUploadedFile();
            try {
                boolean isFirst = true;
                for (PhotoInformation photoInformation : uploadingPhotoList) {
                    if (isFirst) {
                        isFirst = false;
                    } else {
                        uploadCurrentNumber++;
                    }
                    changeTransferringStatusLed();
                    uploadingPhoto = photoInformation;
                    isSucceedUpload = null;
                    try {
                        File file = new File(photoInformation.getPath());
                        uploadPhotoApi.setUploadDataPath(photoInformation.getPath());
                        uploadPhotoApi.setUploadDataName(file.getName());
                        uploadPhotoApi.startUploadFile();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        result = false;
                        continue;
                    }

                    long startUploadingMSec = System.currentTimeMillis();
                    while (true) {
                        if (isSucceedUpload == null) {
                            if (Thread.interrupted()) {
                                throw new InterruptedException("");
                            }
                            continue;
                        } else if (isSucceedUpload) {
                            break;
                        } else {
                            if (errorCode == HttpsURLConnection.HTTP_BAD_REQUEST ||
                                    errorCode == HttpsURLConnection.HTTP_FORBIDDEN) {
                                errorType = ErrorType.BAD_SETTINGS;
                                isNotAuthorization = true;
                            } else {
                                if (timeoutMSec > 0 && System.currentTimeMillis() - startUploadingMSec > timeoutMSec) {
                                    errorType = ErrorType.TIMEOUT;
                                    break;
                                }
                                changeStopTransferringLed();
                                Thread.sleep(UPLOAD_RETRY_WAIT_MSEC);
                                changeTransferringLed();
                                uploadPhotoApi.startUploadFile();
                                continue;
                            }
                        }
                        break;
                    }
                    if (isNotAuthorization) {
                        result = false;
                        break;
                    }
                }
                uploadCurrentNumber++;
                // Wait 3 seconds + alpha for 3 seconds to flash the LED in the upload completed state
                Thread.sleep(3200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            changeReadyLed();
            uploadingPhotoList = null;
            specifiedPhotoList = null;
            uploadFileService = null;
            notificationEndUpload();

            return result;
        }

        // ***** for uploading single file *****
        private boolean uploadSingleFile(PhotoInformation photoInformation) {
            Log.i("SimpleHttpd", "uploadSingleFile");
            notificationStartUpload();
            changeTransferringLed();
            uploadPhotoApi.setUserId(userId);
            boolean result = true;
            SettingData settingData = readSettingData();

            if (uploadPhotoApi.getAccessToken() == null || uploadPhotoApi.getAccessToken().isEmpty()) {
                Timber.e("Access token is empty");
                MainActivity.splay.playSound(MainActivity.splay.soundAccessTokenIsEmpty);
                errorType = ErrorType.NOT_SETTINGS;
                changeReadyLed();
//                uploadingPhotoList = null;
//                specifiedPhotoList = null;
                uploadFileService = null;
                notificationEndUpload();
                return false;
            }

            Log.i("SimpleHttpd", "AAA");

            boolean isNotAuthorization = false;
            int timeoutMSec = settingData.getNoOperationTimeoutMinute() * 60 * 1000;
            isDeleteUploadedFile = settingData.getIsDeleteUploadedFile();
            try {
//                boolean isFirst = true;
//                for (PhotoInformation photoInformation : uploadingPhotoList) {
//                    if (isFirst) {
//                        isFirst = false;
//                    } else {
//                        uploadCurrentNumber++;
//                    }
                    changeTransferringStatusLed();
//                    uploadingPhoto = photoInformation;
                    isSucceedUpload = null;
                    try {
                        File file = new File(photoInformation.getPath());
                        uploadPhotoApi.setUploadDataPath(photoInformation.getPath());
                        uploadPhotoApi.setUploadDataName(file.getName());
                        uploadPhotoApi.startUploadFile();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        result = false;
//                        continue;
                    }

                    Log.i("SimpleHttpd", "BBB");


                    long startUploadingMSec = System.currentTimeMillis();
                    while (true) {
                        if (isSucceedUpload == null) {
                            Log.d("uploadSingleFile", "isSucceedUpload == null");

                            if (Thread.interrupted()) {
                                Log.d("uploadSingleFile", "Thread.interrupted");
                                throw new InterruptedException("");
                            }
                            continue;
                        } else if (isSucceedUpload) {
                            Log.d("uploadSingleFile", "isSucceedUpload");

                            break;
                        } else {
                            if (errorCode == HttpsURLConnection.HTTP_BAD_REQUEST ||
                                    errorCode == HttpsURLConnection.HTTP_FORBIDDEN) {
                                errorType = ErrorType.BAD_SETTINGS;
                                Log.d("ErrorType1", ""+errorType);
                                isNotAuthorization = true;
                            } else {
                                if (timeoutMSec > 0 && System.currentTimeMillis() - startUploadingMSec > timeoutMSec) {
                                    errorType = ErrorType.TIMEOUT;
                                    Log.d("ErrorType2", ""+errorType);
                                    break;
                                }
                                Log.d("uploadSingleFile", "ZZZ");
                                changeStopTransferringLed();
                                Thread.sleep(UPLOAD_RETRY_WAIT_MSEC);
                                changeTransferringLed();
                                uploadPhotoApi.startUploadFile();
                                continue;
                            }
                        }
                        break;
                    }

                    Log.i("SimpleHttpd", "BBB-0");

                    if (isNotAuthorization) {
                        result = false;
//                        break;
                    }
//                }
//                uploadCurrentNumber++;
                // Wait 3 seconds + alpha for 3 seconds to flash the LED in the upload completed state
                Thread.sleep(3200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.i("SimpleHttpd", "CCC");

            changeReadyLed();
//            uploadingPhotoList = null;
//            specifiedPhotoList = null;
            uploadFileService = null;
            notificationEndUpload();

            Log.i("SimpleHttpd", "DDD");

            return result;
        }

        private List<PhotoInformation> getPhotoList(String searchPath, boolean isUploadRaw, boolean isUploadMovie) {
            List photoList = new ArrayList();
            String path;
            Log.i("getPhotoList searchPath", searchPath);
            for (File file : new File(searchPath).listFiles()) {
                path = file.getAbsolutePath();
                if (file.isFile()) {
                    switch (ExtensionType.getType(path)) {
                        case JPG:
                            break;
                        case RAW:
                            if (!isUploadRaw) {
                                continue;
                            }
                            break;
                        case MP4:
                            if (!isUploadMovie) {
                                continue;
                            }
                            break;
                        case UNKNOWN:
                        default:
                            continue;
                    }
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                    String datetime = sdf.format(file.lastModified());
                    PhotoInformation uploadingPhoto = new PhotoInformation();
                    uploadingPhoto.setPath(path);
                    uploadingPhoto.setDatetime(datetime);
                    uploadingPhoto.setUserId(userId);
                    if (!uploadedPhotoList.contains(uploadingPhoto)) {
                        photoList.add(uploadingPhoto);
                    }
                } else if (file.isDirectory()) {
                    photoList.addAll(getPhotoList(path, isUploadRaw, isUploadMovie));
                }
            }

            return photoList;
        }

        /**
         * Sending files
         *
         * @param uri requested url
         * @return resource
         */
        private Response serveFile(String uri) {

            if (uri.equals("/login")) {
                if (!(pollingGetTokenService == null || pollingGetTokenService.isShutdown())) {
                    pollingGetTokenService.shutdownNow();
                }
                startPollingGetToken();
                return newChunkedResponse(Status.OK, "text/html", null);
            } else if (uri.equals("/logout")) {
                if (!(pollingGetTokenService == null || pollingGetTokenService.isShutdown())) {
                    pollingGetTokenService.shutdownNow();
                }
                refreshToken = "";
                userId = "";
                updateAuthDb();
                changeStartupLed();

                doAuthorization();
                InputStream destInputStream = null;
                JSONObject data = new JSONObject();
                try {
                    data.put("user_code", uploadPhotoApi.getUserCode());
                    data.put("google_auth_url", uploadPhotoApi.getRedirectUrl());
                    destInputStream = stringToInputStream(data.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }

                return newChunkedResponse(Status.OK, "text/html", destInputStream);
            } else if (uri.equals("/reacquire")) {
                doAuthorization();
                InputStream destInputStream = null;
                JSONObject data = new JSONObject();
                try {
                    data.put("user_code", uploadPhotoApi.getUserCode());
                    data.put("google_auth_url", uploadPhotoApi.getRedirectUrl());
                    destInputStream = stringToInputStream(data.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }

                return newChunkedResponse(Status.OK, "text/html", destInputStream);
            } else if (uri.equals("/check_logged_in")) {
                InputStream destInputStream = null;
                try {
                    if (userId == null || userId.isEmpty()) {
                        destInputStream = stringToInputStream("0");
                    } else {
                        destInputStream = stringToInputStream("1");
                    }
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                return newChunkedResponse(Status.OK, "text/html", destInputStream);
            } else if (uri.equals("/cancel")) {
                if (!(pollingGetTokenService == null || pollingGetTokenService.isShutdown())) {
                    pollingGetTokenService.shutdownNow();
                }
                return newChunkedResponse(Status.OK, "text/html", null);
            } else if (uri.equals("/done")) {
                return newChunkedResponse(Status.OK, "text/html", null);
            } else if (uri.equals("/upload")) {
                if (isReady) {
                    if (uploadFileService == null) {
                        int refreshCount = 0;
                        boolean refreshResult = false;
                        while (!refreshResult && refreshCount < REFRESH_COUNT_MAX) {
                            refreshResult = hasRefreshToken();
                            refreshCount++;
                        }
                        startUploadFile();
                    } else {
                        uploadFileService.shutdownNow();
                        uploadFileService = null;
                    }
                }
                return newChunkedResponse(Status.OK, "text/html", null);
            } else if (uri.equals("/check_uploading")) {
                InputStream destInputStream = null;
                Map<String, Integer> map = new HashMap<>();
                Gson gson = new Gson();
                try {
                    if (isUploading) {
                        map.put("isUploading", 1);
                        map.put("current", uploadCurrentNumber);
                        map.put("all", uploadAllNumber);
                    } else {
                        map.put("isUploading", 0);
                        map.put("current", 0);
                        map.put("all", 0);
                    }
                    destInputStream = stringToInputStream(gson.toJson(map));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                return newChunkedResponse(Status.OK, "text/html", destInputStream);
            } else if (uri.equals("/end")) {
                Intent intent = new Intent(FinishApplicationReceiver.FINISH_APPLICATION);
                con.sendBroadcast(intent);
                return newChunkedResponse(Status.OK, "text/html", null);
            } else if (uri.equals("/modelInfo")) {
                CameraConnector cameraApi= new CameraConnector();
                JSONObject cameraInfo= cameraApi.getCameraInfo();
                Log.i("/modelInfo info", cameraInfo.toString());

                final String KEY= "isRawSupported";
                JSONObject res = new JSONObject();
                InputStream resStream = null;
                try {
                    if (cameraInfo.get("model").equals("RICOH THETA Z1")) {
                        res.put(KEY, true);
                    } else {
                        res.put(KEY, false);
                    }
                } catch(JSONException ex) {
                    Log.e("/check_rawSupportedModel", ex.toString());
                    res= new JSONObject();
                }
                try {
                    Log.i("/check_rawSupportedModel response", res.toString());
                    resStream = stringToInputStream(res.toString());
                } catch(UnsupportedEncodingException ex) {
                    ex.printStackTrace();
                }
                return newChunkedResponse(Status.OK, "application/json", resStream);
            }

            String filename = uri;
            if (uri.substring(0, 1).equals("/")) {
                filename = filename.substring(1);
            }

            AssetManager as = con.getResources().getAssets();
            InputStream fis = null;
            try {
                fis = as.open(filename);
            } catch (Exception e) {

            }

            if (uri.endsWith(".ico")) {
                return newChunkedResponse(Status.OK, "image/x-icon", fis);
            } else if (uri.endsWith(".png") || uri.endsWith(".PNG")) {
                return newChunkedResponse(Status.OK, "image/png", fis);
            } else if (uri.endsWith(".js")) {
                return newChunkedResponse(Status.OK, "application/javascript", fis);
            } else if (uri.endsWith(".properties")) {
                return newChunkedResponse(Status.OK, "text/html", fis);
            } else if (uri.endsWith(".css")) {
                return newChunkedResponse(Status.OK, "text/html", fis);
            } else if (uri.endsWith(".html") || uri.endsWith(".htm")) {
                String srcString = null;
                try {
                    srcString = inputStreamToString(fis);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (uri.equals("/google_auth.html")) {
                    if (userId == null || userId.isEmpty()) {
                        srcString = srcString.replaceFirst("#IS_LOGGED_IN#", "0");
                    } else {
                        srcString = srcString.replaceFirst("#IS_LOGGED_IN#", "1");
                    }
                    srcString = srcString.replaceFirst("#GOOGLE_PHOTO_USER_CODE#", uploadPhotoApi.getUserCode());
                    srcString = srcString.replaceFirst("#GOOGLE_PHOTO_CODE_AUTH_URL#", uploadPhotoApi.getRedirectUrl());
                    srcString = srcString.replaceFirst("#API_TYPE#", apiType);
                    try (InputStream destInputStream = new ByteArrayInputStream(srcString.getBytes("UTF-8"))) {
                        return newChunkedResponse(Status.OK, "text/html", destInputStream);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        return newChunkedResponse(Status.OK, "text/html", fis);
                    }
                } else if (uri.equals("/timeout.html")) {
                    SettingData settingData = readSettingData();
                    srcString = srcString.replaceFirst("#NO_OPERATION_TIMEOUT_MINUTE#", String.valueOf(settingData.getNoOperationTimeoutMinute()));
                    try (InputStream destInputStream = new ByteArrayInputStream(srcString.getBytes("UTF-8"))) {
                        return newChunkedResponse(Status.OK, "text/html", destInputStream);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        return newChunkedResponse(Status.OK, "text/html", fis);
                    }
                } else if (uri.equals("/api_type.html")) {
                    srcString = srcString.replaceFirst("#API_TYPE#", apiType);
                    try (InputStream destInputStream = new ByteArrayInputStream(srcString.getBytes("UTF-8"))) {
                        return newChunkedResponse(Status.OK, "text/html", destInputStream);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        return newChunkedResponse(Status.OK, "text/html", fis);
                    }
                } else {
                    updateUploadInfo();
                    SettingData settingData = readSettingData();
                    String JSCode = "\\$(function() {\n"
                            + "\\$('#no_operation_timeout_minute_text').val('" + settingData.getNoOperationTimeoutMinute() + "');";
                    JSCode += "\\$('#raw_upload').prop('checked', " + String.valueOf(settingData.getIsUploadRaw()) + ");";
                    JSCode += "\\$('#video_upload').prop('checked', " + String.valueOf(settingData.getIsUploadMovie()) + ");";
                    JSCode += "\\$('#uploaded_delete').prop('checked', " + String.valueOf(settingData.getIsDeleteUploadedFile()) + ");";
                    JSCode += "\\$('#api_type').text('" + apiType + "');";
                    if (userId != null) {
                        JSCode += "\\$('#upload_user_id').text('" + userId + "');";
                    }
                    if (!isReady) {
                        JSCode += "\\$('#upload_btn').prop('disabled', true);";
                    }
                    JSCode += "});";
                    String newSrcString = srcString.replaceFirst("#JS_INJECTION#", JSCode);

                    InputStream destInputStream = null;
                    try {
                        destInputStream = stringToInputStream(newSrcString);
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }

                    return newChunkedResponse(Status.OK, "text/html", destInputStream);
                }
            } else {
                return newFixedLengthResponse(NOT_FOUND, "text/plain", uri);
            }
        }

        /**
         * Read configuration data from DB and return
         */
        private SettingData readSettingData() {
            SettingData settingData;

            Cursor cursor = dbObject.query("theta360_setting", null, null, null, null, null, null, null);
            try {
                settingData = new SettingData();
                if (cursor.moveToNext()) {
                    settingData.setNoOperationTimeoutMinute(cursor.getInt(cursor.getColumnIndex("no_operation_timeout_minute")));
                    settingData.setStatus(cursor.getString(cursor.getColumnIndex("status")));
                    settingData.setIsUploadRaw(cursor.getInt(cursor.getColumnIndex("is_upload_raw")));
                    settingData.setIsUploadMovie(cursor.getInt(cursor.getColumnIndex("is_upload_movie")));
                    settingData.setIsDeleteUploadedFile(cursor.getInt(cursor.getColumnIndex("is_delete_uploaded_file")));
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new SQLiteException("[select data] Unexpected exception");
            } finally {
                cursor.close();
            }

            return settingData;
        }

        private void updateAuthDb() {
            try {
                // Register authentication information in DB
                ContentValues values = new ContentValues();
                values.put("refresh_token", refreshToken);
                values.put("user_id", userId);
                values.put("api_type", apiType);
                dbObject.update("auth_information", values, null, null);
                Timber.i("saved tokens to DB");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void completedRequestCode(String result) {
            try {
                JSONObject json = new JSONObject(result);
                uploadPhotoApi.setApiResult(json);
                synchronized (lock) {
                    lock.notify();
                }
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void failedRequestCode(String result) {
            uploadPhotoApi.setRedirectUrl(new String());
            uploadPhotoApi.setDeviceCode(new String());
            uploadPhotoApi.setUserCode(new String());
            synchronized (lock) {
                lock.notify();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void completedRequestToken(String result) {
            try {
                JSONObject json = new JSONObject(result);
                uploadPhotoApi.setApiResult(json);
                synchronized (lock) {
                    lock.notify();
                }
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void failedRequestToken(String result) {
            uploadPhotoApi.setAccessToken(new String());
            uploadPhotoApi.setRefreshToken(new String());
            synchronized (lock) {
                lock.notify();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void completedRefreshToken(String result) {
            try {
                JSONObject json = new JSONObject(result);
                uploadPhotoApi.setApiResult(json);
                synchronized (lock) {
                    lock.notify();
                }
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void failedRefreshToken(String result) {
            synchronized (lock) {
                lock.notify();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void completedRequestUserinfo(String result) {
            try {
                JSONObject json = new JSONObject(result);
                if (json.has("email")) {
                    String email = json.getString("email");
                    uploadPhotoApi.setUserId(email.split("@")[0]);
                } else {
                    uploadPhotoApi.setUserId("");
                }
                synchronized (lock) {
                    lock.notify();
                }
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void failedRequestUserinfo(String result) {
            uploadPhotoApi.setUserId("");
            synchronized (lock) {
                lock.notify();
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void completedUploadFile(String result) {
            Timber.i("succeeded upload file : " + uploadingPhoto.getPath());
            if (isDeleteUploadedFile) {
                deleteUploadedPhoto();
            } else {
                insertUploadedPhotoDb();
            }
            isSucceedUpload = true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void failedUploadFile(String result) {
            Timber.i("failed upload file : " + uploadingPhoto.getPath() + " by " + result);
            MainActivity.splay.playSound(MainActivity.splay.soundUploadingFailed);

            try {
                errorCode = Integer.parseInt(result);
            } catch (NumberFormatException ex) {
            }

            isSucceedUpload = false;
        }

        private void deleteUploadedPhoto() {
            if (new File(uploadingPhoto.getPath()).delete()) {
                String target = uploadingPhoto.getPath().replace(Environment.getExternalStorageDirectory().getAbsolutePath() + "/", "");
                notificationDeleteFile(new String[]{target});
                Timber.i("succeeded delete file : " + uploadingPhoto.getPath());
            } else {
                Timber.i("failed delete file : " + uploadingPhoto.getPath());
            }
        }

        private void insertUploadedPhotoDb() {
            try {
                ContentValues values;
                values = new ContentValues();
                values.put("path", uploadingPhoto.getPath());
                values.put("datetime", uploadingPhoto.getDatetime());
                values.put("user_id", uploadingPhoto.getUserId());
                values.put("api_type", uploadPhotoApi.getApiType());
                dbObject.insert("uploaded_photo", null, values);
                uploadedPhotoList.add(uploadingPhoto);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
