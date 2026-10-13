import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Python (tools/make_update.py) ile uretilen update.json imzasini Kotlin mantigiyla dogrular. */
public class VerifyUpdate {

    static final Pattern STR = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    static final Pattern NUM = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*(-?\\d+)");

    static String str(String json, String key) {
        Matcher m = STR.matcher(json);
        while (m.find()) if (m.group(1).equals(key)) return m.group(2).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        return "";
    }

    static int num(String json, String key) {
        Matcher m = NUM.matcher(json);
        while (m.find()) if (m.group(1).equals(key)) return Integer.parseInt(m.group(2));
        return 0;
    }

    public static void main(String[] args) throws Exception {
        String jsonPath = args[0];
        String pubPath = args[1];

        byte[] pub = Base64.getDecoder().decode(new String(Files.readAllBytes(Paths.get(pubPath)), StandardCharsets.UTF_8).trim());
        String json = new String(Files.readAllBytes(Paths.get(jsonPath)), StandardCharsets.UTF_8);

        int code = num(json, "versionCode");
        String name = str(json, "versionName");
        String sha = str(json, "sha256");
        String url = str(json, "apkUrl");
        int minSdk = num(json, "minSdk");
        String notes = str(json, "notes");

        StringBuilder sb = new StringBuilder();
        sb.append("gizli-update-v1").append('\n');
        sb.append(code).append('\n');
        sb.append(name).append('\n');
        sb.append(sha).append('\n');
        sb.append(url).append('\n');
        sb.append(minSdk).append('\n');
        sb.append(notes);
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);

        byte[] sig = Base64.getDecoder().decode(str(json, "signature"));

        Ed25519Signer ver = new Ed25519Signer();
        ver.init(false, new Ed25519PublicKeyParameters(pub, 0));
        ver.update(payload, 0, payload.length);
        boolean ok = ver.verifySignature(sig);

        System.out.println("CROSS-LANG IMZA DOGRULAMASI: " + (ok ? "GECERLI" : "GECERSIZ"));
        if (!ok) System.exit(1);
    }
}