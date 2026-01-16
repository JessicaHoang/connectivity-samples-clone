package com.google.location.nearby.apps.walkietalkie;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent; // Share button function: import
import android.media.AudioManager;
import android.net.Uri; // Share button function: import
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.activity.result.ActivityResultLauncher; // Share button function: import
import androidx.activity.result.contract.ActivityResultContracts; // Share button function: import
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.collection.SimpleArrayMap; // Share button function: import
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import android.text.SpannableString;
import android.text.format.DateFormat;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log; // Share button function: import
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.Button; // Share button function: import
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback; // Share button function: import
import com.google.android.gms.nearby.connection.PayloadTransferUpdate; // Share button function: import
import com.google.android.gms.nearby.connection.Strategy;

import java.io.File; // Share button function: import
import java.io.FileOutputStream; // Share button function: import
import java.io.IOException;
import java.io.InputStream; // Share button function: import
import java.io.OutputStream; // Share button function: import
import java.nio.charset.StandardCharsets; // Share button function: import
import java.util.HashMap; // Share button function: import
import java.util.Map; // Share button function: import
import java.util.Random;

/**
 * Our WalkieTalkie Activity. This Activity has 3 {@link State}s.
 *
 * <p>{@link State#UNKNOWN}: We cannot do anything while we're in this state. The app is likely in
 * the background.
 *
 * <p>{@link State#SEARCHING}: Our default state (after we've connected). We constantly listen for a
 * device to advertise near us, while simultaneously advertising ourselves.
 *
 * <p>{@link State#CONNECTED}: We've connected to another device and can now talk to them by holding
 * down the volume keys and speaking into the phone. Advertising and discovery have both stopped.
 */
public class MainActivity extends ConnectionsActivity {
  /** If true, debug logs are shown on the device. */
  private static final boolean DEBUG = true;

  /**
   * The connection strategy we'll use for Nearby Connections. In this case, we've decided on
   * P2P_STAR, which is a combination of Bluetooth Classic and WiFi Hotspots.
   */
  private static final Strategy STRATEGY = Strategy.P2P_STAR;

  /** Length of state change animations. */
  private static final long ANIMATION_DURATION = 600;

  /**
   * A set of background colors. We'll hash the authentication token we get from connecting to a
   * device to pick a color randomly from this list. Devices with the same background color are
   * talking to each other securely (with 1/COLORS.length chance of collision with another pair of
   * devices).
   */
  @ColorInt
  private static final int[] COLORS =
      new int[] {
        0xFFF44336 /* red */,
        0xFF9C27B0 /* deep purple */,
        0xFF00BCD4 /* teal */,
        0xFF4CAF50 /* green */,
        0xFFFFAB00 /* amber */,
        0xFFFF9800 /* orange */,
        0xFF795548 /* brown */
      };

  /**
   * This service id lets us find other nearby devices that are interested in the same thing. Our
   * sample does exactly one thing, so we hardcode the ID.
   */
  private static final String SERVICE_ID =
      "com.google.location.nearby.apps.walkietalkie.automatic.SERVICE_ID";

  /**
   * The state of the app. As the app changes states, the UI will update and advertising/discovery
   * will start/stop.
   */
  private State mState = State.UNKNOWN;

  /** A random UID used as this device's endpoint name. */
  private String mName;

  /**
   * The background color of the 'CONNECTED' state. This is randomly chosen from the {@link #COLORS}
   * list, based off the authentication token.
   */
  @ColorInt private int mConnectedColor = COLORS[0];

  /** Displays the previous state during animation transitions. */
  private TextView mPreviousStateView;

  /** Displays the current state. */
  private TextView mCurrentStateView;

  /** An animator that controls the animation from previous state to current state. */
  @Nullable private Animator mCurrentAnimator;

  /** A running log of debug messages. Only visible when DEBUG=true. */
  private TextView mDebugLogView;

  /** Share button function: Share button*/
  private Button mShareButton;

  /** Share button function: Activity result launcher for image picking */
  private ActivityResultLauncher<Intent> imagePickerLauncher;

  /** Share button function: Stores incoming file payloads in a map.*/
  private final Map<Long, Payload> incomingFilePayloads = new HashMap<>();

  /** Share button function: dispatching PayloadCallback*/
    @Override
    protected PayloadCallback getPayloadCallback() {
        return new PayloadCallback() {

            /** Share button function: onPayloadReceived only took in STREAM payloads, so
             * this one is for receiving FILE and BYTES is a different manner as FILE will need
             * onPayloadTransferUpdate*/
            @Override
            public void onPayloadReceived(String endpointId, Payload payload) {
                if (payload.getType() == Payload.Type.STREAM) {
                    audioPayloadCallback.onPayloadReceived(endpointId, payload);
                } else if (payload.getType() == Payload.Type.FILE
                        || payload.getType() == Payload.Type.BYTES) {
                    filePayloadCallback.onPayloadReceived(endpointId, payload);
                }
            }

            /** Share button function: onPayloadTransferUpdate processes the FILE payloads
             * in filePayloadCallback.*/
            @Override
            public void onPayloadTransferUpdate(
                    String endpointId, PayloadTransferUpdate update) {

                filePayloadCallback.onPayloadTransferUpdate(endpointId, update);
                audioPayloadCallback.onPayloadTransferUpdate(endpointId, update);
            }
        };
    }

    private final PayloadCallback audioPayloadCallback =
            new PayloadCallback() {
                @Override
                public void onPayloadReceived(String endpointId, Payload payload) {
                    if (payload.getType() == Payload.Type.STREAM) {
                        mAudioPlayer = new AudioPlayer(payload.asStream().asInputStream());
                        mAudioPlayer.start();
                    }
                }

                @Override
                public void onPayloadTransferUpdate(
                        String endpointId, PayloadTransferUpdate update) { }
            };

    private final ReceiveFilePayloadCallback filePayloadCallback =
            new ReceiveFilePayloadCallback(this);



    /** Listens to holding/releasing the volume rocker. */
  private final GestureDetector mGestureDetector =
      new GestureDetector(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP) {
        @Override
        protected void onHold() {
          logV("onHold");
          startRecording();
        }

        @Override
        protected void onRelease() {
          logV("onRelease");
          stopRecording();
        }
      };

  /** For recording audio as the user speaks. */
  @Nullable private AudioRecorder mRecorder;

  /** For playing audio from other users nearby. */
  @Nullable private AudioPlayer mAudioPlayer;

  /** The phone's original media volume. */
  private int mOriginalVolume;


  /** Share button function: Sends an image file to all connected devices. */
  private void sendImageFile(Uri uri) {
      logE(">>> MainActivity Sending file payload fired " + uri, null);
      try {
          // Open the file as a read-only ParcelFileDescriptor
          ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
          if (pfd == null) {
              logE("ParcelFileDescriptor for image URI was null.", null);
              return;
          }
          Payload filePayload = Payload.fromFile(pfd);

          // Send the payload to all connected endpoints.
          send(filePayload);
          logV("Sent image file with URI: " + uri);
          Toast.makeText(this, "Sending file...", Toast.LENGTH_SHORT).show();

      } catch (IOException e) {
          logE("Failed to send image file.", e);
      }
  }


    @Override
  protected void onCreate(Bundle savedInstanceState) {
        Log.e("TEST", ">>> MainActivity onCreate fired");
        super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    getSupportActionBar()
        .setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.actionBar));

    mPreviousStateView = (TextView) findViewById(R.id.previous_state);
    mCurrentStateView = (TextView) findViewById(R.id.current_state);

    mDebugLogView = (TextView) findViewById(R.id.debug_log);
    mDebugLogView.setVisibility(DEBUG ? View.VISIBLE : View.GONE);
    mDebugLogView.setMovementMethod(new ScrollingMovementMethod());
    // Share Button Function:
    mShareButton = findViewById(R.id.shareButton);

    // Share Button Function: Share button is hidden until devices are connected.
    mShareButton.setVisibility(View.GONE);
      // Share Button Function: to select photo to share
      imagePickerLauncher =
              registerForActivityResult(
                      new ActivityResultContracts.StartActivityForResult(),
                      result -> {
                          if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                              Uri imageUri = result.getData().getData();
                              if (imageUri != null) {
                                  sendImageFile(imageUri);
                              }
                          }
                      });

      // Share Button Function: Share button listener to open up the image picker upon clicking.
      mShareButton.setOnClickListener(v -> {
          Intent intent = new Intent(Intent.ACTION_PICK);
          intent.setType("image/*");
          imagePickerLauncher.launch(intent);
      });
    mName = generateRandomName();

    ((TextView) findViewById(R.id.name)).setText(mName);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (mState == State.CONNECTED && mGestureDetector.onKeyEvent(event)) {
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  protected void onStart() {
    Log.e("TEST", ">>> MainActivity start() fired");
    super.onStart();

    // Set the media volume to max.
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    mOriginalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);

    setState(State.SEARCHING);
  }

  @Override
  protected void onStop() {
    // Restore the original volume.
    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
    setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);

    // Stop all audio-related threads
    if (isRecording()) {
      stopRecording();
    }
    if (isPlaying()) {
      stopPlaying();
    }

    // After our Activity stops, we disconnect from Nearby Connections.
    setState(State.UNKNOWN);

    if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
      mCurrentAnimator.cancel();
    }

    super.onStop();
  }

  @Override
  public void onBackPressed() {
    if (getState() == State.CONNECTED) {
      setState(State.SEARCHING);
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onEndpointDiscovered(Endpoint endpoint) {
    // We found an advertiser!
      Log.e("TEST", ">>> MainActivity onEndpointFound fired: " + endpoint);
      stopDiscovering();
    connectToEndpoint(endpoint);
  }

  @Override
  protected void onConnectionInitiated(Endpoint endpoint, ConnectionInfo connectionInfo) {
    // A connection to another device has been initiated! We'll use the auth token, which is the
    // same on both devices, to pick a color to use when we're connected. This way, users can
    // visually see which device they connected with.
      Log.e("TEST", ">>> MainActivity onConnectionInitiated fired");
      mConnectedColor = COLORS[connectionInfo.getAuthenticationToken().hashCode() % COLORS.length];

    // We accept the connection immediately.
    acceptConnection(endpoint);
    // ShareButton shows up once connection is established.
    runOnUiThread(() -> mShareButton.setVisibility(View.VISIBLE));
  }

  @Override
  protected void onEndpointConnected(Endpoint endpoint) {
    Toast.makeText(
            this, getString(R.string.toast_connected, endpoint.getName()), Toast.LENGTH_SHORT)
        .show();
    setState(State.CONNECTED);
  }

  @Override
  protected void onEndpointDisconnected(Endpoint endpoint) {
    Toast.makeText(
            this, getString(R.string.toast_disconnected, endpoint.getName()), Toast.LENGTH_SHORT)
        .show();
    setState(State.SEARCHING);
  }

  @Override
  protected void onConnectionFailed(Endpoint endpoint) {
    // Let's try someone else.
      Log.e("TEST", ">>> MainActivity onConnectionFailed fired");
      if (getState() == State.SEARCHING) {
      startDiscovering();
    }
  }

  /**
   * The state has changed. I wonder what we'll be doing now.
   *
   * @param state The new state.
   */
  private void setState(State state) {
    if (mState == state) {
      logW("State set to " + state + " but already in that state");
      return;
    }

    logD("State set to " + state);
    State oldState = mState;
    mState = state;
    onStateChanged(oldState, state);
  }

  /** @return The current state. */
  private State getState() {
    return mState;
  }

  /**
   * State has changed.
   *
   * @param oldState The previous state we were in. Clean up anything related to this state.
   * @param newState The new state we're now in. Prepare the UI for this state.
   */
  private void onStateChanged(State oldState, State newState) {
    if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
      mCurrentAnimator.cancel();
    }

    // Update Nearby Connections to the new state.
    switch (newState) {
      case SEARCHING:
        disconnectFromAllEndpoints();
        startDiscovering();
        startAdvertising();
        break;
      case CONNECTED:
        stopDiscovering();
        stopAdvertising();
        break;
      case UNKNOWN:
        stopAllEndpoints();
        break;
      default:
        // no-op
        break;
    }

    // Update the UI.
    switch (oldState) {
      case UNKNOWN:
        // Unknown is our initial state. Whatever state we move to,
        // we're transitioning forwards.
        transitionForward(oldState, newState);
        break;
      case SEARCHING:
        switch (newState) {
          case UNKNOWN:
            transitionBackward(oldState, newState);
            break;
          case CONNECTED:
            transitionForward(oldState, newState);
            break;
          default:
            // no-op
            break;
        }
        break;
      case CONNECTED:
        // Connected is our final state. Whatever new state we move to,
        // we're transitioning backwards.
        transitionBackward(oldState, newState);
        break;
    }
  }

  /** Transitions from the old state to the new state with an animation implying moving forward. */
  @UiThread
  private void transitionForward(State oldState, final State newState) {
    mPreviousStateView.setVisibility(View.VISIBLE);
    mCurrentStateView.setVisibility(View.VISIBLE);

    updateTextView(mPreviousStateView, oldState);
    updateTextView(mCurrentStateView, newState);

    if (ViewCompat.isLaidOut(mCurrentStateView)) {
      mCurrentAnimator = createAnimator(false /* reverse */);
      mCurrentAnimator.addListener(
          new AnimatorListener() {
            @Override
            public void onAnimationEnd(Animator animator) {
              updateTextView(mCurrentStateView, newState);
            }
          });
      mCurrentAnimator.start();
    }
  }

  /** Transitions from the old state to the new state with an animation implying moving backward. */
  @UiThread
  private void transitionBackward(State oldState, final State newState) {
    mPreviousStateView.setVisibility(View.VISIBLE);
    mCurrentStateView.setVisibility(View.VISIBLE);

    updateTextView(mCurrentStateView, oldState);
    updateTextView(mPreviousStateView, newState);

    if (ViewCompat.isLaidOut(mCurrentStateView)) {
      mCurrentAnimator = createAnimator(true /* reverse */);
      mCurrentAnimator.addListener(
          new AnimatorListener() {
            @Override
            public void onAnimationEnd(Animator animator) {
              updateTextView(mCurrentStateView, newState);
            }
          });
      mCurrentAnimator.start();
    }
  }

  @NonNull
  private Animator createAnimator(boolean reverse) {
    Animator animator;
    if (Build.VERSION.SDK_INT >= 21) {
      int cx = mCurrentStateView.getMeasuredWidth() / 2;
      int cy = mCurrentStateView.getMeasuredHeight() / 2;
      int initialRadius = 0;
      int finalRadius = Math.max(mCurrentStateView.getWidth(), mCurrentStateView.getHeight());
      if (reverse) {
        int temp = initialRadius;
        initialRadius = finalRadius;
        finalRadius = temp;
      }
      animator =
          ViewAnimationUtils.createCircularReveal(
              mCurrentStateView, cx, cy, initialRadius, finalRadius);
    } else {
      float initialAlpha = 0f;
      float finalAlpha = 1f;
      if (reverse) {
        float temp = initialAlpha;
        initialAlpha = finalAlpha;
        finalAlpha = temp;
      }
      mCurrentStateView.setAlpha(initialAlpha);
      animator = ObjectAnimator.ofFloat(mCurrentStateView, "alpha", finalAlpha);
    }
    animator.addListener(
        new AnimatorListener() {
          @Override
          public void onAnimationCancel(Animator animator) {
            mPreviousStateView.setVisibility(View.GONE);
            mCurrentStateView.setAlpha(1);
          }

          @Override
          public void onAnimationEnd(Animator animator) {
            mPreviousStateView.setVisibility(View.GONE);
            mCurrentStateView.setAlpha(1);
          }
        });
    animator.setDuration(ANIMATION_DURATION);
    return animator;
  }

  /** Updates the {@link TextView} with the correct color/text for the given {@link State}. */
  @UiThread
  private void updateTextView(TextView textView, State state) {
    switch (state) {
      case SEARCHING:
        textView.setBackgroundResource(R.color.state_searching);
        textView.setText(R.string.status_searching);
        break;
      case CONNECTED:
        textView.setBackgroundColor(mConnectedColor);
        textView.setText(R.string.status_connected);
        break;
      default:
        textView.setBackgroundResource(R.color.state_unknown);
        textView.setText(R.string.status_unknown);
        break;
    }
  }

  /** {@see ConnectionsActivity#onReceive(Endpoint, Payload). Changed to accomodate FILE payloads} */
  @Override
  protected void onReceive(Endpoint endpoint, Payload payload) {
      Log.e("TEST", ">>> MainActivity onReceive fired, type=" + payload.getType());
      switch (payload.getType()) {

          case Payload.Type.STREAM:
              // --- ORIGINAL AUDIO LOGIC (unchanged) ---
              if (mAudioPlayer != null) {
                  mAudioPlayer.stop();
                  mAudioPlayer = null;
              }

              AudioPlayer player =
                      new AudioPlayer(payload.asStream().asInputStream()) {

                          @WorkerThread
                          @Override
                          protected void onFinish() {
                              runOnUiThread(
                                      new Runnable() {
                                          @UiThread
                                          @Override
                                          public void run() {
                                              mAudioPlayer = null;
                                          }
                                      });
                          }
                      };

              mAudioPlayer = player;
              player.start();
              break;

          case Payload.Type.FILE:
              // --- NEW FILE LOGIC ---
              long id = payload.getId();
              incomingFilePayloads.put(id, payload);
              logV("Received FILE payload (waiting for completion). ID=" + id);
              break;

          case Payload.Type.BYTES:
              // Not used
              break;
      }
  }

  /** Share button function: since the completion of the transfer is indicated when
   * PayloadCallback.onPayloadTransferUpdate, this was created to handle the File Payloads. */
    @Override
    protected void onTransferUpdate(
            Endpoint endpoint, PayloadTransferUpdate update) {

        if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {

            long payloadId = update.getPayloadId();
            Payload payload = incomingFilePayloads.remove(payloadId);

            if (payload != null && payload.getType() == Payload.Type.FILE) {
                processFilePayload(payloadId, payload);
            }
        }
    }

    /** Share button function: This method was for after the transfer was successful
     * to process the FILE payloads. */
    private void processFilePayload(long payloadId, Payload payload) {

        String filename = ReceiveFilePayloadCallback.filePayloadFilenames.remove(payloadId);
        if (filename == null) filename = "received_image";

        Uri uri = payload.asFile().asUri();

        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out =
                     new FileOutputStream(new File(getCacheDir(), filename))) {

            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            logV("Saved file: " + filename);

        } catch (IOException e) {
            logE("File receive failed", e);
        } finally {
            getContentResolver().delete(uri, null, null);
        }
    }

    /** Stops all currently streaming audio tracks. */
  private void stopPlaying() {
    logV("stopPlaying()");
    if (mAudioPlayer != null) {
      mAudioPlayer.stop();
      mAudioPlayer = null;
    }
  }

  /** @return True if currently playing. */
  private boolean isPlaying() {
    return mAudioPlayer != null;
  }

  /** Starts recording sound from the microphone and streaming it to all connected devices. */
  private void startRecording() {
    logV("startRecording()");
    try {
      ParcelFileDescriptor[] payloadPipe = ParcelFileDescriptor.createPipe();

      // Send the first half of the payload (the read side) to Nearby Connections.
      send(Payload.fromStream(payloadPipe[0]));

      // Use the second half of the payload (the write side) in AudioRecorder.
      mRecorder = new AudioRecorder(payloadPipe[1]);
      mRecorder.start();
    } catch (IOException e) {
      logE("startRecording() failed", e);
    }
  }

  /** Stops streaming sound from the microphone. */
  private void stopRecording() {
    logV("stopRecording()");
    if (mRecorder != null) {
      mRecorder.stop();
      mRecorder = null;
    }
  }

  /** @return True if currently streaming from the microphone. */
  private boolean isRecording() {
    return mRecorder != null && mRecorder.isRecording();
  }

  /** {@see ConnectionsActivity#getRequiredPermissions()} */
  @Override
  protected String[] getRequiredPermissions() {
    return join(
        super.getRequiredPermissions(),
        Manifest.permission.RECORD_AUDIO);
  }

  /** Joins 2 arrays together. */
  private static String[] join(String[] a, String... b) {
    String[] join = new String[a.length + b.length];
    System.arraycopy(a, 0, join, 0, a.length);
    System.arraycopy(b, 0, join, a.length, b.length);
    return join;
  }

  /**
   * Queries the phone's contacts for their own profile, and returns their name. Used when
   * connecting to another device.
   */
  @Override
  protected String getName() {
    return mName;
  }

  /** {@see ConnectionsActivity#getServiceId()} */
  @Override
  public String getServiceId() {
    return SERVICE_ID;
  }

  /** {@see ConnectionsActivity#getStrategy()} */
  @Override
  public Strategy getStrategy() {
    return STRATEGY;
  }

  @Override
  protected void logV(String msg) {
    super.logV(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_verbose)));
  }

  @Override
  protected void logD(String msg) {
    super.logD(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_debug)));
  }

  @Override
  protected void logW(String msg) {
    super.logW(msg);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_warning)));
  }

  @Override
  protected void logW(String msg, Throwable e) {
    super.logW(msg, e);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_warning)));
  }

  @Override
  protected void logE(String msg, Throwable e) {
    super.logE(msg, e);
    appendToLogs(toColor(msg, getResources().getColor(R.color.log_error)));
  }

  private void appendToLogs(CharSequence msg) {
    mDebugLogView.append("\n");
    mDebugLogView.append(DateFormat.format("hh:mm", System.currentTimeMillis()) + ": ");
    mDebugLogView.append(msg);
  }

  private static CharSequence toColor(String msg, int color) {
    SpannableString spannable = new SpannableString(msg);
    spannable.setSpan(new ForegroundColorSpan(color), 0, msg.length(), 0);
    return spannable;
  }

  private static String generateRandomName() {
    String name = "";
    Random random = new Random();
    for (int i = 0; i < 5; i++) {
      name += random.nextInt(10);
    }
    return name;
  }

  /**
   * Provides an implementation of Animator.AnimatorListener so that we only have to override the
   * method(s) we're interested in.
   */
  private abstract static class AnimatorListener implements Animator.AnimatorListener {
    @Override
    public void onAnimationStart(Animator animator) {}

    @Override
    public void onAnimationEnd(Animator animator) {}

    @Override
    public void onAnimationCancel(Animator animator) {}

    @Override
    public void onAnimationRepeat(Animator animator) {}
  }

  /** States that the UI goes through. */
  public enum State {
    UNKNOWN,
    SEARCHING,
    CONNECTED
  }

  /** Share button function: acceptConnection() takes in one payload. This was created to
   * implement both onPayloadReceived and onPayloadTransferUpdate as well as other methods
   * to process the FILE payloads. Now acceptConnection can take one argument.
   * */
    static class ReceiveFilePayloadCallback extends PayloadCallback {

        private final Context context;
        private final SimpleArrayMap<Long, Payload> incomingFilePayloads =
                new SimpleArrayMap<>();
        private final SimpleArrayMap<Long, Payload> completedFilePayloads =
                new SimpleArrayMap<>();
        private static final SimpleArrayMap<Long, String> filePayloadFilenames =
                new SimpleArrayMap<>();

        ReceiveFilePayloadCallback(Context context) {
            this.context = context;
        }

        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                String payloadFilenameMessage =
                        null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    payloadFilenameMessage = new String(payload.asBytes(), StandardCharsets.UTF_8);
                }
                long payloadId = addPayloadFilename(payloadFilenameMessage);
                processFilePayload(payloadId);
            } else if (payload.getType() == Payload.Type.FILE) {
                incomingFilePayloads.put(payload.getId(), payload);
            }
        }

        private long addPayloadFilename(String payloadFilenameMessage) {
            String[] parts = payloadFilenameMessage.split(":");
            long payloadId = Long.parseLong(parts[0]);
            String filename = parts[1];
            filePayloadFilenames.put(payloadId, filename);
            return payloadId;
        }

        private void processFilePayload(long payloadId) {
            Payload filePayload = completedFilePayloads.get(payloadId);
            String filename = filePayloadFilenames.get(payloadId);

            if (filePayload != null && filename != null) {
                completedFilePayloads.remove(payloadId);
                filePayloadFilenames.remove(payloadId);

                Uri uri = filePayload.asFile().asUri();
                try (InputStream in =
                             context.getContentResolver().openInputStream(uri);
                     OutputStream out =
                             new FileOutputStream(
                                     new File(context.getCacheDir(), filename))) {
                    copyStream(in, out);
                } catch (IOException e) {
                    Log.e("FileReceive", "Failed to save file", e);
                } finally {
                    context.getContentResolver().delete(uri, null, null);
                }
            }
        }

        @Override
        public void onPayloadTransferUpdate(

                String endpointId, PayloadTransferUpdate update) {
            Log.e("TEST", ">>> MainActivity onPayloadTransferUpdate fired, status=" + update.getStatus());


            if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                long payloadId = update.getPayloadId();
                Payload payload = incomingFilePayloads.remove(payloadId);
                completedFilePayloads.put(payloadId, payload);

                if (payload.getType() == Payload.Type.FILE) {
                    processFilePayload(payloadId);
                }
            }
        }

        private static void copyStream(InputStream in, OutputStream out)
                throws IOException {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }


}
