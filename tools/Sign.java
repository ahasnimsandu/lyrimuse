import com.android.apksig.ApkSigner;

import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/** Signs an APK with the debug keystore using apksig (v1 + v2 + v3). */
public class Sign {
    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File out = new File(args[1]);
        String ksPath = args[2];
        char[] pass = args[3].toCharArray();
        String alias = args.length > 4 ? args[4] : "androiddebugkey";

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (java.io.FileInputStream fis = new java.io.FileInputStream(ksPath)) {
            ks.load(fis, pass);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass);
        Certificate cert = ks.getCertificate(alias);
        if (key == null || cert == null) throw new IllegalStateException("keystore entry not found: " + alias);
        List<X509Certificate> certs = Collections.singletonList((X509Certificate) cert);

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder("LYRIMUSE", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(26)
                .build()
                .sign();
        System.out.println("signed -> " + out);
    }
}
