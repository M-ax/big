package com.example.big;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.big.playback.BufferedSmbMedia;
import com.example.big.playback.GestureFeedbackView;
import com.example.big.playback.GlVideoView;
import com.example.big.playback.MediaTitle;
import com.example.big.playback.PlaybackGesture;
import com.example.big.playback.PlaybackInput;
import com.example.big.playback.SeekFeedback;
import com.example.big.playback.SeekPreview;
import com.example.big.playback.SeekFrameExtractor;
import com.example.big.playback.VideoAspect;
import com.example.big.smb.SmbSession;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService titleExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();
    private final Runnable hideControls = () -> setControlsVisible(false);
    private final Runnable hideFeedback = () -> this.feedback.setVisibility(View.GONE);
    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private AssetFileDescriptor mediaDescriptor;
    private volatile BufferedSmbMedia bufferedSmbMedia;
    private boolean mediaReady;
    private boolean bufferFull;
    private volatile boolean destroying;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PlaybackGesture gestures;
    private PlaybackGesture.Result gestureResult;
    private GestureDetector tapDetector;
    private GlVideoView videoLayout;
    private boolean outputStarted;
    private boolean recoveringOutput;
    private long outputPosition;
    private View header;
    private View controls;
    private View unlock;
    private View touchSurface;
    private GestureFeedbackView feedback;
    private TextView status;
    private TextView time;
    private AppCompatImageButton playPause;
    private SeekBar seekBar;
    private boolean resumed;
    private boolean started;
    private boolean wantsPlay = true;
    private boolean hasFocus;
    private boolean resumeAfterFocusLoss;
    private boolean receiverRegistered;
    private boolean scrubbing;
    private boolean locked;
    private boolean failed;
    private boolean ended;
    private boolean ignoreTouch;
    private boolean dialogOpen;
    private float playbackRate = 1f;
    private int fitMode;
    private long pendingResumePosition;
    private long pendingPlaybackSeek = -1;
    private long gestureStartPosition;
    private long scrubStartPosition;
    private final SeekPreview seekPreview = new SeekPreview();
    private boolean previewing;
    private long previewStartPosition;
    private SeekFrameExtractor frameExtractor;
    private long exactRequest;
    private long exactTarget;
    private long exactRestore = -1;
    private Bitmap exactFrame;
    private final Runnable renderSeekPreview = new Runnable() {
        @Override
        public void run() {
            if (!previewing || !canSeek() || !resumed) return;
            long target = seekPreview.poll(SystemClock.uptimeMillis(), videoLayout.getVideoFrameCount());
            if (target >= 0) {
                BufferedSmbMedia cache = bufferedSmbMedia;
                if (cache != null) cache.updateSeekPreview(target, mediaPlayer.getLength());
                if (!requestExactFrame(target, true)) {
                    videoLayout.requestSeekFrame();
                    mediaPlayer.setTime(target, false);
                }
            }
            if (seekPreview.hasPending()) handler.postDelayed(this, 16);
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pauseByUser();
            }
        }
    };

    private final Runnable refreshProgress = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer == null || !resumed) {
                return;
            }
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_player);
        keepFullscreen(getWindow());
        videoLayout = findViewById(R.id.video_layout);
        videoLayout.setListener(new GlVideoView.Listener() {
            @Override
            public void onOutputReady() {
                attachVideoOutput();
                if (exactFrame != null && !wantsPlay) videoLayout.setSeekStill(exactFrame, fitMode);
                long pausedTarget = mediaPlayer == null ? 0
                        : recoveringOutput ? outputPosition : playbackPosition();
                if (recoveringOutput) {
                    recoveringOutput = false;
                    if (canSeek()) {
                        mediaPlayer.setTime(Math.max(0, outputPosition));
                        videoLayout.requestStillFrame();
                    }
                }
                if (resumed && started && !wantsPlay && !failed) {
                    requestExactFrame(Math.max(pendingResumePosition, pausedTarget), false);
                }
                if (!started || wantsPlay) {
                    startPlayback();
                }
            }

            @Override
            public void onOutputLost() {
                if (mediaPlayer != null) {
                    if (outputStarted && resumed && started && !failed && !ended && !destroying) {
                        outputPosition = playbackPosition();
                        recoveringOutput = true;
                        // With its only track disabled, a video-only input can race to EOF.
                        mediaPlayer.pause();
                    }
                    mediaPlayer.getVLCVout().detachViews();
                }
            }

            @Override
            public void onOutputSizeChanged() {
                applyVideoScale();
            }

            @Override
            public void onOutputError() {
                if (!destroying) {
                    showError(R.string.engine_error);
                }
            }
        });
        header = findViewById(R.id.player_header);
        controls = findViewById(R.id.player_controls);
        unlock = findViewById(R.id.unlock);
        touchSurface = findViewById(R.id.gesture_surface);
        feedback = findViewById(R.id.gesture_feedback);
        status = findViewById(R.id.playback_status);
        time = findViewById(R.id.playback_time);
        playPause = findViewById(R.id.play_pause);
        seekBar = findViewById(R.id.seek_bar);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(this::onAudioFocusChange, handler)
                .setWillPauseWhenDucked(true)
                .build();
        gestures = new PlaybackGesture(ViewConfiguration.get(this).getScaledTouchSlop());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.player_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        configureControls();
        configureGestures();
        long resumePosition = 0;
        if (savedInstanceState != null) {
            resumePosition = savedInstanceState.getLong("position");
            pendingResumePosition = resumePosition;
            wantsPlay = savedInstanceState.getBoolean("playing", true);
            playbackRate = savedInstanceState.getFloat("rate", 1f);
            fitMode = savedInstanceState.getInt("fit");
            setBrightness(savedInstanceState.getFloat("brightness", -1f));
        }
        updateSpeedButton();
        Uri uri = getIntent().getData();
        if (uri == null) {
            showError(R.string.file_open_error);
            return;
        }
        String scheme = uri.getScheme();
        boolean local = "content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme);
        if (!local && PlaybackInput.parseNetworkUrl(uri.toString()) == null) {
            showError(R.string.invalid_stream);
            return;
        }
        TextView title = findViewById(R.id.media_title);
        title.setText(MediaTitle.fallback(uri));
        if ("content".equalsIgnoreCase(scheme)) {
            titleExecutor.execute(() -> {
                String name = MediaTitle.resolve(getContentResolver(), uri);
                handler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        title.setText(name);
                    }
                });
            });
        }
        String sessionToken = getIntent().getStringExtra(SmbSession.EXTRA_SESSION);
        SmbSession smbSession = SmbSession.find(sessionToken, uri.toString());
        if (sessionToken != null && smbSession == null) {
            showError(R.string.smb_session_expired);
            return;
        }
        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--network-caching=1000");
            options.add("--keystore=memory");
            libVLC = new LibVLC(this, options);
            mediaPlayer = new MediaPlayer(libVLC);
            mediaPlayer.setEventListener(this::onPlayerEvent);
            mediaPlayer.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0);
            if (smbSession != null) {
                prepareBufferedSmb(smbSession, uri, resumePosition);
                return;
            }
            Media media;
            if ("content".equalsIgnoreCase(scheme)) {
                mediaDescriptor = getContentResolver().openAssetFileDescriptor(uri, "r");
                if (mediaDescriptor == null) {
                    throw new IOException("No file descriptor");
                }
                media = new Media(libVLC, mediaDescriptor);
            } else {
                media = new Media(libVLC, uri);
            }
            try {
                media.setHWDecoderEnabled(true, false);
                if (local) media.addOption(":file-caching=150");
                if (smbSession != null) {
                    for (String option : smbSession.mediaOptions()) {
                        media.addOption(option);
                    }
                }
                if (resumePosition > 0) {
                    media.addOption(":start-time=" + resumePosition / 1000.0);
                }
                if (!wantsPlay) {
                    media.addOption(":start-paused");
                }
                mediaPlayer.setMedia(media);
                mediaReady = true;
            } finally {
                media.release();
            }
        } catch (IOException | SecurityException exception) {
            showError(R.string.file_open_error);
        } catch (RuntimeException | LinkageError exception) {
            showError(R.string.engine_error);
        }
    }

    private void prepareBufferedSmb(SmbSession session, Uri uri, long resumePosition) {
        Context context = getApplicationContext();
        mediaExecutor.execute(() -> {
            if (destroying) {
                return;
            }
            try {
                bufferedSmbMedia = BufferedSmbMedia.open(context, session, uri);
                handler.post(() -> {
                    if (destroying || isFinishing() || mediaPlayer == null) {
                        return;
                    }
                    bufferedSmbMedia.setCachingEnabled(outputStarted);
                    Media media = new Media(libVLC, bufferedSmbMedia.descriptor().getFileDescriptor());
                    try {
                        media.setHWDecoderEnabled(true, false);
                        media.addOption(":file-caching=150");
                        if (resumePosition > 0) {
                            media.addOption(":start-time=" + resumePosition / 1000.0);
                        }
                        if (!wantsPlay) {
                            media.addOption(":start-paused");
                        }
                        mediaPlayer.setMedia(media);
                        mediaReady = true;
                        startPlayback();
                    } catch (RuntimeException exception) {
                        showError(R.string.file_open_error);
                    } finally {
                        media.release();
                    }
                });
            } catch (IOException | RuntimeException exception) {
                handler.post(() -> {
                    if (!destroying && !isFinishing()) {
                        showError(R.string.file_open_error);
                    }
                });
            }
        });
    }

    private void configureControls() {
        for (int id : new int[]{R.id.back, R.id.lock, R.id.unlock,
                R.id.seek_back, R.id.seek_forward, R.id.tracks, R.id.fit}) {
            View button = findViewById(id);
            button.setTooltipText(button.getContentDescription());
        }
        findViewById(R.id.player_button_row).addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            View transport = findViewById(R.id.transport_controls);
            View speed = findViewById(R.id.speed);
            View fit = findViewById(R.id.fit);
            float shift = Math.min(0, fit.getLeft() - speed.getRight());
            transport.setTranslationX(shift);
            speed.setTranslationX(shift);
        });
        findViewById(R.id.back).setOnClickListener(view -> finish());
        playPause.setOnClickListener(view -> {
            if (mediaPlayer != null && wantsPlay) {
                pauseByUser();
            } else {
                wantsPlay = true;
                startPlayback();
            }
        });
        findViewById(R.id.seek_back).setOnClickListener(view -> skip(-10000));
        findViewById(R.id.seek_forward).setOnClickListener(view -> skip(10000));
        findViewById(R.id.lock).setOnClickListener(view -> setLocked(true));
        unlock.setOnClickListener(view -> setLocked(false));
        findViewById(R.id.speed).setOnClickListener(view -> showSpeedDialog());
        findViewById(R.id.tracks).setOnClickListener(view -> showTrackTypeDialog());
        findViewById(R.id.fit).setOnClickListener(view -> {
            fitMode = (fitMode + 1) % 3;
            applyVideoScale();
            showFeedback(getString(new int[]{R.string.best_fit, R.string.fit_screen,
                    R.string.fill_screen}[fitMode]));
            scheduleHideControls();
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    long target = (long) (mediaPlayer.getLength() * (progress / 1000.0));
                    if (scrubbing && canSeek()) previewSeek(target, scrubStartPosition);
                    showFeedback(SeekFeedback.format(
                            target, scrubStartPosition, true));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
                scrubStartPosition = playbackPosition();
                handler.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (canSeek()) {
                    finishSeekPreview((long) (mediaPlayer.getLength() * (bar.getProgress() / 1000.0)));
                }
                scrubbing = false;
                scheduleHideControls();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (locked) {
                    setLocked(false);
                } else {
                    finish();
                }
            }
        });
    }


    private void configureGestures() {
        touchSurface.setOnClickListener(view -> setControlsVisible(controls.getVisibility() != View.VISIBLE));
        tapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent event) {
                if (!locked && !ignoreTouch) {
                    touchSurface.performClick();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent event) {
                if (!locked && !ignoreTouch) {
                    if (event.getX() < touchSurface.getWidth() / 3f) {
                        skip(-10000);
                    } else if (event.getX() > touchSurface.getWidth() * 2f / 3f) {
                        skip(10000);
                    } else {
                        playPause.performClick();
                    }
                }
                return true;
            }
        });
        touchSurface.setOnTouchListener((view, event) -> {
            if (locked || mediaPlayer == null || failed) {
                return true;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                ignoreTouch = false;
                gestureResult = null;
                gestureStartPosition = playbackPosition();
                IMedia.VideoTrack track = mediaPlayer.getCurrentVideoTrack();
                gestures.setDensity(getResources().getDisplayMetrics().density);
                gestures.setFrameRate(track == null ? 0 : track.frameRateNum, track == null ? 0 : track.frameRateDen);
                gestures.begin(event.getX(), event.getY(), view.getWidth(), view.getHeight(),
                        gestureStartPosition, mediaPlayer.getLength(), mediaPlayer.isSeekable(),
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), currentBrightness(), event.getEventTime());
                refineGestureFrameRate();
                handler.removeCallbacks(hideControls);
            }
            if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_CANCEL) {
                ignoreTouch = true;
                cancelSeekPreview();
                gestures.cancel();
                gestureResult = null;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                tapDetector.onTouchEvent(cancel);
                cancel.recycle();
                scheduleHideControls();
                return true;
            }
            if (ignoreTouch) {
                return true;
            }
            tapDetector.onTouchEvent(event);
            if (action == MotionEvent.ACTION_MOVE) {
                refineGestureFrameRate();
                for (int i = 0; i < event.getHistorySize(); i++) {
                    gestures.move(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalEventTime(i));
                }
                gestureResult = gestures.move(event.getX(), event.getY(), event.getEventTime());
                switch (gestureResult.action) {
                    case SEEK:
                        previewSeek(gestureResult.positionMs, gestureStartPosition);
                        showFeedback(TextUtils.concat(SeekFeedback.format(gestureResult.positionMs, gestureStartPosition, true),
                                "\n", getString(gestureResult.fineSeeking ? R.string.fine_seeking : R.string.fine_seek_hint)));
                        break;
                    case VOLUME:
                        if (audioManager.isVolumeFixed()) {
                            showFeedback(getString(R.string.volume_unavailable));
                        } else {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, gestureResult.volume, 0);
                            int maximum = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                            showFeedback(getString(R.string.volume_feedback,
                                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maximum));
                        }
                        break;
                    case BRIGHTNESS:
                        setBrightness(gestureResult.brightness);
                        showFeedback(getString(R.string.brightness_feedback,
                                Math.round(gestureResult.brightness * 100)));
                        break;
                    default:
                        break;
                }
            } else if (action == MotionEvent.ACTION_UP) {
                gestureResult = gestures.finish(event.getX(), event.getY(), event.getEventTime());
                if (gestureResult != null && gestureResult.action == PlaybackGesture.Action.SEEK && canSeek()) {
                    finishSeekPreview(gestureResult.positionMs);
                }
                gestures.cancel();
                gestureResult = null;
                scheduleHideControls();
            }
            return true;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        outputStarted = true;
        BufferedSmbMedia cache = bufferedSmbMedia;
        if (cache != null) cache.setCachingEnabled(true);
        feedback.setVideoSurface(videoLayout);
        attachVideoOutput();
        ContextCompat.registerReceiver(this, noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepFullscreen(getWindow());
        resumed = true;
        if (!started || wantsPlay) {
            startPlayback();
        }
        if (!wantsPlay && exactRestore >= 0) {
            requestExactFrame(exactRestore, false);
            exactRestore = -1;
        }
        handler.post(refreshProgress);
    }

    @Override
    protected void onPause() {
        resumed = false;
        boolean restoreFrame = exactFrame != null || previewing;
        cancelSeekPreview();
        if (restoreFrame && !wantsPlay && mediaPlayer != null) exactRestore = playbackPosition();
        cancelFrameExtraction();
        videoLayout.setPlaybackActive(false);
        videoLayout.setVideoFrameRate(0);
        updateSeekCache(false);
        resumeAfterFocusLoss = false;
        if (mediaPlayer != null && started && !failed && !ended) {
            mediaPlayer.pause();
        }
        abandonAudioFocus();
        gestures.cancel();
        gestureResult = null;
        scrubbing = false;
        handler.removeCallbacks(refreshProgress);
        handler.removeCallbacks(hideControls);
        handler.removeCallbacks(hideFeedback);
        feedback.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    protected void onStop() {
        outputStarted = false;
        BufferedSmbMedia cache = bufferedSmbMedia;
        if (cache != null) cache.setCachingEnabled(false);
        cancelFrameExtraction();
        if (frameExtractor != null) {
            frameExtractor.close();
            frameExtractor = null;
        }
        videoLayout.clearSeekStill();
        if (receiverRegistered) {
            unregisterReceiver(noisyReceiver);
            receiverRegistered = false;
        }
        feedback.setVideoSurface(null);
        if (mediaPlayer != null) {
            mediaPlayer.getVLCVout().detachViews();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroying = true;
        if (frameExtractor != null) frameExtractor.close();
        exactFrame = null;
        updateSeekCache(false);
        titleExecutor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            mediaPlayer.setEventListener(null);
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        videoLayout.release();
        if (mediaDescriptor != null) {
            try {
                mediaDescriptor.close();
            } catch (IOException ignored) {
            }
        }
        mediaExecutor.execute(() -> {
            if (bufferedSmbMedia != null) {
                try {
                    bufferedSmbMedia.close();
                } catch (IOException ignored) {
                }
                bufferedSmbMedia = null;
            }
        });
        mediaExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (mediaPlayer != null) {
            state.putLong("position", playbackPosition());
        }
        state.putBoolean("playing", wantsPlay);
        state.putFloat("rate", playbackRate);
        state.putInt("fit", fitMode);
        state.putFloat("brightness", getWindow().getAttributes().screenBrightness);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        keepFullscreen(getWindow());
        cancelSeekPreview();
        gestures.cancel();
        gestureResult = null;
        applyVideoScale();
    }

    private void startPlayback() {
        if (mediaPlayer == null || !mediaReady || failed || !resumed || previewing
                || !mediaPlayer.getVLCVout().areViewsAttached()) {
            return;
        }
        if (wantsPlay && !hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) {
                wantsPlay = false;
                updatePlayPause(false);
                Toast.makeText(this, R.string.no_audio_focus, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (ended) {
            mediaPlayer.stop();
            ended = false;
        }
        started = true;
        if (wantsPlay) cancelFrameExtraction();
        mediaPlayer.play();
        if (wantsPlay && mediaPlayer.isPlaying()) commitPlaybackSeek();
    }

    private long playbackPosition() {
        return pendingPlaybackSeek >= 0 ? pendingPlaybackSeek
                : mediaPlayer == null ? 0 : Math.max(0, mediaPlayer.getTime());
    }

    private void commitPlaybackSeek() {
        if (pendingPlaybackSeek < 0 || !canSeek()) return;
        long target = pendingPlaybackSeek;
        pendingPlaybackSeek = -1;
        videoLayout.requestSeekFrame();
        mediaPlayer.setTime(target, false);
    }

    private void pauseByUser() {
        wantsPlay = false;
        cancelSeekPreview();
        gestures.cancel();
        gestureResult = null;
        ignoreTouch = true;
        updateSeekCache(false);
        resumeAfterFocusLoss = false;
        if (mediaPlayer != null && started && !failed && !ended) {
            mediaPlayer.pause();
        }
        abandonAudioFocus();
        setControlsVisible(true);
    }

    private void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            hasFocus = true;
            if (resumeAfterFocusLoss && resumed) {
                resumeAfterFocusLoss = false;
                wantsPlay = true;
                startPlayback();
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            resumeAfterFocusLoss = change != AudioManager.AUDIOFOCUS_LOSS && wantsPlay;
            hasFocus = false;
            wantsPlay = false;
            cancelSeekPreview();
            gestures.cancel();
            gestureResult = null;
            ignoreTouch = true;
            updateSeekCache(false);
            if (mediaPlayer != null && started && !failed && !ended) {
                mediaPlayer.pause();
            }
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        }
    }

    private void abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
    }

    private void onPlayerEvent(MediaPlayer.Event event) {
        if (mediaPlayer == null || isFinishing() || failed) {
            return;
        }
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                bufferFull = false;
                status.setText(R.string.opening_media);
                status.setVisibility(View.VISIBLE);
                break;
            case MediaPlayer.Event.Buffering:
                int percent = Math.round(event.getBuffering());
                bufferFull = event.getBuffering() >= 100f;
                status.setText(getString(R.string.buffering, percent));
                status.setVisibility(percent < 100 && !previewing ? View.VISIBLE : View.GONE);
                break;
            case MediaPlayer.Event.Playing:
                if (!resumed || !wantsPlay || !hasFocus || recoveringOutput || previewing) {
                    mediaPlayer.pause();
                    return;
                }
                commitPlaybackSeek();
                mediaPlayer.setRate(playbackRate);
                applyVideoScale();
                updateVideoFrameRate();
                status.setVisibility(View.GONE);
                updatePlayPause(true);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                scheduleHideControls();
                break;
            case MediaPlayer.Event.Paused:
                videoLayout.setPlaybackActive(false);
                videoLayout.setVideoFrameRate(0);
                status.setVisibility(View.GONE);
                if (previewing) break;
                if (videoLayout.getVideoFrameCount() == 0) {
                    requestExactFrame(Math.max(pendingResumePosition, playbackPosition()), false);
                }
                updatePlayPause(false);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setControlsVisible(true);
                break;
            case MediaPlayer.Event.EndReached:
                videoLayout.setPlaybackActive(false);
                videoLayout.setVideoFrameRate(0);
                ended = true;
                wantsPlay = false;
                status.setVisibility(View.GONE);
                updatePlayPause(false);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                abandonAudioFocus();
                setControlsVisible(true);
                break;
            case MediaPlayer.Event.EncounteredError:
                showError(R.string.playback_error);
                break;
            case MediaPlayer.Event.Vout:
            case MediaPlayer.Event.ESSelected:
                applyVideoScale();
                updateVideoFrameRate();
                videoLayout.requestStillFrame();
                break;
            case MediaPlayer.Event.TimeChanged:
                if (!mediaPlayer.isPlaying()) {
                    videoLayout.requestStillFrame();
                }
                break;
            default:
                break;
        }
        if (pendingResumePosition > 0 && canSeek()) {
            long target = Math.min(pendingResumePosition, mediaPlayer.getLength());
            bufferFull = false;
            updateSeekCache(false);
            if (mediaPlayer.setTime(target) >= 0) {
                pendingResumePosition = 0;
            }
        }
        updateProgress();
    }

    private boolean canSeek() {
        return mediaPlayer != null && !failed && !ended
                && mediaPlayer.isSeekable() && mediaPlayer.getLength() > 0;
    }

    private void updateProgress() {
        long duration = mediaPlayer.getLength();
        long position = playbackPosition();
        time.setText(getString(R.string.time_separator, PlaybackInput.formatTime(position),
                duration > 0 ? PlaybackInput.formatTime(duration) : getString(R.string.unknown_duration)));
        seekBar.setEnabled(canSeek());
        findViewById(R.id.seek_back).setEnabled(canSeek());
        findViewById(R.id.seek_forward).setEnabled(canSeek());
        if (!scrubbing) {
            seekBar.setProgress(duration > 0 ? (int) (position * 1000.0 / duration) : 0);
        }
        updateSeekCache(resumed && wantsPlay && hasFocus && bufferFull && !failed && !ended
                && mediaPlayer.isPlaying());
    }

    private void updateSeekCache(boolean active) {
        BufferedSmbMedia cache = bufferedSmbMedia;
        if (cache != null && mediaPlayer != null) {
            cache.updatePlayback(playbackPosition(), mediaPlayer.getLength(), active);
        }
    }

    private void seekTo(long target) {
        bufferFull = false;
        pendingResumePosition = 0;
        BufferedSmbMedia cache = bufferedSmbMedia;
        if (cache != null) {
            cache.updatePlayback(target, mediaPlayer.getLength(), false);
        }
        if ((!wantsPlay || previewing) && requestExactFrame(target, previewing)) {
            // Exact stills already decode this target. Repeated paused MediaCodec flushes can
            // exhaust VLC's hardware input buffers; seek its playback decoder only on resume.
            pendingPlaybackSeek = target;
            updateProgress();
            return;
        }
        if (wantsPlay && !mediaPlayer.isPlaying()) {
            pendingPlaybackSeek = target;
            return;
        }
        pendingPlaybackSeek = -1;
        videoLayout.requestSeekFrame();
        mediaPlayer.setTime(target, false);
    }

    private boolean requestExactFrame(long target, boolean warmNeighbors) {
        if (!resumed || destroying) return false;
        if (frameExtractor == null) {
            Uri uri = getIntent().getData();
            boolean local = "file".equalsIgnoreCase(uri.getScheme()) || "content".equalsIgnoreCase(uri.getScheme());
            if (!local && bufferedSmbMedia == null) return false;
            try {
                ParcelFileDescriptor descriptor = bufferedSmbMedia == null ? null
                        : bufferedSmbMedia.openIndependentDescriptor();
                frameExtractor = new SeekFrameExtractor(getApplicationContext(), uri, descriptor,
                        new SeekFrameExtractor.Listener() {
                            @Override
                            public void onFrame(long requestId, long targetMs, Bitmap bitmap) {
                                handler.post(() -> {
                                    if (requestId != exactRequest || destroying || !resumed
                                            || (!previewing && wantsPlay)) return;
                                    exactFrame = bitmap;
                                    refineGestureFrameRate();
                                    videoLayout.setSeekStill(bitmap, fitMode);
                                });
                            }

                            @Override
                            public void onUnavailable(long requestId) {
                                handler.post(() -> {
                                    if (requestId != exactRequest || destroying || !resumed || !canSeek()) return;
                                    exactFrame = null;
                                    videoLayout.clearSeekStill();
                                    videoLayout.requestSeekFrame();
                                    pendingPlaybackSeek = -1;
                                    mediaPlayer.setTime(exactTarget, false);
                                    if (previewing) showFeedback(TextUtils.concat(SeekFeedback.format(exactTarget,
                                            previewStartPosition, true), "\n", getString(R.string.seek_frame_unavailable)));
                                });
                            }
                        });
            } catch (IOException | RuntimeException exception) {
                return false;
            }
        }
        exactTarget = target;
        frameExtractor.request(++exactRequest, target, videoLayout.getWidth(), videoLayout.getHeight(), warmNeighbors);
        return true;
    }

    private void cancelFrameExtraction() {
        exactRequest++;
        if (frameExtractor != null) frameExtractor.cancel();
        exactFrame = null;
    }

    private void refineGestureFrameRate() {
        double rate = frameExtractor == null ? 0 : frameExtractor.getFrameRate();
        if (rate > 0 && rate <= 1000) gestures.refineFrameRate((int) Math.round(rate * 1000000), 1000000);
    }

    private void previewSeek(long target, long start) {
        if (!previewing) {
            previewing = true;
            previewStartPosition = start;
            seekPreview.begin(start);
            bufferFull = false;
            updateSeekCache(false);
            mediaPlayer.pause();
            videoLayout.setPlaybackActive(false);
        }
        seekPreview.offer(target);
        handler.removeCallbacks(renderSeekPreview);
        handler.post(renderSeekPreview);
    }

    private void finishSeekPreview(long target) {
        previewing = false;
        seekPreview.cancel();
        handler.removeCallbacks(renderSeekPreview);
        BufferedSmbMedia cache = bufferedSmbMedia;
        if (canSeek()) seekTo(target);
        if (cache != null) {
            cache.updatePlayback(target, mediaPlayer == null ? 0 : mediaPlayer.getLength(), false);
            cache.clearSeekPreview();
        }
        if (wantsPlay && resumed) startPlayback();
        handler.removeCallbacks(hideFeedback);
        handler.postDelayed(hideFeedback, 1500);
    }

    private void cancelSeekPreview() {
        if (previewing) finishSeekPreview(previewStartPosition);
    }

    private void skip(long delta) {
        if (canSeek()) {
            long start = playbackPosition();
            long target = Math.max(0, Math.min(mediaPlayer.getLength(), start + delta));
            seekTo(target);
            showFeedback(SeekFeedback.format(target, start));
        }
        scheduleHideControls();
    }

    private float currentBrightness() {
        float brightness = getWindow().getAttributes().screenBrightness;
        if (brightness < 0) {
            brightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
        }
        return Math.max(.01f, Math.min(1f, brightness));
    }

    private void setBrightness(float brightness) {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = brightness;
        getWindow().setAttributes(attributes);
    }

    private void setLocked(boolean value) {
        locked = value;
        cancelSeekPreview();
        gestures.cancel();
        gestureResult = null;
        unlock.setVisibility(value ? View.VISIBLE : View.GONE);
        setControlsVisible(!value);
        if (value) {
            showFeedback(getString(R.string.locked_hint));
        }
    }

    private void setControlsVisible(boolean visible) {
        visible = visible && !locked;
        header.setVisibility(visible ? View.VISIBLE : View.GONE);
        controls.setVisibility(visible ? View.VISIBLE : View.GONE);
        keepFullscreen(getWindow());
        scheduleHideControls();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            keepFullscreen(getWindow());
        }
    }

    private void keepFullscreen(Window window) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void scheduleHideControls() {
        handler.removeCallbacks(hideControls);
        if (mediaPlayer != null && mediaPlayer.isPlaying() && !locked && !scrubbing && !dialogOpen
                && controls.getVisibility() == View.VISIBLE) {
            handler.postDelayed(hideControls, 4000);
        }
    }

    private void showFeedback(CharSequence text) {
        feedback.setText(text);
        feedback.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideFeedback);
        if (!previewing) handler.postDelayed(hideFeedback, 1500);
    }

    private void updatePlayPause(boolean playing) {
        playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPause.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    private void updateSpeedButton() {
        TextView speed = findViewById(R.id.speed);
        String label = getString(R.string.speed_value, String.format(Locale.getDefault(), "%s", playbackRate));
        speed.setText(label);
        speed.setContentDescription(getString(R.string.current_speed, label));
        speed.setTooltipText(speed.getContentDescription());
    }

    private void attachVideoOutput() {
        if (!outputStarted || mediaPlayer == null || failed || videoLayout.getVideoSurface() == null
                || mediaPlayer.getVLCVout().areViewsAttached()) {
            return;
        }
        mediaPlayer.getVLCVout().setVideoSurface(videoLayout.getVideoSurface(), null);
        applyVideoScale();
        // No layout callback selects VLC's GL vout, retaining its rotation, SAR and subtitle composition.
        mediaPlayer.getVLCVout().attachViews(null);
        // A rapid reattach can precede VLC's asynchronous Vout(0) event; its automatic enable then misses it.
        mediaPlayer.setVideoTrackEnabled(true);
    }

    private void applyVideoScale() {
        int width = videoLayout.getWidth();
        int height = videoLayout.getHeight();
        if (mediaPlayer == null || width <= 0 || height <= 0) {
            return;
        }
        mediaPlayer.getVLCVout().setWindowSize(width, height);
        videoLayout.requestStillFrame();
        mediaPlayer.setAspectRatio(null);
        mediaPlayer.setScale(0);
        IMedia.VideoTrack track = mediaPlayer.getCurrentVideoTrack();
        if (track == null) {
            videoLayout.setVideoGeometry(0, 0, 0, 0, false);
            videoLayout.setVideoCrop(false);
            return;
        }
        boolean swapped = track.orientation == IMedia.VideoTrack.Orientation.LeftBottom
                || track.orientation == IMedia.VideoTrack.Orientation.RightTop
                || track.orientation == IMedia.VideoTrack.Orientation.LeftTop
                || track.orientation == IMedia.VideoTrack.Orientation.RightBottom;
        videoLayout.setVideoGeometry(track.width, track.height, track.sarNum, track.sarDen, swapped);
        if (exactFrame != null && (previewing || !wantsPlay)) videoLayout.setSeekStill(exactFrame, fitMode);
        // Reduce before crossing into VLC: its native DAR-to-SAR products use unsigned 32-bit ints.
        // The vout applies this to its already-oriented display geometry, not the encoded axes.
        mediaPlayer.setAspectRatio(VideoAspect.vlcAspectRatio(swapped ? track.height : track.width,
                swapped ? track.width : track.height, swapped ? track.sarDen : track.sarNum,
                swapped ? track.sarNum : track.sarDen));
        if (fitMode == 0) {
            videoLayout.setVideoCrop(false);
            return;
        }
        if (fitMode == 2) {
            videoLayout.setVideoCrop(false);
            mediaPlayer.setAspectRatio(VideoAspect.vlcAspectRatio(width, height, 1, 1));
        } else {
            double videoWidth = track.width * (track.sarNum > 0 && track.sarDen > 0
                    ? (double) track.sarNum / track.sarDen : 1);
            double videoHeight = track.height;
            double scale = swapped ? Math.max(width / videoHeight, height / videoWidth)
                    : Math.max(width / videoWidth, height / videoHeight);
            // VLC clamps absolute zoom at 10x. Keep its higher-resolution native crop otherwise.
            boolean cropInShader = Double.isFinite(scale) && scale > 10;
            videoLayout.setVideoCrop(cropInShader);
            if (Double.isFinite(scale) && scale > 0) {
                mediaPlayer.setScale(cropInShader ? 0 : (float) scale);
            }
        }
    }

    private void updateVideoFrameRate() {
        IMedia.VideoTrack track = mediaPlayer.getCurrentVideoTrack();
        boolean active = mediaPlayer.isPlaying() && resumed && wantsPlay && !previewing;
        videoLayout.setPlaybackActive(active);
        videoLayout.setVideoFrameRate(active && track != null && track.frameRateDen > 0
                ? (float) ((double) track.frameRateNum / track.frameRateDen * playbackRate) : 0);
    }

    private void showSpeedDialog() {
        if (mediaPlayer == null || failed) {
            return;
        }
        float[] rates = {.25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = new String[rates.length];
        int selected = 3;
        for (int i = 0; i < rates.length; i++) {
            labels[i] = getString(R.string.speed_value, String.format(Locale.getDefault(), "%s", rates[i]));
            if (Math.abs(playbackRate - rates[i]) < .01f) {
                selected = i;
            }
        }
        showPlayerDialog(new AlertDialog.Builder(this).setTitle(R.string.playback_speed)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    mediaPlayer.setRate(rates[which]);
                    playbackRate = mediaPlayer.getRate();
                    updateVideoFrameRate();
                    updateSpeedButton();
                    if (Math.abs(playbackRate - rates[which]) > .01f) {
                        Toast.makeText(this, R.string.speed_unavailable, Toast.LENGTH_SHORT).show();
                    }
                    dialog.dismiss();
                }).setNegativeButton(R.string.cancel, null).create());
    }

    private void showTrackTypeDialog() {
        if (mediaPlayer == null || failed) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.tracks)
                .setItems(new String[]{getString(R.string.audio_tracks), getString(R.string.subtitle_tracks)},
                        (parent, which) -> {
                            parent.dismiss();
                            handler.post(() -> showTracks(which == 0));
                        }).create();
        showPlayerDialog(dialog);
    }

    private void showTracks(boolean audio) {
        if (mediaPlayer == null || isFinishing()) {
            return;
        }
        MediaPlayer.TrackDescription[] tracks = audio ? mediaPlayer.getAudioTracks() : mediaPlayer.getSpuTracks();
        if (tracks == null || tracks.length == 0) {
            Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_SHORT).show();
            return;
        }
        int current = audio ? mediaPlayer.getAudioTrack() : mediaPlayer.getSpuTrack();
        String[] labels = new String[tracks.length];
        int selected = -1;
        for (int i = 0; i < tracks.length; i++) {
            labels[i] = tracks[i].name;
            if (tracks[i].id == current) {
                selected = i;
            }
        }
        showPlayerDialog(new AlertDialog.Builder(this)
                .setTitle(audio ? R.string.audio_tracks : R.string.subtitle_tracks)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    boolean success = audio ? mediaPlayer.setAudioTrack(tracks[which].id)
                            : mediaPlayer.setSpuTrack(tracks[which].id);
                    if (!success) {
                        Toast.makeText(this, R.string.track_unavailable, Toast.LENGTH_SHORT).show();
                    }
                    dialog.dismiss();
                }).setNegativeButton(R.string.cancel, null).create());
    }

    private void showPlayerDialog(AlertDialog dialog) {
        dialogOpen = true;
        handler.removeCallbacks(hideControls);
        dialog.setOnDismissListener(ignored -> {
            dialogOpen = false;
            keepFullscreen(getWindow());
            scheduleHideControls();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        dialog.show();
        if (window != null) {
            keepFullscreen(window);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    private void showError(int message) {
        if (failed || isFinishing()) {
            return;
        }
        failed = true;
        wantsPlay = false;
        cancelSeekPreview();
        cancelFrameExtraction();
        updateSeekCache(false);
        status.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        abandonAudioFocus();
        if (mediaPlayer != null) {
            mediaPlayer.stop();
        }
        showPlayerDialog(new AlertDialog.Builder(this).setMessage(message)
                .setPositiveButton(R.string.close, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()).create());
    }
}