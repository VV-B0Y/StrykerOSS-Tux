package com.zalexdev.stryker.wifi;

import static android.content.ContentValues.TAG;

import android.annotation.SuppressLint;
import android.os.AsyncTask;

import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.Core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScanWifi extends AsyncTask<Void, String, ArrayList<WiFINetwork>> {
    public String exec = Core.EXECUTE;
    public String wlan;
    public int count = 0;
    public int count2 = 0;
    public Core core;

    public ScanWifi(String whatwlan, Core c) {
        core = c;
        wlan = whatwlan;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        count = 0;
        count2 = 0;
    }

    @SuppressLint("WrongThread")
    @Override
    protected ArrayList<WiFINetwork> doInBackground(Void... command) {
        String line;
        ArrayList<WiFINetwork> result = new ArrayList<>();
        if (core.isRootless()) {
            ArrayList<String> out = core.customChrootCommand("iw dev " + wlan + " scan 2>&1");
            result = parsewifi(out);
            if (result.isEmpty()) {
                result = parseWifiLenient(out);
            }
            onPostExecute(result);
            return result;
        }
        // Chroot mode: scan with the Stryker chroot's own iw (/sbin/iw), independent of the Kali/
        // NetHunter /system/bin/iw symlink. 15s timeout so a stuck driver can't hang the scan.
        ArrayList<String> out = core.customCommandSuC(
                "chroot " + Core.CHROOT_ROOT + " /sbin/iw dev " + wlan + " scan 2>&1", 15000);
        result = parsewifi(out);
        if (result.isEmpty()) {
            result = parseWifiLenient(out);
        }
        onPostExecute(result);
        return result;
    }

    @Override
    protected void onPostExecute(ArrayList<WiFINetwork> result) {
        super.onPostExecute(result);

    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);

    }

    public ArrayList<WiFINetwork> parsewifi(ArrayList<String> output) {
        WiFINetwork wifi = new WiFINetwork();
        ArrayList<WiFINetwork> networks = new ArrayList<>();
        count = 0;
        count2 = 0;
        ArrayList<String> tempInfo = new ArrayList<>();
        for (int i = 0; i < output.size(); i++) {
            tempInfo.add(output.get(i));
            try{
            String temp = output.get(i).trim().replace("*", "");
            if (temp.contains("BSS") && temp.contains("wlan") && !temp.contains("Load") && !temp.contains("width") && !temp.contains("scan")) {
                if (tempInfo.size() > 0 && networks.size() > 0) {
                    tempInfo.remove(tempInfo.size() - 1);
                    networks.get(networks.size() - 1).setInfo(tempInfo);
                    tempInfo = new ArrayList<>();
                }
                Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(temp);
                String mac = "";
                if (m.find()) {
                    mac = m.group();
                }
                count = count + 1;
                wifi.setMac(mac);
                String vendor = core.getVendorByMacFromDB(wifi.getMac());
                if (!vendor.equals("")){
                    wifi.setVendor(vendor);}
                else{
                    wifi.setVendor("Unknown");
                }
            } else if (temp.contains("signal:")) {
                String power = temp.replace("signal:", "").replace("dBm", "");
                Matcher m = Pattern.compile("\\d+").matcher(power);
                if (m.find()){
                wifi.setPower(Integer.parseInt(m.group()));}
                count = count + 1;
            } else if (temp.contains("SSID:")) {
                String name = temp.replace("SSID:", "");
                if (name.contains("\\x")) {
                    try {
                        for(String s: core.customChrootCommand("echo -e \"SSID: " + name + "\"")){
                            if (s.contains("SSID:")){
                                name = s.replace("SSID:", "");
                            }
                        }
                    } catch (Exception e) {

                    }
                    if (name.contains("\\x") || name.length()<2) {
                        name = "Unsupported name";
                    }

                }
                if (name.length() != 0) {
                    wifi.setSsid(name);
                } else {
                    wifi.setSsid("Hidden network");
                }
                count = count + 1;
            } else if (temp.contains("DS Parameter set: channel") && count == 3) {
                String ch = temp.replace("DS Parameter set: channel", "");
                Matcher m = Pattern.compile("\\d+").matcher(ch);
                if (m.find()){
                wifi.setChannel(Integer.parseInt(m.group()));}
                count = count + 1;
            } else if (temp.contains("primary channel:") && count == 3) {
                String ch = temp.replace("primary channel:", "");
                wifi.setIs5hhz(true);
                Matcher m = Pattern.compile("\\d+").matcher(ch);
                if (m.find()){
                    wifi.setChannel(Integer.parseInt(m.group()));}
                count = count + 1;
            }
            if (count == 4) {

                networks.add(wifi);
                count = 0;
                wifi = new WiFINetwork();

            }
            if (networks.size()> 0) {
                if (temp.contains("WPS:") && temp.contains("Version")) {
                    networks.get(networks.size() - 1).setWps(true);
                } else if (temp.contains("Model:")) {
                    String model = temp.replace("Model:", "");
                    networks.get(networks.size() - 1).setModel(model);
                    networks.get(networks.size() - 1).setVulnerable(core.checkModel(model));
                    networks.get(networks.size() - 1).setVulnVerified(core.isPixieVerified(model));
                } else if (temp.contains("0x01")) {
                    networks.get(networks.size() - 1).setBlocked(true);
                }else if (temp.contains("Device name:")){
                    String name = temp.replace("Device name:", "");
                    networks.get(networks.size() - 1).setName(name);
                }
            }

            }
            catch (Exception e){
            }
        }
        if (tempInfo.size() > 0 && networks.size() > 0) {
            networks.get(networks.size() - 1).setInfo(tempInfo);
            tempInfo = new ArrayList<>();
        }
        return networks;
    }

    public ArrayList<WiFINetwork> parseWifiLenient(ArrayList<String> output) {
        ArrayList<WiFINetwork> networks = new ArrayList<>();
        WiFINetwork cur = null;
        ArrayList<String> info = new ArrayList<>();
        for (String raw : output) {
            if (raw == null) continue;
            String t = raw.trim().replace("*", "").trim();
            if (t.startsWith("BSS ") && t.contains(":")) {
                if (cur != null && cur.getMac() != null && !cur.getMac().isEmpty()) {
                    cur.setInfo(info);
                    networks.add(cur);
                }
                cur = new WiFINetwork();
                info = new ArrayList<>();
                Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(t);
                if (m.find()) {
                    cur.setMac(m.group());
                    String vendor = core.getVendorByMacFromDB(m.group());
                    cur.setVendor(vendor.isEmpty() ? "Unknown" : vendor);
                }
                cur.setSsid("Hidden network");
            } else if (cur != null) {
                info.add(raw);
                if (t.startsWith("SSID:")) {
                    String name = t.substring(5).trim();
                    if (!name.isEmpty() && !name.contains("\\x")) cur.setSsid(name);
                } else if (t.startsWith("signal:")) {
                    Matcher m = Pattern.compile("\\d+").matcher(t.replace("signal:", "").replace("dBm", ""));
                    if (m.find()) { try { cur.setPower(Integer.parseInt(m.group())); } catch (Exception ignored) {} }
                } else if (t.contains("WPS:") && t.contains("Version")) {
                    cur.setWps(true);
                } else if (t.contains("primary channel:")) {
                    cur.setIs5hhz(true);
                    Matcher m = Pattern.compile("\\d+").matcher(t.replace("primary channel:", ""));
                    if (m.find()) { try { cur.setChannel(Integer.parseInt(m.group())); } catch (Exception ignored) {} }
                } else if (t.contains("DS Parameter set: channel")) {
                    Matcher m = Pattern.compile("\\d+").matcher(t.replace("DS Parameter set: channel", ""));
                    if (m.find()) { try { cur.setChannel(Integer.parseInt(m.group())); } catch (Exception ignored) {} }
                } else if (t.startsWith("Model:")) {
                    String model = t.replace("Model:", "").trim();
                    cur.setModel(model);
                    cur.setVulnerable(core.checkModel(model));
                    cur.setVulnVerified(core.isPixieVerified(model));
                }
            }
        }
        if (cur != null && cur.getMac() != null && !cur.getMac().isEmpty()) {
            cur.setInfo(info);
            networks.add(cur);
        }
        return networks;
    }

}
