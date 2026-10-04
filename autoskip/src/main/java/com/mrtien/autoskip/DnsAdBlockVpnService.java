package com.mrtien.autoskip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class DnsAdBlockVpnService extends VpnService {

    public static final String ACTION_START = "com.mrtien.autoskip.START_DNS_BLOCK";
    public static final String ACTION_STOP = "com.mrtien.autoskip.STOP_DNS_BLOCK";

    private static final String CHANNEL_ID = "dns_ad_block";
    private static final int NOTIFICATION_ID = 2201;
    private static final String VPN_DNS_IP = "10.111.0.2";
    private static final String[] UPSTREAM_DNS = {"94.140.14.14", "94.140.15.15"};

    private static volatile boolean running;

    private ParcelFileDescriptor tunnel;
    private Thread worker;
    private volatile boolean stopRequested;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MainActivity.KEY_DNS_BLOCK_WANTED, false)
                    .apply();
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(MainActivity.KEY_DNS_BLOCK_WANTED, true)
                .apply();

        startForeground(NOTIFICATION_ID, buildNotification("Đang chặn quảng cáo từ mạng"));
        if (!running) {
            startVpn();
        }
        return START_STICKY;
    }

    private synchronized void startVpn() {
        if (running) return;

        try {
            Builder builder = new Builder()
                    .setSession("Auto Skip - DNS Ad Block")
                    .setMtu(1500)
                    .addAddress("10.111.0.1", 32)
                    .addRoute(VPN_DNS_IP, 32)
                    .addDnsServer(VPN_DNS_IP)
                    .setBlocking(true);

            tunnel = builder.establish();
            if (tunnel == null) {
                running = false;
                updateNotification("Không tạo được VPN DNS");
                return;
            }

            stopRequested = false;
            running = true;
            worker = new Thread(this::packetLoop, "AutoSkip-DNS");
            worker.start();
        } catch (Throwable t) {
            running = false;
            updateNotification("Lỗi khởi động chặn quảng cáo");
        }
    }

    private void packetLoop() {
        byte[] packet = new byte[32767];

        try (FileInputStream input = new FileInputStream(tunnel.getFileDescriptor());
             FileOutputStream output = new FileOutputStream(tunnel.getFileDescriptor())) {

            while (!stopRequested) {
                int length = input.read(packet);
                if (length <= 0) continue;

                byte[] response = handleDnsPacket(packet, length);
                if (response != null && response.length > 0) {
                    output.write(response);
                    output.flush();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            running = false;
        }
    }

    private byte[] handleDnsPacket(byte[] packet, int length) {
        if (length < 28) return null;

        int version = (packet[0] >> 4) & 0x0F;
        if (version != 4) return null;

        int ihl = (packet[0] & 0x0F) * 4;
        if (ihl < 20 || length < ihl + 8) return null;
        if ((packet[9] & 0xFF) != 17) return null; // UDP only

        int udpOffset = ihl;
        int dstPort = readU16(packet, udpOffset + 2);
        if (dstPort != 53) return null;

        int dnsOffset = udpOffset + 8;
        int dnsLength = length - dnsOffset;
        if (dnsLength <= 0) return null;

        byte[] dnsQuery = Arrays.copyOfRange(packet, dnsOffset, length);
        byte[] dnsResponse = queryUpstream(dnsQuery);
        if (dnsResponse == null) return null;

        int totalLength = ihl + 8 + dnsResponse.length;
        byte[] out = Arrays.copyOf(packet, totalLength);

        // Swap IPv4 source/destination.
        for (int i = 0; i < 4; i++) {
            byte tmp = out[12 + i];
            out[12 + i] = out[16 + i];
            out[16 + i] = tmp;
        }

        out[8] = 64; // TTL
        writeU16(out, 2, totalLength);

        int originalSrcPort = readU16(packet, udpOffset);
        writeU16(out, udpOffset, 53);
        writeU16(out, udpOffset + 2, originalSrcPort);
        writeU16(out, udpOffset + 4, 8 + dnsResponse.length);
        writeU16(out, udpOffset + 6, 0); // UDP checksum may be zero for IPv4.

        System.arraycopy(dnsResponse, 0, out, dnsOffset, dnsResponse.length);

        writeU16(out, 10, 0);
        writeU16(out, 10, ipv4Checksum(out, 0, ihl));
        return out;
    }

    private byte[] queryUpstream(byte[] query) {
        for (String server : UPSTREAM_DNS) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                protect(socket);
                socket.setSoTimeout(2500);

                InetAddress upstream = InetAddress.getByName(server);
                DatagramPacket request = new DatagramPacket(query, query.length, upstream, 53);
                socket.send(request);

                byte[] responseBuffer = new byte[8192];
                DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(response);
                return Arrays.copyOf(response.getData(), response.getLength());
            } catch (SocketTimeoutException ignored) {
            } catch (Throwable ignored) {
            } finally {
                if (socket != null) socket.close();
            }
        }
        return null;
    }

    private int ipv4Checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int end = offset + length;
        for (int i = offset; i + 1 < end; i += 2) {
            int word = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += word;
            while ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >>> 16);
            }
        }
        if ((length & 1) != 0) {
            sum += (data[end - 1] & 0xFF) << 8;
            while ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >>> 16);
            }
        }
        return (int) (~sum) & 0xFFFF;
    }

    private int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Chặn quảng cáo từ mạng",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("VPN cục bộ chỉ chuyển tiếp DNS qua máy chủ chặn quảng cáo.");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(
                this,
                1,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stop = new Intent(this, DnsAdBlockVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                2,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Auto Skip & Paste")
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_launcher,
                        "Tắt chặn mạng",
                        stopIntent
                ).build())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private synchronized void stopVpn() {
        stopRequested = true;
        running = false;
        if (tunnel != null) {
            try {
                tunnel.close();
            } catch (Throwable ignored) {
            }
            tunnel = null;
        }
        if (worker != null) {
            try {
                worker.interrupt();
            } catch (Throwable ignored) {
            }
            worker = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
