package jp.imlab.visphoto;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;

public class SoundPlay {

    private Context context = MainActivity.getInstance();

    private SoundPool soundPool;
    private static int numSound = 10;
    public int soundWelcomeToVisPhoto, soundReady;
    public int soundWifiConnected, soundWifiDisconnected;
    public int soundStartRecording, soundStopRecording, soundRecordingFailed;
    public int soundUploadingFailed, soundAccessTokenIsEmpty, soundVisphotoIsShuttingDown;

    public SoundPlay() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING); // 2019/1/21追記
        audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVol, 0); // 2019/1/21追記

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setLegacyStreamType(AudioManager.STREAM_RING) // 2019/1/21追記
                .build();

        soundPool = new SoundPool.Builder()
                .setAudioAttributes(audioAttributes)
                // ストリーム数に応じて
                .setMaxStreams(numSound)
                .build();

        // welcome_to_visphoto.ogg をロードしておく
        soundWelcomeToVisPhoto = soundPool.load(context, R.raw.welcome_to_visphoto, 1);

        // wifi_connected.ogg をロードしておく
        soundWifiConnected = soundPool.load(context, R.raw.wifi_connected, 1);

        // wifi_disconnected.ogg をロードしておく
        soundWifiDisconnected = soundPool.load(context, R.raw.wifi_disconnected, 1);

        // start_recording.ogg をロードしておく
        soundStartRecording = soundPool.load(context, R.raw.start_recording, 1);

        // stop_recording.ogg をロードしておく
        soundStopRecording = soundPool.load(context, R.raw.stop_recording, 1);

        // recording_failed.ogg をロードしておく
        soundRecordingFailed = soundPool.load(context, R.raw.recording_failed, 1);

        // uploading_failed.ogg をロードしておく
        soundUploadingFailed = soundPool.load(context, R.raw.uploading_failed, 1);

        // access_token_is_empty.ogg をロードしておく
        soundAccessTokenIsEmpty = soundPool.load(context, R.raw.access_token_is_empty, 1);

        // visphoto_is_shutting_down.ogg をロードしておく
        soundVisphotoIsShuttingDown = soundPool.load(context, R.raw.visphoto_is_shutting_down, 1);

        // ready.ogg をロードしておく
        soundReady = soundPool.load(context, R.raw.ready, 1);

        // load が終わったか確認する場合
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                if (sampleId==soundWelcomeToVisPhoto && status==0) {
                    playSound(sampleId);
                }
//                if (sampleId==soundReady && status==0) {
//                    playSound(sampleId);
////                    MainActivity.setIsReady(true);
//                }
            }
        });
    }

    public void playSound(int soundID) {
        // play(ロードしたID, 左音量, 右音量, 優先度, ループ,再生速度)
        soundPool.play(soundID, 1.0f, 1.0f, 0, 0, 1);
    }
}
