/**
 *
 * test01
 *
 * 既成のデバイス A を自前でハンドリングしてみる
 *
 * - [ALERT ON/OFF」ボタン押下でデバイス A の LED＋ブザーを ON/OFF
 * - デバイス A のボタンが押されたら音と Toast で反応
 *
 */

package jp.klab.test01;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity
        implements View.OnClickListener, Handler.Callback {
    private static final String TAG = "BT";
    private static final String TARGET_ADDR = "FF:FF:00:00:FF:0A"; // 手元のデバイス A 個体

    private static final int SCAN_MODE = ScanSettings.SCAN_MODE_BALANCED;

    private static final int MSG_DOSCAN = 100;
    private static final int MSG_FOUNDDEVICE = 110;
    private static final int MSG_STOPSCAN = 120;
    private static final int MSG_GATTCONNECT = 200;
    private static final int MSG_GATTCONNECTED = 210;
    private static final int MSG_GATTDISCONNECT = 300;
    private static final int MSG_GATTDISCONNECTED = 310;
    private static final int MSG_GATTGOTSERVICE = 400;
    private static final int MSG_ALERT_ON = 500;
    private static final int MSG_ALERT_OFF = 510;
    private static final int MSG_PUSHED = 550;
    private static final int MSG_ERROR = 10;
    private static final int REQ_ENABLE_BT = 0;

    private BluetoothAdapter mBtAdapter = null;
    private BluetoothLeScanner mBtScanner = null;
    private BluetoothGatt mBtGatt = null;
    private BluetoothDevice mBtDevice;
    private Handler mHandler;

    private Context mCtx;
    private ProgressDialog mProgressDlg = null;

    private TextView mTvAddr;
    private TextView mTvRssi;
    private Button mButtonDisconn;
    private Button mButtonConn;
    private Button mButtonAlertOn;
    private Button mButtonAlertOff;

    private BluetoothGattCharacteristic mChAlertLevel = null;
    private BluetoothGattCharacteristic mChUser1 = null;
    private BluetoothGattDescriptor mDescUser1 = null;

    // デバイス A の提供するサービス・キャラクタリスティック群の UUID より
    private UUID mUuidSvcImAlert   = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb");
    private UUID mUuidChAlertLevel = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb");
    private UUID mUuidSvcUser1     = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private UUID mUuidChUser1      = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    // UUID for Client Characteristic Configuration Descriptor
    // - BLUETOOTH SPECIFICATION Version 4.2 [Vol 3, Part G] page 537
    private UUID mUuidCCCD         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // デバイス A への Alert 指示用
    private byte[] mCmdAlertOff = new byte[] {(byte)0x00}; // OFF (No Alert)
    private byte[] mCmdAlertOn  = new byte[] {(byte)0x02}; // ON (High Alert)

    private ScanCallback mScanCallback = new bleScanCallback();
    private BluetoothGattCallback mGattCallback = new bleGattCallback();

    // GATT イベントコールバック
    private class bleGattCallback extends BluetoothGattCallback {
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor desc,
                                      int status) { // writeDescriptor() 結果
            super.onDescriptorWrite(gatt, desc, status);
            Log.d(TAG, "onDescriptorWrite: sts=" + status);
            if (desc == mDescUser1) {
                Log.d(TAG, "onDescriptorWrite: mDescUser1=" + bytesToHexString(desc.getValue()));
                // Alert OFF に初期化
                mHandler.sendEmptyMessage(MSG_ALERT_OFF);
            }
        }
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic ch,
                                         int status) { // readCharacteristic() 結果
            super.onCharacteristicRead(gatt, ch, status);
            Log.d(TAG, "onCharacteristicRead: sts=" + status);
        }
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic ch,
                                          int status) { // writeCharacteristic 結果
            super.onCharacteristicWrite(gatt, ch, status);
            Log.d(TAG, "onCharacteristicWrite: sts=" + status);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                            int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {  // 接続完了
                mHandler.sendEmptyMessage(MSG_GATTCONNECTED);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) { // 切断完了
                mHandler.sendEmptyMessage(MSG_GATTDISCONNECTED);
            }
        }

        @Override
        public void onCharacteristicChanged (BluetoothGatt gatt,
                                             BluetoothGattCharacteristic ch) { // 通知を受信
            Log.d(TAG, "onCharacteristicChanged");
            if (ch == mChUser1) { // デバイス A のユーザ定義キャラクタリスティック 1 からの通知
                Log.d(TAG, "pushed!");
                mHandler.sendEmptyMessage(MSG_PUSHED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) { // GATT サービス一覧取得完了
            super.onServicesDiscovered(gatt, status);

            // デバイス A の Immediate Alert サービスの Alert Level キャラクタリスティックオブジェクトを取得
            BluetoothGattService svc = gatt.getService(mUuidSvcImAlert);
            mChAlertLevel = svc.getCharacteristic(mUuidChAlertLevel);

            // デバイス A のユーザ定義サービス 1 の ユーザ定義キャラクタリスティックの
            // Client ​Characteristic Configulation Descriptor を取得
            svc = gatt.getService(mUuidSvcUser1);
            mChUser1 = svc.getCharacteristic(mUuidChUser1);
            mDescUser1 = mChUser1.getDescriptor(mUuidCCCD);

            // 同キャラクタリスティックの値変更時の通知を有功にして
            // 同 CCCD へ ENABLE_NOTIFICATION_VALUE を書き込んで通知へ待機
            mBtGatt.setCharacteristicNotification(mChUser1, true);
            byte[] val = new byte[] {(byte)0x01, (byte)0x00};
            mDescUser1.setValue(val);
            mBtGatt.writeDescriptor(mDescUser1);

/**  すべての Services - Characteristics - Descriptors をログへ
 List<BluetoothGattService> serviceList = gatt.getServices();

 Log.d(TAG, "onServicesDiscovered: serviceList.size=" + serviceList.size());

 for (BluetoothGattService s : serviceList) {
   Log.d(TAG, "onServicesDiscovered: svc uuid=" + s.getUuid().toString());
   List<BluetoothGattCharacteristic> chlist = s.getCharacteristics();
   Log.d(TAG, "onServicesDiscovered: chrlist.size=" + chlist.size());
     for (BluetoothGattCharacteristic c : chlist) {
       UUID uuid = c.getUuid();
       Log.d(TAG, "onServicesDiscovered:  chr uuid=" + uuid.toString());
       List<BluetoothGattDescriptor> dlist = c.getDescriptors();
         Log.d(TAG, "onServicesDiscovered:  desclist.size=" + dlist.size());
         for (BluetoothGattDescriptor d : dlist) {
           Log.d(TAG, "onServicesDiscovered:   desc uuid=" + d.getUuid());
         }
       }
     }
*/
            mHandler.sendEmptyMessage(MSG_GATTGOTSERVICE);
        }
    };

    // SCAN イベントコールバック
    private class bleScanCallback extends ScanCallback {
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            Log.d(TAG, "onBatchScanResults");
        }
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            int rssi = result.getRssi();
            mBtDevice = result.getDevice();
            ScanRecord rec = result.getScanRecord();
            String addr = mBtDevice.getAddress();
            // デバイス A 以外のアドバタイジングは無視
            if (!addr.equals(TARGET_ADDR)) {
                return;
            }
            Log.d(TAG, "RSSI:" + rssi);
            mTvAddr.setText("ADDRESS\n" + TARGET_ADDR);
            mTvRssi.setText("RSSI\n" + rssi);
            mHandler.sendEmptyMessage(MSG_FOUNDDEVICE);
        }
        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "onScanFailed: err=" + errorCode);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        mCtx = this;
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mTvAddr = (TextView) findViewById(R.id.tvAddr);
        mTvRssi = (TextView) findViewById(R.id.tvRssi);
        mButtonDisconn = (Button)findViewById(R.id.buttonDisconn);
        mButtonConn = (Button)findViewById(R.id.buttonConn);
        mButtonAlertOn = (Button)findViewById(R.id.buttonAlertOn);
        mButtonAlertOff = (Button)findViewById(R.id.buttonAlertOff);
        mButtonDisconn.setOnClickListener(this);
        mButtonConn.setOnClickListener(this);
        mButtonAlertOn.setOnClickListener(this);
        mButtonAlertOff.setOnClickListener(this);
        setButtonsVisibility(false);
        setButtonsEnabled(false);
        setTvColor(Color.LTGRAY);

        mHandler = new Handler(this);

        // 端末の Bluetooth アダプタのハンドルを取得
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBtAdapter == null) {
            // Bluetooth サポートなし
            showDialogMessage(this, "Device does not support Bluetooth.", true); // finish
        } else if (!mBtAdapter.isEnabled()) {
            // Bluetooth 無効状態
            Log.d(TAG, "Bluetooth is not enabled.");
            // 有効化する
            Intent it = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(it, REQ_ENABLE_BT);
        } else {
            mBtScanner = mBtAdapter.getBluetoothLeScanner();
            if (!mBtAdapter.isMultipleAdvertisementSupported()) {
                showDialogMessage(this, "isMultipleAdvertisementSupported NG.", true); // finish
            } else {
                // スキャン開始
                mHandler.sendEmptyMessage(MSG_DOSCAN);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult");
        switch (requestCode) {
            case REQ_ENABLE_BT:
                // Bluetooth 有効化 OK
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "REQ_ENABLE_BT OK");
                    mBtScanner = mBtAdapter.getBluetoothLeScanner();
                    // スキャン開始
                    mHandler.sendEmptyMessage(MSG_DOSCAN);
                } else {
                    Log.d(TAG, "REQ_ENABLE_BT Failed");
                    mHandler.sendEmptyMessage(MSG_ERROR); // finish
                }
                break;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
        // GATT 接続終了
        if (mBtGatt != null) {
            mBtGatt.close();
            mBtGatt = null;
        }
        // スキャン停止
        if (mBtScanner != null) {
            mBtScanner.stopScan(mScanCallback);
            mBtScanner = null;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == (View)mButtonDisconn) {
            mHandler.sendEmptyMessage(MSG_GATTDISCONNECT);
        } else if (v == (View)mButtonConn) {
            mHandler.sendEmptyMessage(MSG_GATTCONNECT);
        } else if (v == (View)mButtonAlertOn) {
            mHandler.sendEmptyMessage(MSG_ALERT_ON);
        } else if (v == (View)mButtonAlertOff) {
            mHandler.sendEmptyMessage(MSG_ALERT_OFF);
        }
        return;
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_DOSCAN: // アドバタイジングのスキャンを開始
                Log.d(TAG, "msg: MSG_DOSCAN");
                ScanSettings scanSettings = new ScanSettings.Builder().
                                setScanMode(SCAN_MODE).build();
                mBtScanner.startScan(null, scanSettings, mScanCallback);
                break;

            case MSG_STOPSCAN: // スキャン停止
                Log.d(TAG, "msg: MSG_STOPSCAN");
                mBtScanner.stopScan(mScanCallback);
                break;

            case MSG_FOUNDDEVICE: // デバイス A のアドバタイズパケットを検出
                Log.d(TAG, "msg: MSG_FOUNDDEVICE");
                setTvColor(Color.BLACK);
                setButtonsVisibility(true);
                break;

            case MSG_GATTCONNECT: // デバイス A への接続を開始
                Log.d(TAG, "msg: MSG_GATTCONNECT");
                showProgressMessage(getString(R.string.app_name), "デバイスへ接続中・・・");
                mBtGatt = mBtDevice.connectGatt(mCtx, false, mGattCallback);
                break;

            case MSG_GATTCONNECTED: // デバイス A への接続が完了
                Log.d(TAG, "msg: MSG_GATTCONNECTED");
                setTvColor(Color.LTGRAY);
                // デバイスの GATT サービス一覧の取得へ
                mBtGatt.discoverServices();
                mHandler.sendEmptyMessage(MSG_STOPSCAN);
                break;

            case MSG_GATTGOTSERVICE: // デバイス A の GATT サービス一覧取得完了
                Log.d(TAG, "msg: MSG_GATTGOTSERVICE");
                mProgressDlg.cancel();
                mProgressDlg = null;
                setButtonsEnabled(true);
                break;

            case MSG_GATTDISCONNECT: // デバイス A の切断
                Log.d(TAG, "msg: MSG_GATTDISCONNECT");
                mBtGatt.disconnect();
                break;

            case MSG_GATTDISCONNECTED: // デバイスとの切断完了
                Log.d(TAG, "msg: MSG_GATTDISCONNECTED");
                setButtonsEnabled(false);
                mBtGatt.close();
                mBtGatt = null;
                mHandler.sendEmptyMessage(MSG_DOSCAN);
                showDialogMessage(mCtx, "デバイスとの接続が切断されました", false);
                break;

            case MSG_ALERT_ON: // Alert ON
                Log.d(TAG, "msg: MSG_ALERT_ON");
                mChAlertLevel.setValue(mCmdAlertOn);
                mBtGatt.writeCharacteristic(mChAlertLevel);
                break;

            case MSG_ALERT_OFF: // Alert Off
                Log.d(TAG, "msg: MSG_ALERT_OFF");
                mChAlertLevel.setValue(mCmdAlertOff);
                mBtGatt.writeCharacteristic(mChAlertLevel);
                break;

            case MSG_PUSHED: // デバイス A の ボタンが押された
                Toast.makeText(mCtx, "* P U S H E D *", Toast.LENGTH_SHORT).show();
                ToneGenerator toneGenerator
                        = new ToneGenerator(AudioManager.STREAM_SYSTEM, ToneGenerator.MAX_VOLUME);
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_PING_RING);
                break;

            case MSG_ERROR:
                showDialogMessage(this, "処理を継続できないため終了します", true);
                break;
        }
        return true;
    }

    // ダイアログメッセージ
    private void showDialogMessage(Context ctx, String msg, final boolean bFinish) {
        new AlertDialog.Builder(ctx).setTitle(R.string.app_name)
                .setMessage(msg)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        if (bFinish) {
                            finish();
                        }
                    }
                }).show();
    }

    // プログレスメッセージ
    private void showProgressMessage(String title, String msg) {
        if (mProgressDlg != null) {
            return;
        }
        mProgressDlg = new ProgressDialog(this);
        mProgressDlg.setTitle(title);
        mProgressDlg.setMessage(msg);
        mProgressDlg.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        mProgressDlg.show();
    }

    private String bytesToHexString(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b & 0xff));
        }
        return new String(sb);
    }

    private void setButtonsEnabled(boolean isConnected) {
        mButtonConn.setEnabled(!isConnected);
        mButtonAlertOn.setEnabled(isConnected);
        mButtonAlertOff.setEnabled(isConnected);
        mButtonDisconn.setEnabled(isConnected);
    }

    private void setButtonsVisibility(boolean visible) {
        int v = (visible)? View.VISIBLE : View.INVISIBLE;
        mButtonConn.setVisibility(v);
        mButtonAlertOn.setVisibility(v);
        mButtonAlertOff.setVisibility(v);
        mButtonDisconn.setVisibility(v);
    }

    private void setTvColor(int color) {
        mTvRssi.setTextColor(color);
        mTvAddr.setTextColor(color);
    }
}
