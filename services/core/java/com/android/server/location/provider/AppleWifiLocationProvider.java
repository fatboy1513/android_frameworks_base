package com.android.server.location.provider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationResult;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// 导入与您的 .proto 文件完全匹配的类
import com.android.server.location.provider.schema.AppleWloc.Request;
import com.android.server.location.provider.schema.AppleWloc.Response;
import com.android.server.location.provider.schema.AppleWloc.WifiDevice;

/**
 * A network location provider that communicates with Apple's location services.
 */
public class AppleWifiLocationProvider extends AbstractLocationProvider implements Runnable {

    private static final String TAG = "AppleWifiLocationProvider";
    private static final String APPLE_URL = "https://gs-loc.apple.com/clls/wloc";
    private static final long MIN_INTERVAL_MS = 10000L; // 定义我们能接受的最小间隔为10秒

    private final Context mContext;
    private final ScheduledExecutorService mExecutor;
    private final WifiManager mWifiManager;
    private final BroadcastReceiver mWifiScanReceiver;

    private boolean mIsRunning = false;
    private long mRequestIntervalMs = Long.MAX_VALUE; // 保存来自App的原始请求间隔
    private Future<?> mScheduledFuture; // 用于控制我们的主循���任务
    private List<ScanResult> mScanResults;

    public AppleWifiLocationProvider(Context context, ScheduledExecutorService executor) {
        super(executor,
              CallerIdentity.forTest(Process.SYSTEM_UID, 0, "system", null),
              new ProviderProperties.Builder()
                  .setHasNetworkRequirement(true)
                  .setHasCellRequirement(false)
                  .setHasSatelliteRequirement(false)
                  .setHasMonetaryCost(false)
                  .setHasAltitudeSupport(true)
                  .setHasSpeedSupport(false)
                  .setHasBearingSupport(false)
                  .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                  .setAccuracy(ProviderProperties.ACCURACY_COARSE)
                  .build(),
              Collections.emptySet());

        this.mContext = context;
        this.mExecutor = executor;
        this.mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        this.mWifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                    mExecutor.execute(() -> handleScanResults());
                }
            }
        };
        Log.d(TAG, "AppleWifiLocationProvider instance created.");
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "Provider started.");
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mContext.registerReceiver(mWifiScanReceiver, filter);
        setState(state -> state.withAllowed(true));
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "Provider stopped.");
        mContext.unregisterReceiver(mWifiScanReceiver);
        mIsRunning = false;
        if (mScheduledFuture != null) {
            mScheduledFuture.cancel(false);
            mScheduledFuture = null;
        }
        setState(state -> state.withAllowed(false));
    }

    @Override
    public void onSetRequest(ProviderRequest request) {
        // 停止任何已存在的调度任务，这对于正确应用新的间隔至关重要
        if (mScheduledFuture != null) {
            mScheduledFuture.cancel(false);
            mScheduledFuture = null;
        }

        if (request.isActive()) {
            mRequestIntervalMs = request.getIntervalMillis();
            mIsRunning = true;
            // 立即触发一次定位周期。该周期会在run()方���的finally块中自行处理重新调度
            mExecutor.execute(this);
        } else {
            mIsRunning = false;
            mRequestIntervalMs = Long.MAX_VALUE;
        }
    }

    @Override
    public void onFlush(Runnable callback) {
        mExecutor.execute(callback);
    }

    @Override
    public void onExtraCommand(int uid, int pid, String command, Bundle extras) {}

    @Override
    public void run() {
        if (!mIsRunning) {
            return; // 如果Provider已被停止，则退出
        }

        try {
            // 核心定位逻辑
            mWifiManager.startScan(); // Wi-Fi扫描会异步地调用 handleScanResults
        } finally {
            // 仅当Provider仍然应该运行时才重新调度
            if (mIsRunning) {
                // "节流"：取我们定义的最小间隔和App请求间隔中较大的那个
                long effectiveInterval = Math.max(mRequestIntervalMs, MIN_INTERVAL_MS);

                // 调度下一次执行
                mScheduledFuture = mExecutor.schedule(this, effectiveInterval, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void handleScanResults() {
        if (!mIsRunning) return;

        // // 1. 检查网络连接状态
        // ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        // if (cm == null) {
        //     Log.e(TAG, "ConnectivityManager is not available.");
        //     return;
        // }
        // NetworkInfo activeNetwork = cm.getActiveNetworkInfo();

        // // 2. 如果网络未连接，则记录一条日志并直接放弃本次机会
        // if (activeNetwork == null || !activeNetwork.isConnected()) {
        //     Log.w(TAG, "No network connection, skipping location request for this cycle.");
        //     return;
        // }

        // 3. 只有在网络连接正常时，才继续获取并处理扫描结果
        mScanResults = mWifiManager.getScanResults();
        if (mScanResults != null && !mScanResults.isEmpty()) {
            byte[] requestBody = buildAppleApiRequestPayload();
            if (requestBody != null) {
                makeNetworkRequest(requestBody);
            }
        }
    }

    private void makeNetworkRequest(byte[] payload) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(APPLE_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000); // 设置10秒连接超时
            conn.setReadTimeout(10000);    // 设置10秒读取超时
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "locationd/1753.17");
            conn.setFixedLengthStreamingMode(payload.length);

            try (DataOutputStream wr = new DataOutputStream(conn.getOutputStream())) {
                wr.write(payload);
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream is = conn.getInputStream()) {
                    Location location = parseApiResponse(is);
                    if (location != null) {
                        reportLocation(LocationResult.wrap(location));
                    } else {
                        Log.w(TAG, "Failed to get location from network response.");
                    }
                }
            } else {
                Log.e(TAG, "Network request failed with code: " + responseCode + " " + conn.getResponseMessage());
            }
        } catch (IOException e) {
            Log.e(TAG, "Network request failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private byte[] buildAppleApiRequestPayload() {
        if (mScanResults == null || mScanResults.isEmpty()) {
            return null;
        }

        Request.Builder request = Request.newBuilder();
        request.setUnknownValue1(0);
        request.setReturnSingleResult(1);
        for (ScanResult scanResult : mScanResults) {
            if (scanResult.BSSID != null && !scanResult.BSSID.isEmpty()) {
                WifiDevice.Builder device = WifiDevice.newBuilder();
                device.setBssid(scanResult.BSSID);
                request.addWifiDevices(device);
            }
        }

        if (request.getWifiDevicesCount() == 0) {
            Log.w(TAG, "No valid BSSIDs to build request payload.");
            return null;
        }

        byte[] serializedProto = request.build().toByteArray();

        //data = b"\x00\x01\x00\x05"+b"en_US"+b"\x00\x13"+b"com.apple.locationd"+b"\x00\x0a"+b"8.1.12B411"+b"\x00\x00\x00\x01\x00\x00\x00" + bytes((length_serialized_apple_wloc,)) + serialized_apple_wloc;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.write(new byte[]{0x00, 0x01});
            dos.writeShort(5);
            dos.write("en_US".getBytes(StandardCharsets.UTF_8));
            dos.writeShort(19);
            dos.write("com.apple.locationd".getBytes(StandardCharsets.UTF_8));
            dos.writeShort(10);
            dos.write("8.1.12B411".getBytes(StandardCharsets.UTF_8));
            dos.writeInt(1);
            dos.writeInt(serializedProto.length);
            dos.write(serializedProto);

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Could not build binary request payload", e);
            return null;
        }
    }

    private Location mergeLocations(List<WifiDevice.Location> locations) {
        if (locations == null || locations.isEmpty()) {
            return null;
        }

        // 如果只有一个有效位置，直接使用它
        if (locations.size() == 1) {
            WifiDevice.Location deviceLocation = locations.get(0);
            Location location = new Location(android.location.LocationManager.NETWORK_PROVIDER);
            location.setLatitude(deviceLocation.getLatitude() / 1e8);
            location.setLongitude(deviceLocation.getLongitude() / 1e8);
            if (deviceLocation.hasUnknownValue3()) {
                location.setAccuracy((float) deviceLocation.getUnknownValue3());
            }
            if (deviceLocation.hasUnknownValue4()) {
                location.setAltitude(deviceLocation.getUnknownValue4());
            }
            if (deviceLocation.hasUnknownValue5()) {
                location.setVerticalAccuracyMeters((float) deviceLocation.getUnknownValue5());
            }
            location.setTime(System.currentTimeMillis());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            return location;
        }

        // 使用加权平均法融合多个位置
        // 权重 = 1 / 精度值 (精度越高，权重越大)
        double totalWeight = 0;
        double weightedLatSum = 0;
        double weightedLonSum = 0;
        
        double totalAccuracy = 0;
        double totalAltitude = 0;
        double totalVerticalAccuracy = 0;
        int altitudeCount = 0;
        int verticalAccuracyCount = 0;

        for (WifiDevice.Location loc : locations) {
            double accuracy = loc.hasUnknownValue3() ? (float) loc.getUnknownValue3() : 1000.0; // 默认一个较大的不精确值
            if (accuracy <= 0) continue; // 忽略无效精度

            double weight = 1.0 / accuracy;
            totalWeight += weight;
            weightedLatSum += (loc.getLatitude() / 1e8) * weight;
            weightedLonSum += (loc.getLongitude() / 1e8) * weight;
            
            totalAccuracy += accuracy;

            if (loc.hasUnknownValue4()) {
                totalAltitude += loc.getUnknownValue4();
                altitudeCount++;
            }
            if (loc.hasUnknownValue5()) {
                totalVerticalAccuracy += (float) loc.getUnknownValue5();
                verticalAccuracyCount++;
            }
        }

        if (totalWeight == 0) {
            Log.w(TAG, "No valid locations with positive accuracy to merge.");
            return null;
        }

        Location finalLocation = new Location(android.location.LocationManager.NETWORK_PROVIDER);
        finalLocation.setLatitude(weightedLatSum / totalWeight);
        finalLocation.setLongitude(weightedLonSum / totalWeight);
        finalLocation.setAccuracy((float) (totalAccuracy / locations.size())); // 最终精度设为平均值

        if (altitudeCount > 0) {
            finalLocation.setAltitude(totalAltitude / altitudeCount);
        }
        if (verticalAccuracyCount > 0) {
            finalLocation.setVerticalAccuracyMeters((float) (totalVerticalAccuracy / verticalAccuracyCount));
        }

        finalLocation.setTime(System.currentTimeMillis());
        finalLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        return finalLocation;
    }

    private Location parseApiResponse(InputStream inputStream) {
        try {
            long skipped = inputStream.skip(10);
            if (skipped != 10) {
                Log.e(TAG, "Could not skip 10-byte header from response stream.");
                return null;
            }

            Response protoResponse = Response.parseFrom(inputStream);

            if (protoResponse == null || protoResponse.getWifiDevicesCount() == 0) {
                Log.w(TAG, "Protobuf response contained no locations.");
                return null;
            }

            List<WifiDevice.Location> validLocations = new ArrayList<>();
            for (WifiDevice wifiDevice : protoResponse.getWifiDevicesList()) {
                if (wifiDevice.hasLocation()) {
                    WifiDevice.Location loc = wifiDevice.getLocation();
                    
                    // 过滤逻辑：当且仅当4个值同时为无效值时，才丢弃该数据点
                    boolean isInvalid = loc.getLatitude() == -18000000000L &&
                                        loc.getLongitude() == -18000000000L &&
                                        loc.getUnknownValue3() == -1 &&
                                        loc.getUnknownValue5() == -1;

                    if (!isInvalid) {
                        validLocations.add(loc);
                    }
                }
            }

            if (validLocations.isEmpty()) {
                Log.w(TAG, "No valid location points found in the response after filtering.");
                return null;
            }

            // 调用融合函数返回最终结果
            return mergeLocations(validLocations);

        } catch (IOException e) {
            Log.e(TAG, "Failed to parse response", e);
            return null;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {}
}