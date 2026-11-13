import com.nexusengine.app.GameApk;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

final class GameApkSmoke {
    public static void main(String[] args) throws Exception {
        char[] password = "test-only".toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream keyFile = new FileInputStream(args[0])) { keys.load(keyFile, password); }
        KeyStore.PrivateKeyEntry key = (KeyStore.PrivateKeyEntry) keys.getEntry(
            "test", new KeyStore.PasswordProtection(password));
        try (InputStream template = new FileInputStream(args[1])) {
            GameApk.build(template, new File(args[2], "unsigned.apk"), new File(args[2], "game.apk"),
                Files.readAllBytes(Paths.get("tools/smoke-game.html")), Files.readAllBytes(Paths.get(args[3])),
                "com.carlos.nexus.offlinegame", "Nexus لعبة 🎮", "2.3.4", 42, "portrait",
                key.getPrivateKey(), (X509Certificate) key.getCertificate());
        }
    }
}
