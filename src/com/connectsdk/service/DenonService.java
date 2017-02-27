package com.connectsdk.service;

import android.text.Html;
import android.util.Xml;

import com.connectsdk.core.ExternalInputInfo;
import com.connectsdk.core.Util;
import com.connectsdk.discovery.DiscoveryFilter;
import com.connectsdk.etc.helper.HttpConnection;
import com.connectsdk.service.capability.CapabilityMethods;
import com.connectsdk.service.capability.ExternalInputControl;
import com.connectsdk.service.capability.Launcher;
import com.connectsdk.service.capability.VolumeControl;
import com.connectsdk.service.capability.listeners.ResponseListener;
import com.connectsdk.service.command.ServiceCommand;
import com.connectsdk.service.command.ServiceCommandError;
import com.connectsdk.service.command.ServiceSubscription;
import com.connectsdk.service.config.ServiceConfig;
import com.connectsdk.service.config.ServiceDescription;
import com.connectsdk.service.sessions.LaunchSession;

import junit.framework.Assert;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;


public class DenonService extends DeviceService implements VolumeControl, ExternalInputControl {
    public static final String ID = "Denon";

    public static final int VOLUME_LIMITER = -40; // [-80, -20]

    interface TARGET_PATH {
        String INFO       = "/goform/Deviceinfo.xml";
        String POWER      = "/goform/formiPhoneAppPower.xml?%s+%s";  // zone[1, 2] value[PowerOn, PowerStandby]
        String VOLUME     = "/goform/formiPhoneAppVolume.xml?%s+%s"; // zone[1, 2] value[-79, 0]
        String MUTE       = "/goform/formiPhoneAppMute.xml?%s+%s";   // zone[1, 2] value[MuteOn, MuteOff]
        String TUNER      = "/goform/formiPhoneAppTuner.xml?%s+%s";  // zone[1, 2] value[PRESETUP, PRESETDOWN]
        String DIRECT_CMD = "/goform/formiPhoneAppDirect.xml?%s";    // cmd[CMD]
        String POST_CMD   = "/goform/AppCommand.xml";
    }

    interface CMD {
        //power
        String POWER_ON = "PWON";
        String POWER_OFF = "PWSTANDBY";
        String REQUEST_POWER_STATUS = "PW?";

        //volume
        String VOLUME_UP = "MVUP";
        String VOLUME_DOWN = "MVDOWN";
        String REQUEST_VOLUM_STATUS = "MV?";

        //mute
        String MUTE_ON = "MUON";
        String MUTE_OFF = "MUOFF";
        String REQUEST_MUTE_STATUS = "MU?";

        //input
        String SOURCE_CD = "SICD";
        String SOURCE_AUX1 = "SIAUX1";
        String SOURCE_AUX2 = "SIAUX2";
        String SOURCE_DIGITAL = "SIDIGITAL_IN";
        String REQUEST_SOURCE_STATUS = "SI?";

        //sleep timer
        String SLEEP_OFF = "SLPOFF";
        String SLEEP_IN = "SLP";
        String REQUEST_SLEEP_STATUS = "SLP?";
    }

    private BufferedWriter out = null;
    private BufferedReader in = null;
    private Semaphore semaphore = new Semaphore(1);
    private int timeout = 250; // Chrome Developer Tools TTFB

    public DenonService(ServiceDescription serviceDescription, ServiceConfig serviceConfig) {
        super(serviceDescription, serviceConfig);
    }

    public static DiscoveryFilter discoveryFilter() {
        return new DiscoveryFilter(ID, "urn:schemas-upnp-org:device:MediaRenderer:1");
    }

    @Override
    public CapabilityPriorityLevel getPriorityLevel(Class<? extends CapabilityMethods> clazz) {

        if (clazz.equals(VolumeControl.class))
            return getVolumeControlCapabilityLevel();
        if (clazz.equals(ExternalInputControl.class))
            return getExternalInputControlPriorityLevel();
        return CapabilityPriorityLevel.NOT_SUPPORTED;
    }

    @Override
    protected void updateCapabilities() {
        List<String> capabilities = new ArrayList<String>();

        capabilities.add(Volume_Set);
        capabilities.add(Volume_Get);
        capabilities.add(Volume_Up_Down);
        capabilities.add(Volume_Subscribe);
        capabilities.add(Mute_Get);
        capabilities.add(Mute_Set);
        capabilities.add(Mute_Subscribe);

        capabilities.add(ExternalInputControl.List);
        capabilities.add(ExternalInputControl.Set);

        setCapabilities(capabilities);
    }


    @Override
    public void setServiceDescription(ServiceDescription serviceDescription) {
        super.setServiceDescription(serviceDescription);
    }

    @Override
    public void sendCommand(final ServiceCommand<?> mCommand) {
        final ServiceCommand<ResponseListener<Object>> serviceCommand = (ServiceCommand<ResponseListener<Object>>) mCommand;
        final Object payload = serviceCommand.getPayload();

        try {
            if (semaphore.tryAcquire(timeout, TimeUnit.MILLISECONDS)){
                Util.runInBackground(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            StringBuilder sb = new StringBuilder();
                            sb.append("http://").append(getServiceDescription().getIpAddress());
                            sb.append(serviceCommand.getTarget());

                            HttpConnection connection = HttpConnection.newInstance(URI.create(sb.toString()));
                            connection.setHeader("Content-Type", "text/xml; charset=utf-8");
                            connection.execute();

                            int code = connection.getResponseCode();
                            if (code == HttpURLConnection.HTTP_OK) {
                                Util.postSuccess(serviceCommand.getResponseListener(), connection.getResponseString());
                            } else {
                                Util.postError(serviceCommand.getResponseListener(), ServiceCommandError.getError(code));
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            Util.postError(serviceCommand.getResponseListener(), new ServiceCommandError(0, e.getMessage(), null));
                        } finally {
                            semaphore.release();
                        }
                    }
                });
            } else {
                String message = "Resource blocked. Discarding command: "+ mCommand.getTarget().toString();
                Util.postError(serviceCommand.getResponseListener(), new ServiceCommandError(0, message, null));
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            Util.postError(serviceCommand.getResponseListener(), new ServiceCommandError(0, e.getMessage(), null));
        };
    }

    private boolean isXmlEncoded(final String xml) {
        if (xml == null || xml.length() < 4) {
            return false;
        }
        return xml.trim().substring(0, 4).equals("&lt;");
    }

    String parseData(String response, String key) {
        if (isXmlEncoded(response)) {
            response = Html.fromHtml(response).toString();
        }
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setInput(new StringReader(response));
            int event;
            boolean isFound = false;
            do {
                event = parser.next();
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if (key.equals(tag)) {
                        isFound = true;
                    }
                } else if (event == XmlPullParser.TEXT && isFound) {
                    return parser.getText();
                }
            } while (event != XmlPullParser.END_DOCUMENT);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public VolumeControl getVolumeControl() {
        return this;
    }

    @Override
    public CapabilityPriorityLevel getVolumeControlCapabilityLevel() {
        return CapabilityPriorityLevel.NORMAL;
    }

    @Override
    public CapabilityPriorityLevel getExternalInputControlPriorityLevel() {
        return CapabilityPriorityLevel.NORMAL;
    }

    @Override
    public void volumeUp(final ResponseListener<Object> listener) {
        String target = String.format(TARGET_PATH.DIRECT_CMD, CMD.VOLUME_UP);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, target, null /*payload*/, listener);
        request.setHttpMethod(ServiceCommand.TYPE_GET);
        request.send();
    }

    @Override
    public void volumeDown(final ResponseListener<Object> listener) {
        String target = String.format(TARGET_PATH.DIRECT_CMD, CMD.VOLUME_DOWN);

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, target, null /*payload*/, listener);
        request.setHttpMethod(ServiceCommand.TYPE_GET);
        request.send();
    }

    @Override
    public void setVolume(float volume, final ResponseListener<Object> listener) {
        String zone = "1";
        String value = String.valueOf(Math.min(VOLUME_LIMITER, (int)(100f*volume-80f)));
        String target = String.format(TARGET_PATH.VOLUME, zone, value);

        ResponseListener<Object> responseListener = new ResponseListener<Object>() {

            @Override
            public void onSuccess(Object response) {
                String currentVolume = parseData((String) response, "MasterVolume");
                float fVolume = -80f;
                try {
                    fVolume = Float.parseFloat(currentVolume);
                } catch (RuntimeException ex) {
                    ex.printStackTrace();
                }
                int masterVolume = (int)(fVolume + 80f);

                Util.postSuccess(listener, masterVolume);
            }

            @Override
            public void onError(ServiceCommandError error) {
                Util.postError(listener, error);
            }
        };

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<>(this, target, null /*payload*/, responseListener);
        request.setHttpMethod(ServiceCommand.TYPE_GET);
        request.send();
    }

    @Override
    public void getVolume(final VolumeListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void setMute(boolean isMute, final ResponseListener<Object> listener) {
        String zone = "1";
        String target = String.format(TARGET_PATH.MUTE, zone, isMute? "MuteOn" : "MuteOff");

        ResponseListener<Object> responseListener = new ResponseListener<Object>() {

            @Override
            public void onSuccess(Object response) {

                String currentMute = parseData((String) response, "Mute");
                try {
                    if (!(currentMute.equalsIgnoreCase("on") || currentMute.equalsIgnoreCase("off"))) {
                        throw new InvalidParameterException();
                    }
                } catch (RuntimeException ex) {
                    ex.printStackTrace();
                }

                Util.postSuccess(listener, currentMute);
            }

            @Override
            public void onError(ServiceCommandError error) {
                Util.postError(listener, error);
            }
        };

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<>(this, target, null /*payload*/, responseListener);
        request.setHttpMethod(ServiceCommand.TYPE_GET);
        request.send();
    }

    @Override
    public void getMute(MuteListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public ServiceSubscription<VolumeListener> subscribeVolume(VolumeListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
        return null;
    }

    @Override
    public ServiceSubscription<MuteListener> subscribeMute(MuteListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
        return null;
    }

    @Override
    public ExternalInputControl getExternalInput() {
        return null;
    }

    @Override
    public void launchInputPicker(Launcher.AppLaunchListener listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    @Override
    public void closeInputPicker(LaunchSession launchSession, ResponseListener<Object> listener) {
        Util.postError(listener, ServiceCommandError.notSupported());
    }

    public static String CMD_ALL_POWER = "GetAllZonePowerStatus";
    public static String CMD_VOLUME_LEVEL = "GetVolumeLevel";
    public static String CMD_MUTE_STATUS = "GetMuteStatus";
    public static String CMD_SET_INPUT_SOURCE = "SetSourceStatus";
    public static String CMD_GET_SOURCE_STATUS = "GetSourceStatus";
    public static String CMD_SURROUND_STATUS = "GetSurroundModeStatus";
    public static String CMD_ZONE_NAME = "GetZoneName";
    public static String CMD_NET_STATUS = "GetNetAudioStatus";
    
    @Override
    public void getExternalInputList(final ExternalInputListListener listener) {
        String zone = "1";
        String target = TARGET_PATH.INFO;

        ResponseListener<Object> responseListener = new ResponseListener<Object>() {

            @Override
            public void onSuccess(Object response) {
                List<ExternalInputInfo> inputInfos = new ArrayList<>();
                try {
                    String xml = (String) response;
                    String list = xml
                            .substring(xml.indexOf("<InputSource>"), xml.indexOf("</InputSource>") + 14)
                            .replaceAll("\r\n", "");
                    InputSource inputSource = new InputSource(new StringReader(list));
                    XPath xPath = XPathFactory.newInstance().newXPath();
                    NodeList sources = (NodeList) xPath.evaluate("InputSource/List/Source", inputSource, XPathConstants.NODESET);
                    for (int i = 0; i < sources.getLength(); i++) {
                        Element source = (Element) sources.item(i);

                        ExternalInputInfo inputInfo = new ExternalInputInfo();
                        try {inputInfo.setName(xPath.evaluate("DefaultName", source));} catch (XPathExpressionException e) {}
                        try {
                            inputInfo.setId(xPath.evaluate("FuncName", source));
                            if ("Digital In".equals(inputInfo.getName())) inputInfo.setId("DIGITAL_IN");
                        } catch (XPathExpressionException e) {}
                        try {inputInfo.setIconURL(xPath.evaluate("IconId", source));} catch (XPathExpressionException e) {}
                        Assert.assertTrue(inputInfo.getId() != null && inputInfo.getId() != "");

                        inputInfos.add(inputInfo);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Util.postSuccess(listener, inputInfos);
            }

            @Override
            public void onError(ServiceCommandError error) {
                Util.postError(listener, error);
            }
        };

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<ResponseListener<Object>>(this, target, null /*payload*/, responseListener);
        request.setHttpMethod(ServiceCommand.TYPE_GET);
        request.send();
    }

    @Override
    public void setExternalInput(final ExternalInputInfo info, final ResponseListener<Object> listener) {
        String cmd = String.format("SI%s", info.getId());
        String target = String.format(TARGET_PATH.DIRECT_CMD, cmd);

        ResponseListener<Object> responseListener = new ResponseListener<Object>() {

            @Override
            public void onSuccess(Object response) {
                Util.postSuccess(listener, info.getName());
            }

            @Override
            public void onError(ServiceCommandError error) {
                Util.postError(listener, error);
            }
        };

        ServiceCommand<ResponseListener<Object>> request = new ServiceCommand<>(this, target, null /*payload*/, responseListener);
        request.setHttpMethod(ServiceCommand.TYPE_GET);
        request.send();
    }
}
