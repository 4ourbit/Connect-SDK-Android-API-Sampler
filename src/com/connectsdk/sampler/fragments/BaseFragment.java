//
//  Connect SDK Sample App by LG Electronics
//
//  To the extent possible under law, the person who associated CC0 with
//  this sample app has waived all copyright and related or neighboring rights
//  to the sample app.
//
//  You should have received a copy of the CC0 legalcode along with this
//  work. If not, see http://creativecommons.org/publicdomain/zero/1.0/.
//

package com.connectsdk.sampler.fragments;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.Toast;

import com.connectsdk.core.AppInfo;
import com.connectsdk.core.ExternalInputInfo;
import com.connectsdk.device.ConnectableDevice;
import com.connectsdk.discovery.DiscoveryManager;
import com.connectsdk.sampler.R;
import com.connectsdk.service.capability.ExternalInputControl;
import com.connectsdk.service.capability.KeyControl;
import com.connectsdk.service.capability.Launcher;
import com.connectsdk.service.capability.MediaControl;
import com.connectsdk.service.capability.MediaPlayer;
import com.connectsdk.service.capability.MouseControl;
import com.connectsdk.service.capability.PowerControl;
import com.connectsdk.service.capability.TVControl;
import com.connectsdk.service.capability.TextInputControl;
import com.connectsdk.service.capability.ToastControl;
import com.connectsdk.service.capability.VolumeControl;
import com.connectsdk.service.capability.WebAppLauncher;
import com.connectsdk.service.capability.listeners.ResponseListener;
import com.connectsdk.service.command.ServiceCommandError;
import com.connectsdk.service.command.ServiceSubscription;

import java.util.Arrays;
import java.util.List;

public class BaseFragment extends Fragment {

    private ConnectableDevice mTv, mStereo;
    private Launcher launcher;
    private MediaPlayer mediaPlayer;
    private MediaControl mediaControl;
    private TVControl tvControl;
    private VolumeControl volumeControl, stereoVolumeControl;
    private ToastControl toastControl;
    private MouseControl mouseControl;
    private TextInputControl textInputControl;
    private PowerControl powerControl;
    private ExternalInputControl externalInputControl, stereoInputControl;
    private KeyControl keyControl;
    private WebAppLauncher webAppLauncher;
    public Button[] buttons;
    Context mContext;

    static ServiceSubscription<VolumeControl.VolumeListener> volumeSubscription;
    static ServiceSubscription<VolumeControl.MuteListener> muteSubscription;
    static ServiceSubscription<Launcher.AppInfoListener> runningAppSubscription;

    public BaseFragment() {}

    public BaseFragment(Context context)
    {
        mContext = context;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (savedInstanceState != null) {
            String tvId = savedInstanceState.getString("tvId");
            // TODO restore Tv instance
            // if (tvId != null) mTv = DiscoveryManager.getInstance();
            String stereoId = savedInstanceState.getString("stereoId");
            // TODO restore Stereo instance
            // if (stereoId != null) mStereo = DiscoveryManager.getInstance();
        }

        setDevices(mTv, mStereo);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mTv != null)
            outState.putString("tvId", mTv.getId());
        if (mStereo != null)
            outState.putString("stereoId", mStereo.getId());
    }

    public void setDevices(ConnectableDevice tv, ConnectableDevice stereo)
    {
        if (volumeSubscription != null) {
            volumeSubscription.unsubscribe();
            volumeSubscription = null;
        }
        if (muteSubscription != null) {
            muteSubscription.unsubscribe();
            muteSubscription = null;
        }
        if (runningAppSubscription != null) {
            runningAppSubscription.unsubscribe();
            runningAppSubscription = null;
        }

        mTv = tv;

        if (tv == null) {
            launcher = null;
            mediaPlayer = null;
            mediaControl = null;
            tvControl = null;
            volumeControl = null;
            toastControl = null;
            textInputControl = null;
            mouseControl = null;
            externalInputControl = null;
            powerControl = null;
            keyControl = null;
            webAppLauncher = null;

            disableButtons();
        }
        else {
            launcher = mTv.getCapability(Launcher.class);
            mediaPlayer = mTv.getCapability(MediaPlayer.class);
            mediaControl = mTv.getCapability(MediaControl.class);
            tvControl = mTv.getCapability(TVControl.class);
            volumeControl = mTv.getCapability(VolumeControl.class);
            toastControl = mTv.getCapability(ToastControl.class);
            textInputControl = mTv.getCapability(TextInputControl.class);
            mouseControl = mTv.getCapability(MouseControl.class);
            externalInputControl = mTv.getCapability(ExternalInputControl.class);
            powerControl = mTv.getCapability(PowerControl.class);
            keyControl = mTv.getCapability(KeyControl.class);
            webAppLauncher = mTv.getCapability(WebAppLauncher.class);

            enableButtons();
        }

        mStereo = stereo;

        if (stereo == null) {
            stereoVolumeControl = null;
        } else {
            stereoVolumeControl = mStereo.getCapability(VolumeControl.class);
            stereoInputControl = mStereo.getCapability(ExternalInputControl.class);
        }

        if ((getStereo() != null) && (getTv() != null)) {

            if (getTv().hasCapability(VolumeControl.Volume_Subscribe)
                    && getStereo().hasCapability(VolumeControl.Volume_Set))
                volumeSubscription = getVolumeControl().subscribeVolume(volumeListener);

            if (getTv().hasCapability(VolumeControl.Mute_Subscribe)
                    && getStereo().hasCapability(VolumeControl.Mute_Set))
                muteSubscription = getVolumeControl().subscribeMute(muteListener);

            if (getTv().hasCapability(Launcher.RunningApp_Subscribe)
                    && getStereo().hasCapability(ExternalInputControl.List)
                    && getStereo().hasCapability(ExternalInputControl.Set)) {
                runningAppSubscription = getLauncher().subscribeRunningApp(appOnInputFilterListener);
            }
        }
    }

    public void disableButtons() {
        if (buttons != null)
        {
            for (Button button : buttons)
            {
                button.setOnClickListener(null);
                button.setEnabled(false);
            }
        }
    }

    public void enableButtons() {
        if (buttons != null)
        {
            for (Button button : buttons)
                button.setEnabled(true);
        }
    }

    private VolumeControl.VolumeListener volumeListener = new VolumeControl.VolumeListener() {
        Float previousVolume = -1f;

        @Override
        public void onSuccess(final Float object) {
            if (!previousVolume.equals(object)) { // debounce
                getStereoVolumeControl().setVolume(object, new ResponseListener<Object>() {
                    @Override
                    public void onSuccess(Object response) {
                        Log.d("Denon", "Successful setting volume: " + response);
                        previousVolume = object;
                    }

                    @Override
                    public void onError(ServiceCommandError error) {
                        Log.d("Denon", "Error setting volume: " + error.getMessage());
                    }
                });
            }
        }

        @Override
        public void onError(ServiceCommandError error) {
            Log.d("LG", "Error subscribing to volume: " + error.getMessage());
        }
    };

    private VolumeControl.MuteListener muteListener = new VolumeControl.MuteListener() {

        @Override
        public void onSuccess(Boolean object) {
            getStereoVolumeControl().setMute(object, new ResponseListener<Object>() {
                @Override
                public void onSuccess(Object response) {
                    Log.d("Denon", "Successful setting mute: " + response);
                }

                @Override
                public void onError(ServiceCommandError error) {
                    Log.d("Denon", "Error setting mute: " + error.getMessage());
                }
            });
        }

        @Override
        public void onError(ServiceCommandError error) {
            Log.d("LG", "Error subscribing to mute: " + error.getMessage());
        }
    };

    private AppOnInputFilter appOnInputFilterListener = new AppOnInputFilter(new Pair[]{
            new Pair<>("com.webos.app.hdmi3", "AUX1"),
            new Pair<>("com.webos.app.livetv", "DIGITAL_IN"),
            new Pair<>("com.webos.app.smartshare", "DIGITAL_IN"),
            new Pair<>("youtube.leanback.v4", "DIGITAL_IN")}) {

        @Override
        public void onSuccess(Pair<AppInfo, ExternalInputInfo> object) {
            setStereoInput(object.second);
        }

        @Override
        public void onError(ServiceCommandError error) {
            Log.d("LG", error.getMessage());
        }
    };

    private void setStereoInput(ExternalInputInfo info) {
        final String denonIconData = getDenonIconData(info.getIconURL());
        getStereoInputControl().setExternalInput(info, new ResponseListener<Object>() {
            @Override
            public void onSuccess(Object object) {
                getToastControl().showToast((String) object, denonIconData, "png", null);
                Toast.makeText(getContext(), (String) object, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(ServiceCommandError error) {
                Log.d("Denon", "Error setting external input: " + error.getMessage());
            }
        });
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return false;
    }

    public ConnectableDevice getTv()
    {
        return mTv;
    }

    public ConnectableDevice getStereo()
    {
        return mStereo;
    }

    public Launcher getLauncher()
    {
        return launcher;
    }

    public MediaPlayer getMediaPlayer()
    {
        return mediaPlayer;
    }

    public MediaControl getMediaControl()
    {
        return mediaControl;
    }

    public VolumeControl getVolumeControl() 
    {
        return volumeControl;
    }

    public VolumeControl getStereoVolumeControl()
    {
        return stereoVolumeControl;
    }

    public TVControl getTVControl()
    {
        return tvControl;
    }

    public ToastControl getToastControl() 
    {
        return toastControl;
    }

    public TextInputControl getTextInputControl() 
    {
        return textInputControl;
    }

    public MouseControl getMouseControl() 
    {
        return mouseControl;
    }

    public ExternalInputControl getExternalInputControl()
    {
        return externalInputControl;
    }

    public ExternalInputControl getStereoInputControl() {
        return stereoInputControl;
    }

    public PowerControl getPowerControl()
    {
        return powerControl;
    }

    public KeyControl getKeyControl() 
    {
        return keyControl;
    }

    public WebAppLauncher getWebAppLauncher() 
    {
        return webAppLauncher;
    }

    public Context getContext()
    {
        return mContext;
    }

    protected void disableButton(final Button button) {
        button.setEnabled(false);
    }

    protected String getDenonIconData(String iconURL) {
        // TODO set iconURL in Denon Service to R string
        return mContext.getString(R.string.denon_icon_data);
    }

    public interface AppOnInputFilterListener {
        abstract public void onSuccess(Pair<AppInfo, ExternalInputInfo> object);
    }

    abstract public class AppOnInputFilter implements AppOnInputFilterListener, Launcher.AppInfoListener {
        private Pair[] appInfoInputInfoIdPairs;

        public AppOnInputFilter(Pair<String, String>[] appInfoInputInfoIdPairs) {
            this.appInfoInputInfoIdPairs = appInfoInputInfoIdPairs;
        }

        @Override
        final public void onSuccess(final AppInfo appInfo) {
            getStereoInputControl().getExternalInputList(new ExternalInputControl.ExternalInputListListener() {
                @Override
                public void onSuccess(List<ExternalInputInfo> inputInfoList) {
                    for (ExternalInputInfo inputInfo : inputInfoList) {
                        Pair<String, String> current = new Pair<>(appInfo.getId(), inputInfo.getId());
                        if (Arrays.asList(AppOnInputFilter.this.appInfoInputInfoIdPairs).contains(current)) {
                            AppOnInputFilter.this.onSuccess(new Pair<>(appInfo, inputInfo));
                            break;
                        }
                    }
                }

                @Override
                public void onError(ServiceCommandError error) {
                    Log.d("Denon", "Error getting external input list: " + error.getMessage());
                    AppOnInputFilter.this.onError(error);
                }
            });
        }
    }
}