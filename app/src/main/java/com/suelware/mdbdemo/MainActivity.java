package com.suelware.mdbdemo;

import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.suelware.mdbdemo.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;

public class MainActivity extends AppCompatActivity {

    public EditText log;
    private Button startMDB;
    private Logger logger;
    private HidBridge hid;
    private Thread mdbThread = null;
    private boolean runMdbThread = false;

    private String __TAG = "MDB";
    private byte[] idn = { 0x17, 0x00, 'S', 'L', 'W', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '1', 'C', 'A', 'S', 'H', '/', 'T', 'O', 'U', 'C', 'H', 'L', 'S', 0x00, 0x01 };

    private void slp(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {}
    }

    private Runnable mdbRunnable = new Runnable() {
        public void run() {
            hid.StartReadingThread();

            int state = 1;
            int reclen = 0;
            int sndlen = 0;
            byte[] recbuf = new byte[36];
            byte[] sndbuf = new byte[36];
            boolean done = false;
            while (runMdbThread) {
                switch (state) {
                    case 0:
                        done = true;
                        break;

                    case 1:
                        if (reclen > 0) {
                            logger.i(__TAG, "Cashless reset OK\n");
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x12;
                            sndlen = 2;
                            state = 2;
                        } else {
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x10;
                            sndlen = 2;
                        }
                        break;
                    case 2:
                        if (reclen > 0 && recbuf[0] == 1) {
                            logger.i(__TAG, "Cashless just reset OK\n");
                            sndbuf[0] = 6;
                            sndbuf[1] = 0x11;
                            sndbuf[2] = 0x00;
                            sndbuf[3] = 0x01;
                            sndbuf[4] = 0x00;
                            sndbuf[5] = 0x00;
                            sndbuf[6] = 0x00;
                            sndlen = 7;
                            state = 3;
                        } else {
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x12;
                            sndlen = 2;
                        }
                        break;

                    case 3:
                        if (reclen > 0 && recbuf[0] == 8) {
                            logger.i(__TAG, "Cashless setup OK\n");
                            sndbuf[0] = 6;
                            sndbuf[1] = 0x11;
                            sndbuf[2] = 0x01;
                            sndbuf[3] = 0x00;
                            sndbuf[4] = (byte) 0xC8;
                            sndbuf[5] = 0x00;
                            sndbuf[6] = 0x32;
                            sndlen = 7;
                            state = 4;
                        } else {
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x12;
                            sndlen = 2;
                        }
                        break;
                    case 4:
                        if (reclen > 0) {
                            logger.i(__TAG, "Cashless max/min OK\n");
                            sndbuf[0] = (byte) idn.length;
                            for (int i = 0; i < idn.length; i++) {
                                sndbuf[i + 1] = idn[i];
                            }
                            sndlen = idn.length + 1;
                            state = 5;
                        } else {
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x12;
                            sndlen = 2;
                        }
                        break;
                    case 5:
                        if (reclen > 0 && recbuf[0] == 30) {
                            logger.i(__TAG, "Manufacturer: " + getStrFromB(recbuf, 2, 4));
                            logger.i(__TAG, "Serial      : " + getStrFromB(recbuf, 5, 16));
                            logger.i(__TAG, "Model       : " + getStrFromB(recbuf, 17, 28));
                            logger.i(__TAG, "Enabling Cashless and entering SESSION IDLE\n");
                            sndbuf[0] = 2;
                            sndbuf[1] = 0x14;
                            sndbuf[2] = 0x01;
                            sndlen = 3;
                            state = 6;
                        } else {
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x12;
                            sndlen = 2;
                        }
                        break;
                    case 6:
                        if (reclen > 0) {
                            int tmp16 = 0;
                            if (recbuf[0] == 3 && recbuf[1] == 0x03) {
                                tmp16 = (recbuf[2] << 8) | recbuf[3];
                                logger.i(__TAG, String.format("Cashless Fund available: %d\n", tmp16));
                                sndbuf[0] = 6;
                                sndbuf[1] = 0x13;
                                sndbuf[2] = 0x00;
                                sndbuf[3] = 0x00;
                                sndbuf[4] = 0x64;
                                sndbuf[5] = 0x00;
                                sndbuf[6] = 0x01;
                                sndlen = 7;
                            } else if (recbuf[0] == 3 && recbuf[1] == 0x05) {
                                state = 7;
                                tmp16 = (recbuf[2] << 8) | recbuf[3];
                                logger.i(__TAG, String.format("Cashless approved: %d\n", tmp16));
                                sndbuf[0] = 4;
                                sndbuf[1] = 0x13;
                                sndbuf[2] = 0x02;
                                sndbuf[3] = 0x00;
                                sndbuf[4] = 0x01;
                                sndlen = 5;
                            } else {
                                sndbuf[0] = 1;
                                sndbuf[1] = 0x12;
                                sndlen = 2;
                            }

                        } else {
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x12;
                            sndlen = 2;
                        }
                        break;
                    case 7:
                        if (reclen > 0) {
                            logger.i(__TAG, "Sending SESSION COMPLETE\n");
                            sndbuf[0] = 2;
                            sndbuf[1] = 0x13;
                            sndbuf[2] = 0x04;
                            sndlen = 3;
                            state = 8;
                        } else {
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x12;
                            sndlen = 2;
                        }
                        break;
                    case 8:
                        if (reclen > 0) {
                            if (recbuf[0] == 1 && recbuf[1] == 0x07) {
                                logger.i(__TAG, "Entering SESSION IDLE\n");
                                sndbuf[0] = 2;
                                sndbuf[1] = 0x14;
                                sndbuf[2] = 0x01;
                                sndlen = 3;
                                state = 6;
                            } else {
                                sndbuf[0] = 1;
                                sndbuf[1] = 0x12;
                                sndlen = 2;
                            }
                        } else {
                            sndbuf[0] = 1;
                            sndbuf[1] = 0x12;
                            sndlen = 2;
                        }
                        break;
                }

                if (sndlen > 0) {
                    Log.d(__TAG, String.format("State %d, writing %d bytes...", state, sndlen));
                    byte[] sendBuffer = new byte[sndlen];
                    for (int i = 0; i < sndlen; i++) {
                        sendBuffer[i] = sndbuf[i];
                    }
                    hid.WriteData(sendBuffer);
                    sndlen = 0;
                }

                reclen = 0;
                slp(100);
                if (hid.IsThereAnyReceivedData()) {
                    byte[] rec = hid.GetReceivedDataFromQueue();
                    reclen = rec.length;
                    for (int i = 0; i < reclen; i++) {
                        recbuf[i] = rec[i];
                    }
                    Log.d(__TAG, String.format("read %d bytes; message size is %d\n", reclen, recbuf[0]));
                }
                slp(500);
            }

            hid.CloseTheDevice();
        }
    };

    private String getStrFromB(byte[] b, int start, int end) {
        if (b.length < end || start >= end) {
            return "";
        }
        String ret = "";
        for (int i=start; i<=end; i++) {
            ret = ret + Character.toString((char) b[i]);
        }
        return ret;
    }

    private void test() {
        if (mdbThread == null) {
            logger.i(__TAG, "Start MDB comm");
            runMdbThread = true;
            mdbThread = new Thread(mdbRunnable);
            mdbThread.start();
        }
    }

    private void stopTest() {
        if (mdbThread != null) {
            logger.i(__TAG, "Stop MDB comm");
            runMdbThread = false;
            mdbThread = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        log = (EditText) findViewById(R.id.log);
        startMDB = (Button) findViewById(R.id.startMDB);
        logger = new Logger();

        hid = new HidBridge(this, 0x82F2, 0x1FC9);
        if (hid.OpenDevice()) {
            logger.i(__TAG, "MDB converter found");
        }

        startMDB.setOnClickListener( (view -> {
            if (!runMdbThread) {
                startMDB.setText("Stop MDB");
                test();
            } else {
                startMDB.setText("Start MDB");
                stopTest();
            }

        }));
    }

    private class Logger {
        public void d(String tag, String message) {
            Log.d(tag, message);
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    log.append(message + "\n");
                }
            });
        }
        public void i(String tag, String message) {
            Log.i(tag, message);
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    log.append(message + "\n");
                }
            });
        }
        public void e(String tag, String message) {
            Log.e(tag, message);
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    log.append(message + "\n");
                }
            });
        }
    }
}