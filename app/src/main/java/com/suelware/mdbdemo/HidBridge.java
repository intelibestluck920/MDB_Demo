package com.suelware.mdbdemo;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

public class HidBridge {
    private Context _context;
    private int _productId;
    private int _vendorId;

    private static final String ACTION_USB_PERMISSION =
            "com.suelware.usb2mdb.USB_PERMISSION";

    // Locker object that is responsible for locking read/write thread.
    private final Object _locker = new Object();
    private Thread _readingThread = null;
    private boolean _runReadingThread = false;
    private boolean _permissionsGranted = false;
    private String _deviceName;
    private String __TAG = "HidBridge";

    private UsbManager _usbManager;
    private UsbDevice _usbDevice;

    // The queue that contains the read data.
    private Queue<byte[]> _receivedQueue;

    /**
     * Creates a hid bridge to the device. Should only be created once.
     * @param context is the UI context of Android.
     * @param productId of the device.
     * @param vendorId of the device.
     */
    public HidBridge(Context context, int productId, int vendorId) {
        _context = context;
        _productId = productId;
        _vendorId = vendorId;
        _receivedQueue = new LinkedList<byte[]>();
    }

    /**
     * Searches for the device and opens it if successful
     * @return true if connection was successful
     */
    public boolean OpenDevice() {
        _usbManager = (UsbManager) _context.getSystemService(Context.USB_SERVICE);

        HashMap<String, UsbDevice> deviceList = _usbManager.getDeviceList();

        Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
        _usbDevice = null;

        while(deviceIterator.hasNext()){
            UsbDevice device = deviceIterator.next();
            if (device.getProductId() == _productId && device.getVendorId() == _vendorId) {
                _usbDevice = device;
                _deviceName = _usbDevice.getDeviceName();
            }
        }

        if (_usbDevice == null) {
            Log("Cannot find the device. Did you forgot to plug it in?");
            return false;
        }

        // Create and intent and request a permission.
        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(_context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        _context.registerReceiver(mUsbReceiver, filter);

        _usbManager.requestPermission(_usbDevice, mPermissionIntent);
        Log("Found device with s/n " + _usbDevice.getSerialNumber());
        return true;
    }

    /**
     * Closes the reading thread of the device.
     */
    public void CloseTheDevice() {
        try
        {
            StopReadingThread();
            _context.unregisterReceiver(mUsbReceiver);
        }
        catch(RuntimeException e)
        {
            Log("Error occurred while closing device.");
        }
    }

    /**
     * Starts thread that continuously reads data from the device.
     */
    public void StartReadingThread() {
        if (_readingThread == null) {
            _runReadingThread = true;
            _readingThread = new Thread(readerReceiver);
            _readingThread.start();
        } else {
            Log("Reader thread already started");
        }
    }

    /**
     * Stops the thread that continuously reads the data from the device.
     */
    public void StopReadingThread() {
        if (_readingThread != null) {
            _runReadingThread = false;
            _readingThread = null;
        } else {
            Log("No reader thread to stop");
        }
    }

    /**
     * Write data to the usb hid.
     * @param bytes is the data to be written.
     * @return true if succeeded.
     */
    public boolean WriteData(byte[] bytes) {
        try
        {
            // Lock that is common for read/write methods.
            synchronized (_locker) {
                UsbInterface writeIntf = _usbDevice.getInterface(0);
                UsbEndpoint writeEp = writeIntf.getEndpoint(1);
                UsbDeviceConnection writeConnection = _usbManager.openDevice(_usbDevice);

                // Lock the usb interface.
                writeConnection.claimInterface(writeIntf, true);

                // Write data as bulk transfer with defined data length.
                int r = writeConnection.bulkTransfer(writeEp, bytes, bytes.length, 0);
                if (r != -1) {
                    Log(String.format("Written %s bytes to the device. Data written: %s", r, composeString(bytes)));
                } else {
                    Log("Error occurred while writing data. No ACK");
                }

                // Release usb interface.
                writeConnection.releaseInterface(writeIntf);
                writeConnection.close();
            }

        } catch(NullPointerException e)
        {
            Log("Error occurred while writing. Could not connect to the device or interface is busy");
            Log.e(__TAG, Log.getStackTraceString(e));
            return false;
        }
        return true;
    }

    /**
     * @return true if there is any data in the queue to be read.
     */
    public boolean IsThereAnyReceivedData() {
        synchronized(_locker) {
            return !_receivedQueue.isEmpty();
        }
    }

    /**
     * Retrieve data from read queue.
     * @return queued data.
     */
    public byte[] GetReceivedDataFromQueue() {
        synchronized(_locker) {
            return _receivedQueue.poll();
        }
    }

    // The thread that continuously receives data from the device and puts it into the queue.
    private Runnable readerReceiver = new Runnable() {
        public void run() {
            if (_usbDevice == null) {
                Log("No device to read from");
                return;
            }

            UsbEndpoint readEp;
            UsbDeviceConnection readConnection = null;
            UsbInterface readIntf = null;
            boolean readerStartedMsgWasShown = false;

            while (_runReadingThread) {
                // Lock that is common for read/write methods.
                synchronized (_locker) {
                    try
                    {
                        if (_usbDevice == null) {
                            OpenDevice();
                            Log("No device. Re-checking in 10 sec...");

                            Sleep(10000);
                            continue;
                        }

                        readIntf = _usbDevice.getInterface(0);
                        readEp = readIntf.getEndpoint(0);
                        if (!_usbManager.getDeviceList().containsKey(_deviceName)) {
                            Log("Failed to connect to the device. Retrying to acquire it.");
                            OpenDevice();
                            if (!_usbManager.getDeviceList().containsKey(_deviceName)) {
                                Log("No device. Re-checking in 10 sec...");

                                Sleep(10000);
                                continue;
                            }
                        }

                        try
                        {
                            readConnection = _usbManager.openDevice(_usbDevice);

                            if (readConnection == null) {
                                Log("Cannot start reader because user didn't grant permissions or the device is not present. Retrying in 2 sec...");
                                Sleep(2000);
                                continue;
                            }

                            // Claim and lock the interface
                            readConnection.claimInterface(readIntf, true);
                        }
                        catch (SecurityException e) {
                            Log("Cannot start reader because user didn't grant permissions. Retrying in 2 sec...");

                            Sleep(2000);
                            continue;
                        }

                        if (!readerStartedMsgWasShown) {
                            Log("!!! Reader was started !!!");
                            readerStartedMsgWasShown = true;
                        }

                        // Read the data as a bulk transfer with size = MaxPacketSize
                        int packetSize = readEp.getMaxPacketSize();
                        byte[] bytes = new byte[packetSize];
                        int r = readConnection.bulkTransfer(readEp, bytes, packetSize, 100);
                        if (r >= 0) {
                            byte[] receivedBytes = new byte[r];
                            for (int i=0; i<r; i++) {
                                receivedBytes[i] = bytes[i];
                            }
                            _receivedQueue.add(receivedBytes); // Store received data
                            Log(String.format("Message received of length %s and content: %s", r, composeString(receivedBytes)));
                        }

                        // Release the interface lock.
                        readConnection.releaseInterface(readIntf);
                        readConnection.close();
                    }

                    catch (NullPointerException e) {
                        Log("Error occurred while reading. No device or the connection is busy");
                        Log.e(__TAG, Log.getStackTraceString(e));
                    }
                    catch (ThreadDeath e) {
                        if (readConnection != null) {
                            readConnection.releaseInterface(readIntf);
                            readConnection.close();
                        }

                        throw e;
                    }
                }

                // As both read and write data methods lock each other - they cannot be run in parallel.
                // Looks like Android is not so smart in handling the threads, so we need to pause a while
                // to switch the thread context.
                Sleep(10);
            }
        }
    };

    private void Sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public boolean hasPermission() {
        return true; //_permissionsGranted;
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            Log("permissions granted");
                        }
                    }
                }
            }
        }
    };

    /**
     * Logs the message from HidBridge.
     * @param message to log.
     */
    private void Log(String message) {
        Log.i(__TAG, message);
    }

    /**
     * Composes a hex string from byte array.
     */
    private String composeString(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b: bytes) {
            builder.append(String.format("%02x", b));
            builder.append(" ");
        }

        return builder.toString();
    }
}