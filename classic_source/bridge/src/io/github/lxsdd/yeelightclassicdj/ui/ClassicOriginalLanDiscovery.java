package io.github.lxsdd.yeelightclassicdj.ui;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit, bounded, READ-ONLY Yeelight LAN discovery for the original Classic
 * GroupMusic list. Original T() identity and LAN "id" are DIFFERENT domains:
 * only an EXACT, positive id match is accepted; never guess from name/model/IP.
 *
 * A matched endpoint is not a Music-session ownership claim. Music start,
 * power and set_scene remain behind a separate user confirmation and the
 * generation-fenced ClassicMusicCoordinator. No persistent device directory,
 * cloud account, secrets, OEM objects or discovery response logs are created.
 */
public final class ClassicOriginalLanDiscovery {
    private static final int MAX_REPLY = 4096;
    private static final int MAX_REPLIES = 512;
    private static final long FRESH_MS = 90_000L;
    private static final Pattern LOCATION = Pattern.compile(
        "^yeelight://([0-9.]+):55443$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAN_ID = Pattern.compile("^0x[0-9a-fA-F]{16}$");
    private static final byte[] REQUEST = (
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1982\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "ST: wifi_bulb\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

    public static final class VerifiedTargets {
        private final LinkedHashMap<String,String> resolved;
        private final long observedAtMs;
        private VerifiedTargets(Map<String,String> targets, long observedAtMs) {
            this.resolved = new LinkedHashMap<>(targets);
            this.observedAtMs = observedAtMs;
        }
        boolean freshAt(long nowMs) {
            return nowMs >= observedAtMs && nowMs - observedAtMs <= FRESH_MS;
        }
        Map<String,String> copy() {
            return new LinkedHashMap<>(resolved);
        }
    }

    private static final class Observation {
        final String ip;
        final long atMs;
        Observation(String ip, long atMs) { this.ip = ip; this.atMs = atMs; }
    }

    /** Synthetic tests exercise this SAME production parser, no network. */
    static final class Witness {
        private final Map<String,Observation> byId = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();
        private final Map<String,String> ipOwners = new HashMap<>();

        boolean observe(String raw, String senderIp, long atMs) {
            if (atMs < 0L || raw == null || raw.length() > MAX_REPLY ||
                !privateIpv4(senderIp) || !raw.startsWith("HTTP/1.1 200 OK\r\n"))
                return false;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c != '\r' && c != '\n' && (c < 32 || c > 126))
                    return false;
            }
            String[] lines = raw.split("\r\n", -1);
            if (lines.length < 5 || lines.length > 80) return false;
            HashMap<String,String> headers = new HashMap<>();
            for (int i=1; i<lines.length; i++) {
                String line=lines[i];
                if (line.isEmpty()) break;
                int colon=line.indexOf(':');
                if (colon < 1 || colon > 40) return false;
                String key=line.substring(0,colon).trim().toLowerCase(java.util.Locale.ROOT);
                String value=line.substring(colon+1).trim();
                if (!key.matches("[a-z][a-z0-9-]*") ||
                    headers.put(key,value) != null) return false;
            }
            String id=headers.get("id"), location=headers.get("location");
            String model=headers.get("model"), support=headers.get("support");
            if (id == null || !LAN_ID.matcher(id).matches() ||
                !"color4".equalsIgnoreCase(model) ||
                location == null || support == null) return false;
            Matcher url=LOCATION.matcher(location);
            if (!url.matches() || !senderIp.equals(url.group(1))) return false;
            Set<String> methods = new HashSet<>(Arrays.asList(support.split("\\s+")));
            if (!methods.contains("get_prop") || !methods.contains("set_music") ||
                !methods.contains("set_scene")) return false;
            if (ambiguous.contains(id)) return false;
            Observation previous=byId.get(id);
            if (previous != null && !previous.ip.equals(senderIp)) {
                ambiguous.add(id);
                byId.remove(id);
                return false;
            }
            String priorOwner=ipOwners.get(senderIp);
            if (priorOwner != null && !priorOwner.equals(id)) {
                ambiguous.add(priorOwner);
                ambiguous.add(id);
                byId.remove(priorOwner);
                byId.remove(id);
                return false;
            }
            ipOwners.put(senderIp,id);
            byId.put(id,new Observation(senderIp,atMs));
            return true;
        }

        VerifiedTargets resolveAll(List<String> originalKeys, long nowMs) {
            if (nowMs < 0 || originalKeys == null ||
                originalKeys.isEmpty() || originalKeys.size() > 512 ||
                new HashSet<>(originalKeys).size() != originalKeys.size())
                return null;
            LinkedHashMap<String,String> result=new LinkedHashMap<>();
            HashSet<String> uniqueIps=new HashSet<>();
            for (String key : originalKeys) {
                if (key == null || !LAN_ID.matcher(key).matches() ||
                    ambiguous.contains(key)) return null;
                Observation hit=byId.get(key);
                if (hit == null || nowMs < hit.atMs ||
                    nowMs - hit.atMs > FRESH_MS ||
                    !uniqueIps.add(hit.ip)) return null;
                result.put(key,hit.ip);
            }
            return new VerifiedTargets(result,nowMs);
        }
    }

    public static boolean privateIpv4(String ip) {
        if (ip == null) return false;
        String[] values=ip.split("\\.",-1);
        if (values.length!=4) return false;
        int[] parts=new int[4];
        for (int i=0;i<4;i++) {
            if (!values[i].matches("(0|[1-9][0-9]{0,2})")) return false;
            try { parts[i]=Integer.parseInt(values[i]); }
            catch (NumberFormatException bad) { return false; }
            if (parts[i]>255) return false;
        }
        return ((parts[0]==10) ||
                (parts[0]==172 && parts[1]>=16 && parts[1]<=31) ||
                (parts[0]==192 && parts[1]==168)) &&
            parts[3]>=1 && parts[3]<=254;
    }

    /**
     * ONE explicitly user-confirmed inquiry, no retries, no scans in app
     * startup/background, no account/scene/device writes. Called off the UI
     * thread. Only IPv4 endpoints sourced from actual UDP sender addresses
     * are considered. Missing/ambiguous replies revoke the entire cohort.
     */
    public static VerifiedTargets discover(List<String> originalKeys,
                                           String wifiIp) throws IOException {
        if (!privateIpv4(wifiIp) || originalKeys == null || originalKeys.isEmpty() ||
            originalKeys.size() > 512)
            return null;
        Witness evidence=new Witness();
        InetAddress sender=InetAddress.getByName(wifiIp);
        long deadline=System.nanoTime()+1_600_000_000L;
        try (DatagramSocket socket=new DatagramSocket(
                new InetSocketAddress(sender,0))) {
            socket.setSoTimeout(180);
            DatagramPacket query=new DatagramPacket(REQUEST,REQUEST.length,
                InetAddress.getByName("239.255.255.250"),1982);
            socket.send(query);
            byte[] bytes=new byte[MAX_REPLY+1];
            for (int n=0;n<MAX_REPLIES && System.nanoTime()<deadline;n++) {
                DatagramPacket reply=new DatagramPacket(bytes,bytes.length);
                try { socket.receive(reply); }
                catch (SocketTimeoutException noReply) { continue; }
                if (reply.getLength()<1 || reply.getLength()>MAX_REPLY) continue;
                String text=new String(reply.getData(),reply.getOffset(),
                    reply.getLength(),StandardCharsets.US_ASCII);
                evidence.observe(text,reply.getAddress().getHostAddress(),
                                 System.nanoTime()/1_000_000L);
            }
        }
        return evidence.resolveAll(originalKeys,System.nanoTime()/1_000_000L);
    }

    private ClassicOriginalLanDiscovery() {}
}
