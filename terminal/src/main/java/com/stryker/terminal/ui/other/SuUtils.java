package com.stryker.terminal.ui.other;

import android.app.Activity;

import com.stryker.terminal.component.config.NeoTermPath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.function.Consumer;

public class SuUtils {

    private static final String EXECUTE = NeoTermPath.ROOT_PATH + "/chroot_exec ";
    private static final String ROOTLESS_MARKER = NeoTermPath.ROOT_PATH + "/rootless/vms/vm0/.active";
    private static final String GUEST_SENTINEL = "__STRYKER_EXIT__";
    private static final int GUEST_PORT = 1050;

    public static boolean isRootless(){
        return new java.io.File(ROOTLESS_MARKER).exists();
    }

    private static ArrayList<String> guestCommand(String command){
        ArrayList<String> out = new ArrayList<>();
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", GUEST_PORT), 4000);
            s.setSoTimeout(30000);
            OutputStream os = s.getOutputStream();
            os.write(("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                    + "export HOME=/root\n"
                    + command + "\n"
                    + "printf '\\n" + GUEST_SENTINEL + "%s\\n' \"$?\"\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(GUEST_SENTINEL)) break;
                out.add(line);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static ArrayList<String> customCommand(String command){
        if (isRootless()) return guestCommand(command);
        ArrayList<String> result = new ArrayList<>();
        Process process = generateSuProcess();
        try {
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            stdin.write((command + '\n').getBytes());
            stdin.write(("exit\n").getBytes());
            stdin.flush();
            stdin.close();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            String line;
            while ((line = br.readLine()) != null) {result.add(line);}
            br.close();
            BufferedReader br2 = new BufferedReader(new InputStreamReader(stderr));
            String lineError;
            while ((lineError = br2.readLine()) != null) {result.add(lineError);}
            br2.close();
        } catch (IOException e) {
        }

        process.destroy();
        return result;
    }

    public static ArrayList<String> chrootCommand(String command){
        if (isRootless()) return guestCommand(command);
        ArrayList<String> result = new ArrayList<>();
        Process process = generateSuProcess();
        try {
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            stdin.write((EXECUTE + "'bash'" + '\n').getBytes());
            stdin.write((command + '\n').getBytes());
            stdin.write(("exit\n").getBytes());
            stdin.flush();
            stdin.close();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            String line;
            while ((line = br.readLine()) != null) {result.add(line);}
            br.close();
            BufferedReader br2 = new BufferedReader(new InputStreamReader(stderr));
            String lineError;
            while ((lineError = br2.readLine()) != null) {result.add(lineError);}
            br2.close();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
        }
        process.destroy();
        return result;
    }

    public static Process generateSuProcess(){
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
        } catch (IOException e) {
            e.printStackTrace();

            try {
                process = Runtime.getRuntime().exec("echo Device is not rooted");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return  process;
    }

    public static boolean contains(ArrayList<String> list, String item){
        for (String s : list){if (s.contains(item)){return true;}}
        return false;
    }

    public static void getInterfacesList(Activity activity, Consumer<ArrayList<String>> l){
      new Thread(() -> {
        ArrayList<String> output = customCommand("ip link show | grep wlan");
        ArrayList<String> wlanList = new ArrayList<>();
        for (String s : output){
          if (s.contains("wlan")){
            wlanList.add(s.split(":")[1].trim());
          }
        }
        activity.runOnUiThread(() -> l.accept(wlanList));
      }).start();
    }

    public static void getConnectedUsbDevices(Activity activity, Consumer<ArrayList<String>> l){
      new Thread(() -> {
        ArrayList<String> output = customCommand("lsusb");
        ArrayList<String> usbList = new ArrayList<>(output);
        activity.runOnUiThread(() -> l.accept(usbList));
      }).start();
    }

    public static final class Iface {
        public final String name;
        public final String type;
        public Iface(String name, String type) {
            this.name = name;
            this.type = (type == null) ? "" : type;
        }
        public boolean isMonitor() { return type.toLowerCase().contains("monitor"); }
    }

    public static final class UsbInfo {
        public final String location;
        public final String id;
        public final String description;
        public UsbInfo(String location, String id, String description) {
            this.location = location;
            this.id = id;
            this.description = description;
        }
    }

    public static void getMonitorInterfaces(Activity activity, Consumer<ArrayList<Iface>> l){
        new Thread(() -> {
            ArrayList<String> raw = chrootCommand(
                "iw dev 2>/dev/null | awk '$1==\"Interface\"{n=$2} $1==\"type\"{if(n!=\"\"){print n\",\"$2; n=\"\"}}'");
            ArrayList<Iface> out = parseInterfaces(raw);
            activity.runOnUiThread(() -> l.accept(out));
        }).start();
    }

    private static ArrayList<Iface> parseInterfaces(ArrayList<String> raw){
        ArrayList<Iface> out = new ArrayList<>();
        for (String t : raw) {
            if (t == null) continue;
            String[] parts = t.trim().replaceAll("\\s+", " ").split(",");
            if (parts.length < 2) continue;
            String a = parts[0].trim();
            String b = parts[1].trim();
            String name, type;
            if (a.contains("wlan")) { name = a; type = b; }
            else if (b.contains("wlan")) { name = b; type = a; }
            else continue;
            if (!name.isEmpty()) out.add(new Iface(name, type));
        }
        return out;
    }

    public static void setMonitorMode(Activity activity, String ifc, boolean enable, Runnable done){
        new Thread(() -> {
            boolean host = !isRootless();
            if (enable) {
                if (host && isInternal(ifc)) customCommand("svc wifi disable");
                chrootCommand(host ? monitorCommand(ifc) : guestMonitorCommand(ifc));
            } else {
                if (host && isInternal(ifc)) customCommand("svc wifi disable");
                chrootCommand(host ? disableCommand(ifc) : guestDisableCommand(ifc));
                if (host && isInternal(ifc)
                        && !isMonitorEnabled(ifc)
                        && !isMonitorEnabled(ifc + "mon")) {
                    customCommand("ip link set " + ifc + " up");
                    customCommand("svc wifi enable");
                }
            }
            if (activity != null && done != null) activity.runOnUiThread(done);
        }).start();
    }

    private static boolean isMonitorEnabled(String ifc){
        ArrayList<String> raw = chrootCommand(
            "iw dev 2>/dev/null | awk '$1==\"Interface\"{n=$2} $1==\"type\"{if(n!=\"\"){print n\",\"$2; n=\"\"}}'");
        for (Iface i : parseInterfaces(raw)) {
            if (i.name.equals(ifc)) return i.isMonitor();
        }
        return false;
    }

    private static boolean isInternal(String ifc){
        return "wlan0".equals(ifc) || "swlan0".equals(ifc);
    }

    private static String monitorCommand(String ifc){
        if ("wlan0".equals(ifc))
            return "ip link set wlan0 down; echo '4' > /sys/module/wlan/parameters/con_mode; ip link set wlan0 up";
        if ("swlan0".equals(ifc))
            return "ip link set swlan0 down; echo '4' > /sys/module/wlan/parameters/con_mode; ip link set swlan0 up";
        return "airmon-ng start " + ifc;
    }

    private static String guestMonitorCommand(String ifc){
        return "rfkill unblock all 2>/dev/null; airmon-ng check kill >/dev/null 2>&1; "
                + "ip link set " + ifc + " down 2>/dev/null; "
                + "iw dev " + ifc + " set type monitor 2>/dev/null || airmon-ng start " + ifc + "; "
                + "ip link set " + ifc + " up 2>/dev/null";
    }

    private static String guestDisableCommand(String ifc){
        return "airmon-ng stop " + ifc + " 2>/dev/null; "
                + "ip link set " + ifc + " down 2>/dev/null; "
                + "iw dev " + ifc + " set type managed 2>/dev/null; "
                + "ip link set " + ifc + " up 2>/dev/null";
    }

    private static String disableCommand(String ifc){
        if ("wlan0".equals(ifc))
            return "ip link set wlan0 down; echo '0' > /sys/module/wlan/parameters/con_mode; ip link set wlan0 up";
        if ("swlan0".equals(ifc))
            return "ip link set swlan0 down; echo '0' > /sys/module/wlan/parameters/con_mode";
        return "airmon-ng stop " + ifc;
    }

    public static void getUsbDevicesDetailed(Activity activity, Consumer<ArrayList<UsbInfo>> l){
        new Thread(() -> {
            ArrayList<String> raw = chrootCommand("lsusb");
            if (!hasUsbLines(raw)) raw = customCommand("lsusb");
            ArrayList<UsbInfo> out = parseUsb(raw);
            activity.runOnUiThread(() -> l.accept(out));
        }).start();
    }

    private static boolean hasUsbLines(ArrayList<String> raw){
        for (String s : raw) {
            if (s != null && s.contains("ID ")) return true;
        }
        return false;
    }

    private static ArrayList<UsbInfo> parseUsb(ArrayList<String> raw){
        ArrayList<UsbInfo> out = new ArrayList<>();
        for (String line : raw) {
            if (line == null) continue;
            int idIdx = line.indexOf("ID ");
            if (idIdx < 0) continue;
            String location = line.substring(0, idIdx).replace(":", "").trim();
            String right = line.substring(idIdx + 3).trim();
            String id, description;
            int sp = right.indexOf(' ');
            if (sp > 0) {
                id = right.substring(0, sp).trim();
                description = right.substring(sp + 1).trim();
            } else {
                id = right;
                description = "";
            }
            out.add(new UsbInfo(location, id, description));
        }
        return out;
    }

    public static  boolean checkRoot(){
        return contains(customCommand("id"),"uid=0");
    }
}
